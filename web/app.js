/* audio.cpp-hub 前端逻辑：模型分组 / 实例管理 / 四类任务面板 / 主题切换 */

const t = (k, p) => I18N.t(k, p);
const STATUS_CLASS = { STARTING: "starting", READY: "ready", ERROR: "error", STOPPED: "stopped" };
function statusText(s) {
  const v = t("instance.status." + s);
  return v === "instance.status." + s ? s : v;
}
const CATEGORY_ORDER = ["tts", "asr", "sep", "other"];
function categoryName(cat) {
  return t("category." + cat);
}
const SUBMIT_BTNS = ["tts-submit", "asr-submit", "sep-submit", "other-submit"];
const SUBMIT_KEYS = { "tts-submit": "tts.submit", "asr-submit": "asr.submit", "sep-submit": "sep.submit", "other-submit": "other.submit" };
function submitLabel(id) {
  return t(SUBMIT_KEYS[id]);
}
/* 不从 paramSchema 自动渲染为高级参数的键（有专属 UI 或语义特殊） */
const RESERVED_KEYS = new Set([
  "emotionModes", "emotionLabels", "emotion_alpha",
  "text", "voice_ref", "language", "speaker", "instruct", "reference_text", "task_route"
]);

let models = [];
let instances = [];
let executables = [];
let profiles = [];
let selectedModelId = null;
let activeInstanceId = null;
let emotionMode = "none";
const emotionVector = new Array(8).fill(0);
let ttsVariant = "base";
let ttsLanguageSel = null;
let asrLanguageSel = null;
let otherLanguageSel = null;
let ttsAudioUrl = null;
/* VibeVoice 多说话人：每行一个 AudioPicker，第 N 行对应脚本里的 Speaker N（voice_samples 顺序） */
let speakerPickers = [];
const VIBEVOICE_MAX_SPEAKERS = 4;

const $ = (id) => document.getElementById(id);
const el = (html) => {
  const t = document.createElement("template");
  t.innerHTML = html.trim();
  return t.content.firstChild;
};
const selectedModel = () => models.find(m => m.id === selectedModelId);

/* ---------- 主题切换 ---------- */
const themeBtn = $("theme-toggle");
function applyThemeIcon() {
  themeBtn.textContent = document.documentElement.dataset.theme === "dark" ? "☀️" : "🌙";
}
themeBtn.onclick = () => {
  const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  localStorage.setItem("hub-theme", next);
  applyThemeIcon();
  window.dispatchEvent(new Event("themechange"));
};
applyThemeIcon();

/* ---------- 语言切换 ---------- */
const langBtn = $("lang-toggle");
function applyLangBtn() {
  langBtn.textContent = I18N.lang() === "zh" ? "EN" : "中文";
}
langBtn.onclick = () => I18N.setLang(I18N.lang() === "zh" ? "en" : "zh");
function rerenderAll() {
  applyLangBtn();
  if (models.length) { renderModelList(); updateQuickLaunchTitle(); }
  renderExecList(); updateLaunchExec(); renderLaunchProfiles();
  renderInstanceList(); updateInstanceBar();
  buildEmotionSliders();
  if (selectedModel()) renderWorkspace();
  if (!settingsModal.classList.contains("hidden")) {
    syncGeneralPane();
    if (lastCertStatus) renderCertStatus(lastCertStatus);
  }
  if (!$("downloads-modal").classList.contains("hidden")) renderDownloadList();
  if (!$("model-dl-modal").classList.contains("hidden") && mdlPackages) renderMdlPackages();
  (window.__audioPickers || []).forEach(p => p.refreshLabels && p.refreshLabels());
  if (window.FileBrowser && FileBrowser.relocalize) FileBrowser.relocalize();
}

/* ---------- 移动端抽屉菜单（模型/实例列表） ---------- */
function openDrawer() {
  $("left").classList.add("open");
  $("drawer-overlay").classList.remove("hidden");
}
function closeDrawer() {
  $("left").classList.remove("open");
  $("drawer-overlay").classList.add("hidden");
}
$("menu-toggle").onclick = openDrawer;
$("drawer-overlay").onclick = closeDrawer;

/* ---------- 历史侧边栏抽屉（窄屏弹出，宽屏常驻右侧） ---------- */
function openHistoryDrawer() {
  $("history-sidebar").classList.add("open");
  $("history-overlay").classList.remove("hidden");
}
function closeHistoryDrawer() {
  $("history-sidebar").classList.remove("open");
  $("history-overlay").classList.add("hidden");
}
$("history-fab").onclick = openHistoryDrawer;
$("history-overlay").onclick = closeHistoryDrawer;
$("history-close").onclick = closeHistoryDrawer;

/* ---------- 模型列表（按 category 分组） ---------- */
async function loadModels() {
  const res = await fetch("/api/models");
  models = await res.json();
  if (models.length && !selectedModelId) {
    // 刷新后恢复上次选中的模型（否则回到第一个模型，其历史/实例视图会让用户误以为数据丢失）
    const saved = localStorage.getItem("hub-model");
    selectedModelId = models.some(m => m.id === saved) ? saved : models[0].id;
  }
  renderModelList();
  updateQuickLaunchTitle();
  restoreWeightsPath();
  renderWorkspace();
}

/* 已配置 = 任一使用记录（Profile）的权重有效，且当前存在至少一个可用的 audiocpp_server。
   注意：Profile 关联的 executableId 可能已失效（可执行文件被删除/重加），
   但启动弹窗可改选其他可执行文件，所以不把失效的关联当作"未配置"。 */
function modelConfigured(m) {
  const weightsOk = profiles.some(x => x.modelId === m.id && x.weightsPath && x.weightsExists !== false);
  return weightsOk && executables.some(e => e.exists);
}

let hfMenuEl = null;
let hfMenuAnchor = null;

function hfMirrorOf(url) {
  return url ? url.replace("https://huggingface.co/", "https://hf-mirror.com/") : null;
}

function closeHfMenu() {
  if (hfMenuEl) hfMenuEl.classList.remove("open");
  if (hfMenuAnchor) hfMenuAnchor.classList.remove("open");
  hfMenuAnchor = null;
}

function openHfMenu(anchor, m) {
  if (!hfMenuEl) {
    hfMenuEl = document.createElement("div");
    hfMenuEl.id = "hf-menu";
    document.body.appendChild(hfMenuEl);
    hfMenuEl.addEventListener("click", (e) => { if (e.target.closest("a")) closeHfMenu(); });
  }
  const items = [
    { label: t("model.hfMenu.hf"), url: m.hfUrl },
    { label: t("model.hfMenu.mirror"), url: hfMirrorOf(m.hfUrl) },
    { label: t("model.hfMenu.gguf"), url: m.ggufUrl },
    { label: t("model.hfMenu.ggufMirror"), url: hfMirrorOf(m.ggufUrl) },
  ].filter(x => x.url);
  hfMenuEl.innerHTML = items.map(x => `<a href="${x.url}" target="_blank" rel="noopener">${x.label}<span class="hf-menu-ext">↗</span></a>`).join("");
  closeHfMenu();
  hfMenuAnchor = anchor;
  anchor.classList.add("open");
  hfMenuEl.classList.add("open");
  const r = anchor.getBoundingClientRect();
  const mw = hfMenuEl.offsetWidth, mh = hfMenuEl.offsetHeight;
  let top = r.bottom + 6;
  if (top + mh > window.innerHeight - 8) top = Math.max(8, r.top - mh - 6);
  let right = window.innerWidth - r.right;
  if (right + mw > window.innerWidth - 8) right = 8;
  hfMenuEl.style.top = top + "px";
  hfMenuEl.style.right = right + "px";
}

function toggleHfMenu(anchor, m) {
  if (hfMenuAnchor === anchor) { closeHfMenu(); return; }
  openHfMenu(anchor, m);
}

document.addEventListener("mousedown", (e) => {
  if (hfMenuAnchor && !e.target.closest("#hf-menu") && !e.target.closest(".hf-link")) closeHfMenu();
});
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeHfMenu(); });
document.addEventListener("scroll", closeHfMenu, true);
window.addEventListener("resize", closeHfMenu);

function renderModelList() {
  closeHfMenu();
  const list = $("model-list");
  list.innerHTML = "";
  for (const cat of CATEGORY_ORDER) {
    const group = models.filter(m => m.category === cat);
    if (group.length === 0) continue;
    const title = document.createElement("div");
    title.className = "group-title";
    title.textContent = categoryName(cat);
    list.appendChild(title);
    for (const m of group) {
      const usable = modelConfigured(m);
      const card = document.createElement("div");
      card.className = "card" + (m.id === selectedModelId ? " selected" : "") + (usable ? "" : " unconfigured");
      card.innerHTML = `<div class="card-title">${I18N.pick(m, "displayName")}${usable ? "" : ` <span class="badge unconfigured">${t("model.unconfigured")}</span>`}<button class="dl-link" title="${t("dl.cardBtn")}">⬇</button>${m.hfUrl ? `<button class="hf-link" title="${t("model.hfRepo")}">HF ▾</button>` : ""}</div>
        <div class="card-family">${m.family} <span class="cat-badge cat-${cat}">${categoryName(cat)}</span></div>
        <div class="card-desc">${I18N.pick(m, "description")}</div>`;
      if (!usable) card.title = t("model.unconfiguredTip");
      card.querySelector(".dl-link").onclick = (e) => { e.stopPropagation(); openModelDlModal(m); };
      const hfBtn = card.querySelector(".hf-link");
      if (hfBtn) hfBtn.onclick = (e) => { e.stopPropagation(); toggleHfMenu(hfBtn, m); };
      card.onclick = () => {
        selectedModelId = m.id;
        localStorage.setItem("hub-model", m.id);
        renderModelList();
        updateQuickLaunchTitle();
        restoreWeightsPath();
        refreshInstances();
        renderWorkspace();
        closeDrawer();
      };
      list.appendChild(card);
    }
  }
}

function updateQuickLaunchTitle() {
  const m = selectedModel();
  $("quick-launch-model").textContent = m ? I18N.pick(m, "displayName") : "";
}

/* ---------- 设置对话框（左侧功能菜单 + 右侧内容面板） ---------- */
const settingsModal = $("settings-modal");
let settingsSection = "general";
let lastCertStatus = null;

function openSettingsModal(section) {
  settingsSection = section || settingsSection || "general";
  activateSettingsSection(settingsSection);
  syncGeneralPane();
  settingsModal.classList.remove("hidden");
  loadExecutables();
}
function closeSettingsModal() {
  settingsModal.classList.add("hidden");
}
function activateSettingsSection(section) {
  settingsSection = section;
  document.querySelectorAll(".settings-nav-item").forEach(b =>
    b.classList.toggle("active", b.dataset.section === section));
  document.querySelectorAll(".settings-pane").forEach(p => p.classList.add("hidden"));
  $("settings-pane-" + section).classList.remove("hidden");
  if (section === "https") loadCertStatus();
  if (section === "executables") resetExecForm();
}
document.querySelectorAll(".settings-nav-item").forEach(btn => {
  btn.onclick = () => activateSettingsSection(btn.dataset.section);
});
$("settings-btn").onclick = () => openSettingsModal("general");
$("exec-goto-btn").onclick = () => openSettingsModal("executables");
$("settings-modal-close").onclick = closeSettingsModal;
settingsModal.onclick = (e) => { if (e.target === settingsModal) closeSettingsModal(); };

/* 通用面板：界面语言 / 主题（与页头开关同一状态源） */
function syncGeneralPane() {
  $("ui-language").value = I18N.lang();
  $("ui-theme").value = document.documentElement.dataset.theme;
}
$("ui-language").onchange = (e) => I18N.setLang(e.target.value);
$("ui-theme").onchange = (e) => {
  const next = e.target.value === "light" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  localStorage.setItem("hub-theme", next);
  applyThemeIcon();
  window.dispatchEvent(new Event("themechange"));
};

/* ---------- HTTPS 证书面板 ---------- */
async function loadCertStatus() {
  try {
    const res = await fetch("/api/cert/status");
    const json = await res.json();
    if (json && json.data) renderCertStatus(json.data);
  } catch (e) { /* 状态拉取失败不影响面板其他操作 */ }
}

function renderCertStatus(data) {
  lastCertStatus = data;
  $("https-enabled").checked = !!data.enabled;
  const badge = $("https-status-badge");
  if (data.exists) {
    badge.textContent = t("https.certOk");
    badge.className = "badge ready";
  } else {
    badge.textContent = t("https.certMissing");
    badge.className = "badge stopped";
  }
  $("https-status-text").textContent = t("https.statusLine", {
    path: data.path,
    ca: data.caCertExists ? t("https.caExists") : t("https.caMissing")
  });
}

$("https-enabled").onchange = async (e) => {
  const enabled = e.target.checked;
  try {
    const res = await fetch("/api/https/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled })
    });
    const text = await res.text();
    if (!res.ok) throw new Error(I18N.errText(text));
    renderCertStatus(JSON.parse(text).data);
    showToast("info", t("https.configSaved"));
  } catch (err) {
    e.target.checked = !enabled;
    showToast("error", t("https.saveFailed") + t("common.colon") + err.message);
  }
};

async function downloadCert(url, fallbackName) {
  try {
    const res = await fetch(url);
    if (!res.ok) {
      const text = await res.text();
      showToast("error", t("https.downloadFailed") + t("common.colon") + I18N.errText(text));
      return;
    }
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    const dispo = res.headers.get("Content-Disposition") || "";
    const m = dispo.match(/filename="([^"]+)"/);
    a.download = m ? m[1] : fallbackName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  } catch (e) {
    showToast("error", t("https.downloadFailed") + t("common.colon") + e.message);
  }
}
$("https-download-ca").onclick = () => downloadCert("/api/cert/download?type=ca", "ca-cert.cer");
$("https-download-keystore").onclick = () => downloadCert("/api/cert/download", "keystore.p12");

$("https-generate-btn").onclick = async () => {
  const msg = $("https-msg");
  const result = $("https-result");
  msg.textContent = "";
  result.textContent = "";
  const body = {
    hostnames: $("https-hostnames").value.split("\n").map(s => s.trim()).filter(Boolean),
    ips: $("https-ips").value.split("\n").map(s => s.trim()).filter(Boolean),
    validity: parseInt($("https-validity").value, 10) || 3650,
    keysize: parseInt($("https-keysize").value, 10) || 2048
  };
  const password = $("https-password").value.trim();
  if (password) body.password = password;
  const btn = $("https-generate-btn");
  btn.disabled = true;
  btn.textContent = t("https.generating");
  const start = showBusy(t("https.busy"));
  try {
    const res = await fetch("/api/cert/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const text = await res.text();
    if (!res.ok) {
      msg.textContent = t("https.generateFailed") + t("common.colon") + I18N.errText(text);
      return;
    }
    const data = JSON.parse(text).data;
    result.textContent = t("https.generateDone", {
      path: data.path, ca: data.caCertPath, password: data.password, expire: data.expireDate
    });
    loadCertStatus();
  } catch (e) {
    msg.textContent = t("https.generateFailed") + t("common.colon") + e.message;
  } finally {
    hideBusy();
    btn.disabled = false;
    btn.textContent = t("https.generate");
  }
};


/* ---------- 启动模型 modal ---------- */
const launchModal = $("launch-modal");
function openLaunchModal() {
  $("launch-msg").textContent = "";
  launchModal.classList.remove("hidden");
  loadProfiles();
}
function closeLaunchModal() {
  launchModal.classList.add("hidden");
}
$("launch-open-btn").onclick = openLaunchModal;
$("launch-modal-close").onclick = closeLaunchModal;
launchModal.onclick = (e) => { if (e.target === launchModal) closeLaunchModal(); };

/* 权重目录：服务器端文件选择器（目录模式） */
$("weights-browse-btn").onclick = async () => {
  const path = await FileBrowser.open({
    mode: "dir",
    title: t("launch.weightsBrowseTitle"),
    startPath: $("launch-weights").value.trim()
  });
  if (path) {
    $("launch-weights").value = path;
    $("launch-weights").dispatchEvent(new Event("input"));
  }
};

/* 权重也可以是单个 GGUF 文件（audio.cpp 支持直接加载 .gguf） */
$("weights-gguf-btn").onclick = async () => {
  const path = await FileBrowser.open({
    mode: "file",
    title: t("launch.weightsGgufBrowseTitle"),
    extensions: [".gguf"],
    startPath: $("launch-weights").value.trim()
  });
  if (path) {
    $("launch-weights").value = path;
    $("launch-weights").dispatchEvent(new Event("input"));
  }
};

/* 可执行文件：服务器端文件选择器（文件模式，默认过滤 .exe） */
$("exec-browse-btn").onclick = async () => {
  const path = await FileBrowser.open({
    mode: "file",
    title: t("launch.execBrowseTitle"),
    extensions: [".exe"],
    defaultAll: true,
    startPath: $("exec-path").value.trim()
  });
  if (path) {
    $("exec-path").value = path;
  }
};

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    closeSettingsModal();
    closeLaunchModal();
    closeDownloadsModal();
    closeModelDlModal();
    closeDrawer();
  }
});

async function loadExecutables() {
  try {
    const res = await fetch("/api/executables");
    executables = await res.json();
  } catch (e) {
    return;
  }
  renderExecList();
  updateLaunchExec();
  // 可执行文件有效性也影响模型卡片的已配置/黯淡状态
  if (models.length) renderModelList();
}

function renderExecList() {
  const list = $("exec-list");
  list.innerHTML = "";
  if (executables.length === 0) {
    list.innerHTML = `<div class="hint exec-empty">${t("exec.empty")}</div>`;
    return;
  }
  for (const ex of executables) {
    const row = document.createElement("div");
    row.className = "exec-row" + (ex.exists ? "" : " missing");
    let html = `<div class="exec-info">
      <div class="exec-name">${ex.name}${ex.exists ? "" : ` <span class="badge error">${t("exec.missing")}</span>`}</div>
      <div class="exec-path">${ex.path}</div>`;
    if (ex.note) {
      html += `<div class="exec-note">${ex.note}</div>`;
    }
    if (ex.env && Object.keys(ex.env).length) {
      html += `<div class="exec-env-line">${t("exec.envSummary", { keys: Object.keys(ex.env).join(", ") })}</div>`;
    }
    html += `</div><button class="stop-btn exec-edit">${t("exec.edit")}</button><button class="stop-btn exec-del">${t("exec.delete")}</button>`;
    row.innerHTML = html;
    row.querySelector(".exec-edit").onclick = () => startEditExec(ex);
    row.querySelector(".exec-del").onclick = async () => {
      await fetch("/api/executables/" + ex.id, { method: "DELETE" });
      if (editingExecId === ex.id) resetExecForm();
      loadExecutables();
    };
    list.appendChild(row);
  }
}

/* 正在编辑的可执行文件 id，null 表示新增模式；表单默认收起，点“新增”/“编辑”才展开 */
let editingExecId = null;

function showExecForm() {
  $("exec-form-section").classList.remove("hidden");
}

function hideExecForm() {
  $("exec-form-section").classList.add("hidden");
  $("exec-msg").textContent = "";
}

function startEditExec(ex) {
  editingExecId = ex.id;
  $("exec-name").value = ex.name || "";
  $("exec-path").value = ex.path || "";
  $("exec-note").value = ex.note || "";
  $("exec-env").value = envToText(ex.env);
  $("exec-msg").textContent = "";
  const title = $("exec-form-title");
  title.dataset.i18n = "exec.editTitle";
  title.textContent = t("exec.editTitle");
  const btn = $("exec-add-btn");
  btn.dataset.i18n = "exec.save";
  btn.textContent = t("exec.save");
  showExecForm();
}

function resetExecForm() {
  editingExecId = null;
  $("exec-name").value = "";
  $("exec-path").value = "";
  $("exec-note").value = "";
  $("exec-env").value = "";
  const title = $("exec-form-title");
  title.dataset.i18n = "exec.addTitle";
  title.textContent = t("exec.addTitle");
  const btn = $("exec-add-btn");
  btn.dataset.i18n = "exec.add";
  btn.textContent = t("exec.add");
  hideExecForm();
}

$("exec-new-btn").onclick = () => {
  resetExecForm();
  showExecForm();
};
$("exec-cancel-edit-btn").onclick = resetExecForm;

/* 解析环境变量输入：每行 KEY=VALUE，空行忽略；格式错误抛带行号的异常 */
function parseEnvText() {
  const env = {};
  const lines = $("exec-env").value.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    const eq = line.indexOf("=");
    if (eq <= 0 || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(line.substring(0, eq).trim())) {
      throw new Error(t("exec.envInvalid", { line: i + 1 }));
    }
    env[line.substring(0, eq).trim()] = line.substring(eq + 1).trim();
  }
  return env;
}

function envToText(env) {
  if (!env) return "";
  return Object.entries(env).map(([k, v]) => k + "=" + v).join("\n");
}

function updateLaunchExec() {
  const sel = $("launch-exec");
  sel.innerHTML = "";
  const empty = executables.length === 0;
  if (empty) {
    // 无可用程序时显示占位项，点击下拉即跳转到设置页添加（见下方 mousedown 处理）
    const opt = document.createElement("option");
    opt.value = "";
    opt.textContent = t("launch.execNone");
    sel.appendChild(opt);
  }
  for (const ex of executables) {
    const opt = document.createElement("option");
    opt.value = ex.id;
    opt.textContent = ex.name + (ex.exists ? "" : t("exec.missingSuffix"));
    sel.appendChild(opt);
  }
  sel.classList.toggle("exec-empty", empty);
  $("launch-btn").disabled = empty;
  $("exec-empty-hint").classList.toggle("hidden", !empty);
}

/* 可执行文件为空时，点击下拉框直接跳转到设置页的可执行文件面板 */
$("launch-exec").addEventListener("mousedown", (e) => {
  if (executables.length === 0) {
    e.preventDefault();
    openSettingsModal("executables");
  }
});

$("exec-add-btn").onclick = async () => {
  const msg = $("exec-msg");
  msg.textContent = "";
  let env;
  try {
    env = parseEnvText();
  } catch (e) {
    msg.textContent = e.message;
    return;
  }
  const body = {
    name: $("exec-name").value.trim(),
    path: $("exec-path").value.trim(),
    note: $("exec-note").value.trim(),
    env
  };
  const editing = editingExecId !== null;
  try {
    const res = await fetch(editing ? "/api/executables/" + editingExecId : "/api/executables", {
      method: editing ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const text = await res.text();
    if (!res.ok) {
      msg.textContent = t(editing ? "exec.saveFailed" : "exec.addFailed") + t("common.colon") + I18N.errText(text);
      return;
    }
    resetExecForm();
    loadExecutables();
  } catch (e) {
    msg.textContent = t(editing ? "exec.saveFailed" : "exec.addFailed") + t("common.colon") + e.message;
  }
};

/* ---------- 快速启动 ---------- */
/* 权重目录路径按模型持久化到 localStorage */
const weightsKey = () => "hub-weights-" + selectedModelId;
function restoreWeightsPath() {
  $("launch-weights").value = localStorage.getItem(weightsKey()) || "";
}
$("launch-weights").addEventListener("input", (e) => {
  if (selectedModelId) localStorage.setItem(weightsKey(), e.target.value.trim());
});

/* 线程数全局持久化 */
$("launch-threads").value = localStorage.getItem("hub-threads") || "";
$("launch-threads").addEventListener("input", (e) => {
  localStorage.setItem("hub-threads", e.target.value.trim());
});

/* ---------- 启动配置（Profile）：持久化到后端 data/profiles.json ---------- */
async function loadProfiles() {
  try {
    const res = await fetch("/api/profiles");
    profiles = await res.json();
  } catch (e) {
    return;
  }
  renderLaunchProfiles();
  // 配置变化会影响模型列表的可用/黯淡展示
  if (models.length) renderModelList();
}

/* 下拉只显示当前模型的配置 */
function renderLaunchProfiles() {
  const sel = $("launch-profile");
  const current = sel.value;
  sel.innerHTML = `<option value="">${t("launch.profileNew")}</option>`;
  for (const p of profiles.filter(p => p.modelId === selectedModelId)) {
    const opt = document.createElement("option");
    opt.value = p.id;
    opt.textContent = p.name;
    sel.appendChild(opt);
  }
  sel.value = [...sel.options].some(o => o.value === current) ? current : "";
  updateProfileButtons();
}

function selectedProfile() {
  return profiles.find(p => p.id === $("launch-profile").value) || null;
}

function updateProfileButtons() {
  $("profile-del-btn").classList.toggle("hidden", !selectedProfile());
}

/* 选中配置 → 回填表单 */
$("launch-profile").onchange = () => {
  const p = selectedProfile();
  if (p) {
    $("launch-weights").value = p.weightsPath || "";
    $("launch-name").value = p.instanceName || "";
    $("launch-backend").value = p.backend || "cpu";
    $("launch-device").value = p.device ?? "";
    $("launch-port").value = p.port ?? "";
    $("launch-threads").value = p.threads ?? "";
    if (p.executableId && [...$("launch-exec").options].some(o => o.value === p.executableId)) {
      $("launch-exec").value = p.executableId;
    }
  }
  updateProfileButtons();
};

/* 从当前表单收集配置字段（与启动请求同源） */
function collectProfileFields(name) {
  const fields = {
    name,
    modelId: selectedModelId,
    weightsPath: $("launch-weights").value.trim(),
    backend: $("launch-backend").value
  };
  const execId = $("launch-exec").value;
  if (execId) fields.executableId = execId;
  const instanceName = $("launch-name").value.trim();
  if (instanceName) fields.instanceName = instanceName;
  const device = $("launch-device").value;
  const port = $("launch-port").value;
  const threads = $("launch-threads").value;
  if (device !== "") fields.device = parseInt(device, 10);
  if (port !== "") fields.port = parseInt(port, 10);
  if (threads !== "") fields.threads = parseInt(threads, 10);
  return fields;
}

async function saveProfile(url, method, fields, failKey) {
  const msg = $("launch-msg");
  msg.textContent = "";
  if (!fields.weightsPath) { msg.textContent = t("launch.weightsRequired"); return false; }
  try {
    const res = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(fields)
    });
    const text = await res.text();
    if (!res.ok) { msg.textContent = t(failKey) + t("common.colon") + I18N.errText(text); return false; }
    return true;
  } catch (e) {
    msg.textContent = t(failKey) + t("common.colon") + e.message;
    return false;
  }
}

$("profile-save-btn").onclick = async () => {
  const name = (window.prompt(t("profile.namePrompt"), "") || "").trim();
  if (!name) return;
  if (await saveProfile("/api/profiles", "POST", collectProfileFields(name), "profile.saveFailed")) {
    await loadProfiles();
    const saved = profiles.find(p => p.modelId === selectedModelId && p.name === name);
    if (saved) {
      $("launch-profile").value = saved.id;
      updateProfileButtons();
    }
    showToast("info", t("profile.saved", { name }));
  }
};

/* 启动模型时顺带动态保存参数：已有配置则原地更新，否则新建“默认”配置 */
async function autoSaveProfile() {
  try {
    const existing = selectedProfile() || profiles.find(p => p.modelId === selectedModelId);
    const fields = collectProfileFields(existing ? existing.name : t("profile.autoName"));
    if (!fields.weightsPath) return;
    await fetch(existing ? "/api/profiles/" + existing.id : "/api/profiles", {
      method: existing ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(fields)
    });
    await loadProfiles();
  } catch (e) { /* 动态保存失败不影响启动结果 */ }
}

$("profile-del-btn").onclick = async () => {
  const p = selectedProfile();
  if (!p || !window.confirm(t("profile.confirmDelete", { name: p.name }))) return;
  try {
    await fetch("/api/profiles/" + p.id, { method: "DELETE" });
    await loadProfiles();
    showToast("info", t("profile.deleted", { name: p.name }));
  } catch (e) {
    showToast("error", t("profile.deleteFailed") + t("common.colon") + e.message);
  }
};

$("launch-btn").onclick = async () => {
  const msg = $("launch-msg");
  msg.textContent = "";
  const body = {
    modelId: selectedModelId,
    weightsPath: $("launch-weights").value.trim(),
    backend: $("launch-backend").value
  };
  const execId = $("launch-exec").value;
  if (execId) body.executableId = execId;
  const name = $("launch-name").value.trim();
  if (name) body.name = name;
  const device = $("launch-device").value;
  const port = $("launch-port").value;
  const threads = $("launch-threads").value;
  if (device !== "") body.device = parseInt(device, 10);
  if (port !== "") body.port = parseInt(port, 10);
  if (threads !== "") body.threads = parseInt(threads, 10);
  try {
    const res = await fetch("/api/instances", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const text = await res.text();
    if (!res.ok) {
      msg.textContent = t("launch.failed") + t("common.colon") + I18N.errText(text);
      return;
    }
    closeLaunchModal();
    showToast("info", t("launch.started"));
    refreshInstances();
    // 启动成功即视为一次使用：动态保存参数，模型随之变为“已配置”
    autoSaveProfile();
  } catch (e) {
    msg.textContent = t("launch.failed") + t("common.colon") + e.message;
  }
};

/* ---------- 实例列表 + 状态条（每 2s 轮询） ---------- */
async function refreshInstances() {
  try {
    const res = await fetch("/api/instances");
    instances = await res.json();
  } catch (e) {
    return;
  }
  renderInstanceList();
  updateInstanceBar();
}

function renderInstanceList() {
  const list = $("instance-list");
  const filtered = instances.filter(i => i.modelId === selectedModelId);
  list.innerHTML = "";
  if (filtered.length === 0) {
    list.innerHTML = `<div class="hint">${t("instance.empty")}</div>`;
    return;
  }
  for (const inst of filtered) {
    const card = document.createElement("div");
    const statusClass = STATUS_CLASS[inst.status] || "stopped";
    card.className = "card" + (inst.id === activeInstanceId ? " selected" : "");
    let html = `<div class="card-title">#${inst.id}${inst.instanceName ? ` <span class="badge">${inst.instanceName}</span>` : ""} <span class="badge ${statusClass}">${statusText(inst.status)}</span></div>
      <div class="card-desc">${inst.backend}${inst.device != null ? ":" + inst.device : ""} ｜ ${t("instance.port")} ${inst.port}${inst.executableName ? " ｜ " + inst.executableName : ""}</div>`;
    if (inst.status === "ERROR" && inst.errorMessage) {
      html += `<div class="error-text">${inst.errorMessage}</div>`;
    }
    if (inst.status !== "STOPPED") {
      html += `<div class="card-actions"><button class="stop-btn">${t("instance.stop")}</button></div>`;
    }
    card.innerHTML = html;
    const stopBtn = card.querySelector(".stop-btn");
    if (stopBtn) {
      stopBtn.onclick = async () => {
        await fetch("/api/instances/" + inst.id, { method: "DELETE" });
        refreshInstances();
      };
    }
    list.appendChild(card);
  }
}

function updateInstanceBar() {
  const ready = instances.filter(i => i.modelId === selectedModelId && i.status === "READY");
  const select = $("instance-select");
  select.innerHTML = "";
  for (const inst of ready) {
    const opt = document.createElement("option");
    opt.value = inst.id;
    opt.textContent = `#${inst.id} ｜ ${inst.backend}${inst.device != null ? ":" + inst.device : ""} ｜ ${t("instance.port")} ${inst.port}`;
    select.appendChild(opt);
  }
  const prevActive = activeInstanceId;
  const has = ready.length > 0;
  if (has) {
    if (!ready.some(i => i.id === activeInstanceId)) {
      activeInstanceId = ready[0].id;
    }
    select.value = activeInstanceId;
  } else {
    activeInstanceId = null;
  }
  // 轮询导致激活实例被动切换（如实例停止）时也刷新历史列表
  if (prevActive !== activeInstanceId) loadHistory();
  select.disabled = !has;
  $("instance-stop").disabled = !has;

  const pill = $("instance-pill");
  pill.textContent = has ? t("instance.ready") : t("instance.noReady");
  pill.className = "pill " + (has ? "ok" : "warn");

  for (const id of SUBMIT_BTNS) {
    const btn = $(id);
    btn.disabled = !has;
    btn.textContent = has ? submitLabel(id) : submitLabel(id) + t("instance.noReadySuffix");
  }
}

$("instance-select").onchange = (e) => {
  activeInstanceId = e.target.value;
  renderInstanceList();
  loadHistory();
};

$("instance-stop").onclick = async () => {
  if (!activeInstanceId) return;
  $("instance-stop").disabled = true;
  await fetch("/api/instances/" + activeInstanceId, { method: "DELETE" });
  refreshInstances();
};

/* ---------- 下载管理（任务列表 + 模型下载弹窗） ---------- */
let downloads = [];
let mdlPackages = null;
let mdlModel = null;

function fmtBytes(n) {
  if (n == null || n < 0) return "?";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = n, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v : v.toFixed(1)) + " " + units[i];
}

const DL_STATUS_CLASS = { RUNNING: "starting", PENDING: "starting", PAUSED: "stopped", DONE: "ready", FAILED: "error" };

async function refreshDownloads() {
  try {
    const res = await fetch("/api/downloads");
    downloads = await res.json();
  } catch (e) {
    return;
  }
  updateDlBadge();
  if (!$("downloads-modal").classList.contains("hidden")) renderDownloadList();
}

/* 页头角标：进行中的任务数 */
function updateDlBadge() {
  const running = downloads.filter(d => d.status === "RUNNING" || d.status === "PENDING").length;
  const badge = $("dl-badge");
  badge.textContent = running;
  badge.classList.toggle("hidden", running === 0);
}

function openDownloadsModal() {
  renderDownloadList();
  $("downloads-modal").classList.remove("hidden");
}
function closeDownloadsModal() {
  $("downloads-modal").classList.add("hidden");
}
$("downloads-btn").onclick = openDownloadsModal;
$("downloads-modal-close").onclick = closeDownloadsModal;
$("downloads-modal").onclick = (e) => { if (e.target === $("downloads-modal")) closeDownloadsModal(); };

function renderDownloadList() {
  const list = $("dl-list");
  list.innerHTML = "";
  if (downloads.length === 0) {
    list.innerHTML = `<div class="hint exec-empty">${t("dl.empty")}</div>`;
    return;
  }
  for (const d of downloads) {
    const model = d.modelId ? models.find(m => m.id === d.modelId) : null;
    const title = model ? I18N.pick(model, "displayName") : d.targetDir;
    const pct = d.percent;
    const row = document.createElement("div");
    row.className = "dl-row";
    let html = `<div class="dl-row-head">
      <span class="dl-row-title">${title} <span class="dl-row-dir">models/${d.targetDir}</span></span>
      <span class="badge ${DL_STATUS_CLASS[d.status] || "stopped"}">${t("dl.status." + d.status)}</span>
    </div>
    <div class="dl-progress"><div class="dl-progress-fill${pct < 0 ? " indeterminate" : ""}" style="width:${pct < 0 ? 100 : pct}%"></div></div>
    <div class="dl-row-meta">${fmtBytes(d.downloadedBytes)} / ${fmtBytes(d.totalBytes)}${pct >= 0 ? ` ｜ ${pct}%` : ""}${d.status === "RUNNING" && d.speedBps > 0 ? ` ｜ ${fmtBytes(d.speedBps)}/s` : ""} ｜ ${t("dl.fileProgress", { done: d.completedFiles, n: d.fileCount })}</div>`;
    if (d.status === "FAILED" && d.error) {
      html += `<div class="error-text">${d.error}</div>`;
    }
    html += `<div class="card-actions">`;
    if (d.status === "RUNNING" || d.status === "PENDING") {
      html += `<button class="stop-btn dl-act" data-act="pause">${t("dl.pause")}</button>`;
    }
    if (d.status === "PAUSED" || d.status === "FAILED") {
      html += `<button class="stop-btn dl-act" data-act="resume">${t(d.status === "FAILED" ? "dl.retry" : "dl.resume")}</button>`;
    }
    if (d.status === "DONE" && d.modelId) {
      html += `<button class="stop-btn dl-fill">${t("dl.fillWeights")}</button>`;
    }
    html += `<button class="stop-btn dl-del">${t("dl.delete")}</button></div>`;
    row.innerHTML = html;
    for (const btn of row.querySelectorAll(".dl-act")) {
      btn.onclick = async () => {
        const res = await fetch(`/api/downloads/${d.id}/${btn.dataset.act}`, { method: "POST" });
        if (!res.ok) showToast("error", I18N.errText(await res.text()));
        refreshDownloads();
      };
    }
    const fillBtn = row.querySelector(".dl-fill");
    if (fillBtn) {
      fillBtn.onclick = () => {
        const path = "models/" + d.targetDir;
        localStorage.setItem("hub-weights-" + d.modelId, path);
        if (selectedModelId === d.modelId) $("launch-weights").value = path;
        showToast("info", t("dl.weightsFilled", { path }));
      };
    }
    row.querySelector(".dl-del").onclick = async () => {
      if (!window.confirm(t("dl.confirmDelete"))) return;
      const res = await fetch(`/api/downloads/${d.id}?purge=true`, { method: "DELETE" });
      if (!res.ok) showToast("error", I18N.errText(await res.text()));
      refreshDownloads();
    };
    list.appendChild(row);
  }
}

/* 模型下载弹窗：包选择 + token + 覆盖 */
function openModelDlModal(m) {
  mdlModel = m;
  mdlPackages = null;
  $("mdl-dl-model").textContent = I18N.pick(m, "displayName");
  $("mdl-msg").textContent = "";
  $("mdl-package-list").innerHTML = `<div class="hint">${t("dl.loading")}</div>`;
  $("model-dl-modal").classList.remove("hidden");
  loadMdlPackages(m);
}
function closeModelDlModal() {
  $("model-dl-modal").classList.add("hidden");
}
$("model-dl-modal-close").onclick = closeModelDlModal;
$("model-dl-modal").onclick = (e) => { if (e.target === $("model-dl-modal")) closeModelDlModal(); };

async function loadMdlPackages(m) {
  try {
    const res = await fetch(`/api/models/${m.id}/packages`);
    const text = await res.text();
    if (!res.ok) throw new Error(I18N.errText(text));
    mdlPackages = JSON.parse(text);
    renderMdlPackages();
  } catch (e) {
    $("mdl-package-list").innerHTML = `<div class="hint">${t("dl.loadFailed")}${t("common.colon")}${e.message}</div>`;
  }
}

function renderMdlPackages() {
  const c = $("mdl-package-list");
  c.innerHTML = "";
  const pkgs = (mdlPackages && mdlPackages.packages) || [];
  if (pkgs.length === 0) {
    c.innerHTML = `<div class="hint">${t("dl.noPackages")}</div>`;
    return;
  }
  pkgs.forEach((p, i) => {
    const row = el(`<label class="dl-package-row">
      <input type="radio" name="mdl-package" value="${p.id}"${p.default || (!pkgs.some(x => x.default) && i === 0) ? " checked" : ""}>
      <span class="dl-package-text">
        <span class="dl-package-name"></span>
        <span class="dl-package-meta">${[p.format, p.precision].filter(Boolean).join(" ｜ ")} → models/${p.targetDir} ｜ ${t("dl.fileCount", { n: p.files.length })}</span>
      </span>
    </label>`);
    const name = row.querySelector(".dl-package-name");
    name.textContent = p.displayName || p.id;
    if (p.default) name.appendChild(el(` <span class="badge ready">${t("dl.recommended")}</span>`));
    if (p.gated) name.appendChild(el(` <span class="badge stopped">gated</span>`));
    c.appendChild(row);
  });
}

$("mdl-start").onclick = async () => {
  const msg = $("mdl-msg");
  msg.textContent = "";
  const sel = document.querySelector('input[name="mdl-package"]:checked');
  if (!sel || !mdlModel) {
    msg.textContent = t("dl.noPackages");
    return;
  }
  const body = {
    modelId: mdlModel.id,
    packageId: sel.value,
    overwrite: $("mdl-overwrite").checked
  };
  const token = $("mdl-token").value.trim();
  if (token) body.token = token;
  const endpoint = $("mdl-endpoint").value;
  if (endpoint) body.endpoint = endpoint;
  const btn = $("mdl-start");
  btn.disabled = true;
  try {
    const res = await fetch("/api/downloads", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const text = await res.text();
    if (!res.ok) {
      msg.textContent = t("dl.startFailed") + t("common.colon") + I18N.errText(text);
      return;
    }
    closeModelDlModal();
    showToast("info", t("dl.started"));
    await refreshDownloads();
    openDownloadsModal();
  } catch (e) {
    msg.textContent = t("dl.startFailed") + t("common.colon") + e.message;
  } finally {
    btn.disabled = false;
  }
};

/* ---------- 事件通知（toast） ---------- */
let eventsInitialized = false;
const seenEvents = new Set();

async function refreshEvents() {
  let events;
  try {
    const res = await fetch("/api/events");
    events = await res.json();
  } catch (e) {
    return;
  }
  const fresh = [];
  for (const ev of events) {
    const key = ev.time + "|" + ev.message;
    if (!seenEvents.has(key)) {
      seenEvents.add(key);
      fresh.push(ev);
    }
  }
  if (!eventsInitialized) {
    eventsInitialized = true;
    return;
  }
  fresh.reverse().forEach(ev => showToast(ev.level, ev.message));
}

function showToast(level, message) {
  const root = $("toast-root");
  const node = el(`<div class="toast ${level === "error" ? "error" : "info"}">
    <span class="toast-text"></span><button class="toast-close">×</button></div>`);
  node.querySelector(".toast-text").textContent = message;
  node.querySelector(".toast-close").onclick = () => node.remove();
  root.appendChild(node);
  setTimeout(() => node.remove(), 8000);
}

/* ---------- 通用运行 ---------- */
async function runTask(requestObj) {
  const res = await fetch("/api/run/" + activeInstanceId, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ request: requestObj })
  });
  const text = await res.text();
  if (!res.ok) throw new Error(I18N.errText(text));
  return JSON.parse(text);
}

/* ---------- 任务等待遮罩（spinner + 实时计时） ---------- */
let busyTimer = null;
function showBusy(label) {
  $("busy-label").textContent = label;
  const start = performance.now();
  $("busy-elapsed").textContent = "0.0s";
  $("busy-overlay").classList.remove("hidden");
  busyTimer = setInterval(() => {
    $("busy-elapsed").textContent = ((performance.now() - start) / 1000).toFixed(1) + "s";
  }, 100);
  return start;
}
function hideBusy() {
  clearInterval(busyTimer);
  busyTimer = null;
  $("busy-overlay").classList.add("hidden");
}
function fmtElapsed(start) {
  return t("common.elapsedSec", { s: ((performance.now() - start) / 1000).toFixed(1) });
}

function b64ToBlob(b64, mime) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: mime });
}

function makeTrackRow(name, b64) {
  const url = URL.createObjectURL(b64ToBlob(b64, "audio/wav"));
  const row = el(`<div class="track-row">
    <span class="track-name"></span>
    <audio controls preload="auto"></audio>
    <a class="btn-ghost"></a>
  </div>`);
  row.dataset.blobUrl = url;
  row.querySelector(".track-name").textContent = name;
  row.querySelector("audio").src = url;
  const a = row.querySelector("a");
  a.textContent = t("common.download");
  a.href = url;
  a.download = name + ".wav";
  return row;
}

/* 清空结果容器前回收其中 track-row 的 objectURL，避免内存泄漏 */
function clearResult(container) {
  for (const row of container.querySelectorAll(".track-row[data-blob-url]")) {
    URL.revokeObjectURL(row.dataset.blobUrl);
  }
  container.innerHTML = "";
}

/* ---------- 工作区：按 category 切换面板 ---------- */
function renderWorkspace() {
  const m = selectedModel();
  if (!m) return;
  for (const cat of ["tts", "asr", "sep", "other"]) {
    $("panel-" + cat).classList.toggle("hidden", cat !== m.category);
  }
  if (m.category === "tts") renderTtsPanel(m);
  else if (m.category === "asr") renderAsrPanel(m);
  else if (m.category === "sep") renderSepPanel(m);
  else renderOtherPanel(m);
}

/* ---------- 参数渲染辅助 ---------- */
function buildLanguageRow(container, m, prefix) {
  container.innerHTML = "";
  if (!m.language) return null;
  const locked = m.language.values.length <= 1;
  const label = el(`<label>${t("common.language")}<select id="${prefix}-language" ${locked ? "disabled" : ""}></select></label>`);
  const sel = label.querySelector("select");
  for (const v of m.language.values) {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = v;
    sel.appendChild(opt);
  }
  sel.value = m.language.default || m.language.values[0];
  container.appendChild(label);
  if (locked) {
    container.appendChild(el(`<div class="hint">${t("common.langLocked", { lang: sel.value })}</div>`));
  }
  return sel;
}

function schemaParams(m) {
  const s = m.paramSchema || {};
  return Object.entries(s).filter(([k, v]) =>
    v && typeof v === "object" && !Array.isArray(v) && v.type && !RESERVED_KEYS.has(k));
}

function paramInput(key, p, prefix) {
  if (p.type === "boolean") {
    return el(`<label class="checkbox-label"><input type="checkbox" id="${prefix}-${key}" ${p.default ? "checked" : ""}> ${key}</label>`);
  }
  if (p.type === "string") {
    const ph = I18N.pick(p, "placeholder");
    return el(`<label>${key}<input type="text" id="${prefix}-${key}" value="${p.default ?? ""}"${ph ? ` placeholder="${ph}"` : ""}></label>`);
  }
  if (p.type === "enum") {
    const label = el(`<label>${key}<select id="${prefix}-${key}"></select></label>`);
    const sel = label.querySelector("select");
    for (const v of p.values) {
      const opt = document.createElement("option");
      opt.value = v;
      opt.textContent = v;
      sel.appendChild(opt);
    }
    sel.value = p.default ?? p.values[0];
    return label;
  }
  const step = p.step ?? (p.type === "integer" ? 1 : 0.05);
  const min = p.min != null ? `min="${p.min}"` : "";
  const max = p.max != null ? `max="${p.max}"` : "";
  const val = p.default != null ? p.default : "";
  return el(`<label>${key}<input type="number" id="${prefix}-${key}" value="${val}" ${min} ${max} step="${step}"></label>`);
}

function renderAdvancedGrid(container, m, prefix) {
  container.innerHTML = "";
  for (const [key, p] of schemaParams(m)) {
    container.appendChild(paramInput(key, p, prefix));
  }
  return container.children.length > 0;
}

function collectParams(m, prefix, req) {
  for (const [key, p] of schemaParams(m)) {
    const input = $(`${prefix}-${key}`);
    if (!input) continue;
    // 放进 options 嵌套对象：服务端 /v1/tasks/run 对 options 全量透传，
    // 顶层字段只认白名单（emotion_*、interval_silence_ms 等会被静默丢弃）
    const opts = req.options || (req.options = {});
    if (p.type === "boolean") { opts[key] = input.checked; continue; }
    const v = String(input.value).trim();
    if (v === "") continue;
    opts[key] = p.type === "string" ? v : (p.type === "integer" ? parseInt(v, 10) : parseFloat(v));
  }
}

function collectEnums(m, prefix, req, exclude) {
  const s = m.paramSchema || {};
  for (const [key, p] of Object.entries(s)) {
    if (!p || Array.isArray(p) || p.type !== "enum" || exclude.includes(key)) continue;
    const sel = $(`${prefix}-${key}`);
    if (sel) req[key] = sel.value;
  }
}

function renderEnumRow(container, m, prefix, exclude) {
  container.innerHTML = "";
  const s = m.paramSchema || {};
  for (const [key, p] of Object.entries(s)) {
    if (!p || Array.isArray(p) || p.type !== "enum" || exclude.includes(key)) continue;
    container.appendChild(paramInput(key, p, prefix));
  }
}

function buildTextRow(container, m, key, labelText, id) {
  container.innerHTML = "";
  const p = m.paramSchema && m.paramSchema[key];
  if (p && !Array.isArray(p)) {
    container.appendChild(el(`<label>${labelText}<input type="text" id="${id}" placeholder="${t("common.optional")}"></label>`));
    return true;
  }
  return false;
}

/* ---------- VibeVoice 多说话人块 ---------- */
/* 行号即 Speaker 编号：每个说话人一个元素，内含台词框（每行一句）+ 音色选择器。
   提交时按行号轮流把各说话人的台词拼成 Speaker N: 脚本，音色按行序拼成 voice_samples。 */
function clearSpeakerRows() {
  for (const sp of speakerPickers) {
    const idx = (window.__audioPickers || []).indexOf(sp.picker);
    if (idx >= 0) window.__audioPickers.splice(idx, 1);
    sp.row.remove();
  }
  speakerPickers = [];
}

function renumberSpeakerRows() {
  speakerPickers.forEach((sp, i) => {
    const n = i + 1;
    sp.label.textContent = "Speaker " + n;
    sp.picker.titleKey = "Speaker " + n;
    sp.picker.$(".picker-title").textContent = "Speaker " + n;
  });
  $("tts-speaker-add").disabled = speakerPickers.length >= VIBEVOICE_MAX_SPEAKERS;
}

function addSpeakerRow(path) {
  if (speakerPickers.length >= VIBEVOICE_MAX_SPEAKERS) return;
  const n = speakerPickers.length + 1;
  const row = el(`<details class="speaker-row"${n === 1 ? " open" : ""}>
    <summary><span class="speaker-label">Speaker ${n}</span>
      <span class="speaker-actions">
        <button type="button" class="btn-ghost speaker-remove"></button>
      </span>
    </summary>
    <textarea class="speaker-lines" rows="2"></textarea>
    <div class="speaker-picker-mount"></div>
  </details>`);
  const removeBtn = row.querySelector(".speaker-remove");
  const linesTa = row.querySelector(".speaker-lines");
  removeBtn.textContent = t("tts.speakerRemove");
  linesTa.placeholder = t("tts.speakerLinesPlaceholder");
  removeBtn.onclick = () => {
    const i = speakerPickers.findIndex(sp => sp.row === row);
    if (i < 0) return;
    const idx = (window.__audioPickers || []).indexOf(speakerPickers[i].picker);
    if (idx >= 0) window.__audioPickers.splice(idx, 1);
    speakerPickers.splice(i, 1);
    row.remove();
    if (!speakerPickers.length) addSpeakerRow();
    renumberSpeakerRows();
  };
  // summary 里的按钮不应触发 details 折叠
  row.querySelector(".speaker-actions").onclick = (e) => e.stopPropagation();
  row.querySelector(".speaker-actions").addEventListener("click", (e) => e.preventDefault());
  const picker = new AudioPicker(row.querySelector(".speaker-picker-mount"), "Speaker " + n);
  $("tts-speakers-list").appendChild(row);
  speakerPickers.push({ picker, row, label: row.querySelector(".speaker-label"), removeBtn, linesTa });
  renumberSpeakerRows();
  if (path) setPickerPath(picker, path);
}

function renderSpeakersBlock(m) {
  const show = m.family === "vibevoice";
  $("tts-speakers-block").classList.toggle("hidden", !show);
  // VibeVoice 的脚本由各说话人行内的台词框组装，主文本框不使用
  $("tts-text-block").classList.toggle("hidden", show);
  // 语言切换等重渲染会重建本区块：先快照已填的音色与台词，重建后还原
  const saved = speakerPickers.map(sp => ({ path: sp.picker.getValue(), lines: sp.linesTa.value }));
  clearSpeakerRows();
  if (!show) return;
  if (!saved.length) { addSpeakerRow(); return; }
  for (const s of saved.slice(0, VIBEVOICE_MAX_SPEAKERS)) {
    addSpeakerRow(s.path);
    speakerPickers[speakerPickers.length - 1].linesTa.value = s.lines;
  }
}

$("tts-speaker-add").onclick = () => addSpeakerRow();

/* 各说话人台词按行号轮流拼接：所有人的第 1 句 → 第 2 句 → …，空行跳过 */
function buildVibeVoiceScript() {
  const per = speakerPickers.map(sp => sp.linesTa.value.split("\n").map(s => s.trim()).filter(Boolean));
  const maxLen = Math.max(0, ...per.map(a => a.length));
  const out = [];
  for (let k = 0; k < maxLen; k++) {
    for (let i = 0; i < per.length; i++) {
      if (per[i][k]) out.push("Speaker " + (i + 1) + ": " + per[i][k]);
    }
  }
  return out.join("\n");
}

/* ---------- TTS 面板 ---------- */
function renderTtsPanel(m) {
  $("tts-title").textContent = t("tts.title") + " — " + I18N.pick(m, "displayName");
  ttsLanguageSel = buildLanguageRow($("tts-language-row"), m, "tts");

  // qwen3_tts 变体选择
  const variantRow = $("tts-variant-row");
  variantRow.innerHTML = "";
  if (m.family === "qwen3_tts") {
    const label = el(`<label>${t("tts.variant")}<select id="tts-variant">
      <option value="base">${t("tts.variant.base")}</option>
      <option value="custom_voice">${t("tts.variant.custom_voice")}</option>
      <option value="voice_design">${t("tts.variant.voice_design")}</option>
    </select></label>`);
    variantRow.appendChild(label);
    label.querySelector("select").onchange = (e) => {
      ttsVariant = e.target.value;
      updateTtsBlocks(m);
    };
    ttsVariant = "base";
  }

  // qwen3_tts CustomVoice 的 speaker 下拉
  const speakerRow = $("tts-speaker-row");
  speakerRow.innerHTML = "";
  if (m.family === "qwen3_tts" && m.paramSchema && m.paramSchema.speaker) {
    speakerRow.appendChild(paramInput("speaker", m.paramSchema.speaker, "tts-spk"));
  }

  buildTextRow($("tts-instruct-row"), m, "instruct", t("tts.instructLabel"), "tts-instruct");
  buildTextRow($("tts-ref-text-row"), m, "reference_text", t("tts.refTextLabel"), "tts-reference-text");
  // OmniVoice 原生克隆要求 reference_text，占位提示不能写"可选"
  const rtInput = $("tts-reference-text");
  if (rtInput && m.family === "omnivoice") {
    rtInput.placeholder = t("tts.refTextPlaceholderRequired");
  }
  renderEnumRow($("tts-enum-row"), m, "tts-enum", ["language", "speaker"]);

  $("tts-emotion-block").classList.toggle("hidden", !(m.paramSchema && m.paramSchema.emotionModes));

  renderSpeakersBlock(m);

  const hasAdvanced = renderAdvancedGrid($("tts-advanced-grid"), m, "adv");
  $("tts-advanced").classList.toggle("hidden", !hasAdvanced);

  updateTtsBlocks(m);
  $("tts-text").placeholder = t("tts.textPlaceholder");
  $("tts-result").classList.add("hidden");
  $("tts-msg").textContent = "";
  $("tts-stats").textContent = "";
  loadHistory();
}

function updateTtsBlocks(m) {
  const isQwen = m.family === "qwen3_tts";
  const voiceRefMode = m.inputs && m.inputs.voiceRef;
  let showVoice = voiceRefMode && voiceRefMode !== "none";
  if (isQwen) showVoice = ttsVariant === "base";
  $("tts-voice-block").classList.toggle("hidden", !showVoice);
  $("tts-speaker-row").classList.toggle("hidden", !(isQwen && ttsVariant === "custom_voice"));
  if (isQwen) {
    // instruct：Base 不读；reference_text：仅 Base 克隆用（参考音频的转写）
    $("tts-instruct-row").classList.toggle("hidden", ttsVariant === "base");
    $("tts-ref-text-row").classList.toggle("hidden", ttsVariant !== "base");
    // instruct 在 VoiceDesign 下必填，CustomVoice 下可选
    const insInput = $("tts-instruct");
    if (insInput) {
      insInput.placeholder = ttsVariant === "voice_design" ? t("tts.instructPlaceholderRequired") : t("tts.instructPlaceholderOptional");
    }
  }
}

$("tts-submit").onclick = async () => {
  const m = selectedModel();
  const msg = $("tts-msg");
  const btn = $("tts-submit");
  msg.textContent = "";
  $("tts-result").classList.add("hidden");
  if (!activeInstanceId) { msg.textContent = t("instance.noReady"); return; }

  const req = {};
  // VibeVoice 的脚本由各说话人台词框组装；其它模型用主文本框
  req.text = m.family === "vibevoice" ? buildVibeVoiceScript() : $("tts-text").value;
  if (!req.text.trim()) { msg.textContent = t("tts.errNoText"); return; }
  if (ttsLanguageSel && ttsLanguageSel.value) req.language = ttsLanguageSel.value;

  // 声音来源
  if (m.family === "qwen3_tts") {
    if (ttsVariant === "base") {
      const v = voicePicker.getValue();
      if (!v) { msg.textContent = t("tts.errNoVoice"); return; }
      req.voice_ref = v;
      const rt = $("tts-reference-text");
      if (rt && rt.value.trim()) req.reference_text = rt.value.trim();
    } else if (ttsVariant === "custom_voice") {
      req.speaker = $("tts-spk-speaker").value;
      const ins = $("tts-instruct");
      if (ins && ins.value.trim()) req.instruct = ins.value.trim();
    } else {
      const ins = $("tts-instruct");
      if (!ins || !ins.value.trim()) { msg.textContent = t("tts.errNoInstruct"); return; }
      req.instruct = ins.value.trim();
    }
  } else {
    const voiceRefMode = m.inputs && m.inputs.voiceRef;
    if (voiceRefMode && voiceRefMode !== "none") {
      const v = voicePicker.getValue();
      if (voiceRefMode === "required" && !v) { msg.textContent = t("tts.errNoVoice"); return; }
      if (v) req.voice_ref = v;
    }
    // VibeVoice 多说话人：音色按行序拼成 voice_samples，中间不能有空洞
    // （引擎按下标映射 Speaker 编号，空洞会导致映射错位）
    if (m.family === "vibevoice") {
      const vals = speakerPickers.map(sp => sp.picker.getValue());
      let last = -1;
      vals.forEach((v, i) => { if (v) last = i; });
      if (last >= 0) {
        for (let i = 0; i <= last; i++) {
          if (!vals[i]) { msg.textContent = t("tts.errVibevoiceGap", { n: i + 1 }); return; }
        }
        // 脚本引用的最大 Speaker 编号（引擎按最小编号归一化：min>0 时整体减 1）
        let minId = Infinity, maxId = 0;
        for (const mm of req.text.matchAll(/^Speaker\s+(\d+)\s*:/gim)) {
          const id = parseInt(mm[1], 10);
          if (id < minId) minId = id;
          if (id > maxId) maxId = id;
        }
        const need = maxId > 0 ? (minId > 0 ? maxId : maxId + 1) : 0;
        if (need > last + 1) { msg.textContent = t("tts.errVibevoiceNeedVoices", { n: need }); return; }
        const opts = req.options || (req.options = {});
        opts.voice_samples = vals.slice(0, last + 1).join(",");
      }
    }
    const rt = $("tts-reference-text");
    if (rt && rt.value.trim()) req.reference_text = rt.value.trim();
    // OmniVoice 原生克隆：提供了参考音频就必须给出参考文本（引擎侧硬约束）
    if (m.family === "omnivoice" && req.voice_ref && !req.reference_text) {
      msg.textContent = t("tts.errOmnivoiceRef");
      return;
    }
    const ins = $("tts-instruct");
    if (ins && ins.value.trim()) req.instruct = ins.value.trim();
  }

  collectEnums(m, "tts-enum", req, ["language", "speaker"]);

  // 情感控制（仅 index_tts2；除情感参考音频走顶层 audio 外，其余通过 options 透传给引擎）
  if (m.paramSchema && m.paramSchema.emotionModes) {
    const opts = req.options || (req.options = {});
    if (emotionMode === "emotion_audio") {
      const v = emotionPicker.getValue();
      if (!v) { msg.textContent = t("tts.errNoEmotionAudio"); return; }
      req.audio = v;
    } else if (emotionMode === "emotion_vector") {
      opts.emotion_vector = emotionVector.slice();
      opts.use_random_emotion = false;
    } else if (emotionMode === "emotion_text") {
      opts.use_emotion_text = true;
      const emoText = $("tts-emotion-text").value.trim();
      if (emoText) opts.emotion_text = emoText;
    }
    if (emotionMode !== "none") {
      opts.emotion_alpha = parseFloat($("tts-emotion-alpha").value);
    }
  }

  collectParams(m, "adv", req);

  btn.disabled = true;
  btn.textContent = t("tts.running");
  const stats = $("tts-stats");
  stats.textContent = "";
  const start = showBusy(t("tts.busy"));
  try {
    const json = await runTask(req);
    if (!json.audio) {
      msg.textContent = t("tts.noAudio") + JSON.stringify(json).substring(0, 300);
      return;
    }
    // 替换前回收上一次的 objectURL，避免每次合成泄漏一个 Blob
    if (ttsAudioUrl) URL.revokeObjectURL(ttsAudioUrl);
    const url = URL.createObjectURL(b64ToBlob(json.audio, "audio/wav"));
    ttsAudioUrl = url;
    $("tts-player").src = url;
    const download = $("tts-download");
    download.href = url;
    $("tts-result").classList.remove("hidden");
    stats.textContent = t("common.doneElapsed", { verb: t("tts.verb"), t: fmtElapsed(start) });
    loadHistory();
  } catch (e) {
    msg.textContent = t("common.failedElapsed", { verb: t("tts.verb"), t: fmtElapsed(start), msg: e.message });
  } finally {
    hideBusy();
    btn.disabled = false;
    btn.textContent = submitLabel("tts-submit");
  }
};

/* ---------- 情感模式 Tab（index_tts2） ---------- */
document.querySelectorAll("#tts-emotion-block .tab").forEach(tab => {
  tab.onclick = () => {
    emotionMode = tab.dataset.mode;
    document.querySelectorAll("#tts-emotion-block .tab").forEach(tb => tb.classList.toggle("active", tb === tab));
    document.querySelectorAll("#tts-emotion-block .emotion-pane").forEach(p => p.classList.add("hidden"));
    $("emotion-pane-" + emotionMode).classList.remove("hidden");
    $("emotion-alpha-row").classList.toggle("hidden", emotionMode === "none");
  };
});

function buildEmotionSliders() {
  const container = $("emotion-sliders");
  container.innerHTML = "";
  t("emotion.labels").forEach((label, i) => {
    const row = document.createElement("div");
    row.className = "slider-row";
    row.innerHTML = `<span class="slider-label">${label}</span>
      <input type="range" min="0" max="1" step="0.05" value="0" data-idx="${i}">
      <span class="slider-val" id="emotion-val-${i}">0.00</span>`;
    container.appendChild(row);
  });
  container.oninput = (e) => {
    const idx = parseInt(e.target.dataset.idx, 10);
    emotionVector[idx] = parseFloat(e.target.value);
    $("emotion-val-" + idx).textContent = emotionVector[idx].toFixed(2);
  };
}

$("tts-emotion-alpha").addEventListener("input", (e) => {
  $("emotion-alpha-val").textContent = parseFloat(e.target.value).toFixed(2);
});

/* ---------- TTS 操作历史 ---------- */
/* 历史按 modelId 维度记录，仅当前选中模型 category=="tts" 时显示该区块。
   加载时机：TTS 面板渲染（renderTtsPanel）、激活实例变化（见 updateInstanceBar /
   instance-select onchange）、合成成功（tts-submit 成功分支）、手动刷新。 */
function historyModelId() {
  const m = selectedModel();
  return m && m.category === "tts" ? m.id : null;
}

async function loadHistory() {
  const block = $("history-sidebar");
  const modelId = historyModelId();
  if (!modelId) {
    block.classList.add("hidden");
    $("history-fab").classList.add("hidden");
    closeHistoryDrawer();
    return;
  }
  block.classList.remove("hidden");
  $("history-fab").classList.remove("hidden");
  const list = $("history-list");
  let items;
  try {
    const res = await fetch("/api/history/" + modelId);
    const text = await res.text();
    if (!res.ok) throw new Error(I18N.errText(text));
    items = JSON.parse(text);
  } catch (e) {
    list.innerHTML = "";
    list.appendChild(el(`<div class="hint history-empty">${t("history.listFailed") + t("common.colon") + e.message}</div>`));
    return;
  }
  renderHistoryList(items);
}

function renderHistoryList(items) {
  const list = $("history-list");
  list.innerHTML = "";
  if (!items.length) {
    list.appendChild(el(`<div class="hint history-empty">${t("history.empty")}</div>`));
    return;
  }
  for (const item of items) list.appendChild(makeHistoryRow(item));
}

/* 单行历史：信息行（时间 / 文本预览 / 时长与大小 / 按钮）+ 成功行内播放与下载；失败行红字显示 error */
function makeHistoryRow(item) {
  const audioUrl = "/api/history/" + selectedModelId + "/" + item.taskId + "/audio";
  const row = el(`<div class="history-row${item.ok ? "" : " failed"}">
    <div class="history-info">
      <span class="history-time"></span>
      <span class="history-text"></span>
      <span class="history-meta"></span>
      <span class="history-btns">
        <button type="button" class="history-load"></button>
        <button type="button" class="history-del stop-btn"></button>
      </span>
    </div>
  </div>`);
  const timeEl = row.querySelector(".history-time");
  timeEl.textContent = new Date(item.time).toLocaleString();
  const textEl = row.querySelector(".history-text");
  textEl.textContent = item.text || t("history.noText");
  if (item.text) textEl.title = item.text;
  const meta = [];
  if (item.ok && item.result) {
    if (item.result.durationSec != null) meta.push(item.result.durationSec.toFixed(1) + "s");
    if (item.result.size != null) meta.push(WavUtil.formatSize(item.result.size));
  }
  if (item.instanceName) meta.push(item.instanceName);
  row.querySelector(".history-meta").textContent = meta.join(" ｜ ");
  if (!item.ok) {
    const err = el(`<div class="error-text history-error"></div>`);
    err.textContent = item.error || t("history.failedBadge");
    if (item.error) err.title = item.error;
    row.appendChild(err);
  } else {
    // 懒加载：不预设 src，只有点击“播放”时才向后端拉取 wav 文件
    const player = el(`<div class="history-player">
      <button type="button" class="history-play btn-ghost"></button>
      <audio controls preload="none" class="hidden"></audio>
      <a class="btn-ghost" download></a>
    </div>`);
    const audio = player.querySelector("audio");
    const playBtn = player.querySelector(".history-play");
    playBtn.textContent = t("history.play");
    playBtn.onclick = () => {
      if (!audio.src) {
        audio.src = audioUrl;
        audio.classList.remove("hidden");
      }
      if (audio.paused) audio.play();
      else audio.pause();
    };
    audio.onplay = () => { playBtn.textContent = t("history.pause"); };
    audio.onpause = () => { playBtn.textContent = t("history.play"); };
    audio.onended = () => { playBtn.textContent = t("history.play"); };
    const a = player.querySelector("a");
    a.textContent = t("history.download");
    a.href = audioUrl;
    a.download = "tts-" + item.taskId + ".wav";
    row.appendChild(player);
  }
  const loadBtn = row.querySelector(".history-load");
  loadBtn.textContent = t("history.load");
  loadBtn.onclick = () => loadHistoryRecord(item.taskId);
  const delBtn = row.querySelector(".history-del");
  delBtn.textContent = t("history.delete");
  delBtn.onclick = () => deleteHistoryItem(item.taskId);
  return row;
}

async function deleteHistoryItem(taskId) {
  const modelId = historyModelId();
  if (!modelId) return;
  try {
    const res = await fetch("/api/history/" + modelId + "/" + taskId, { method: "DELETE" });
    if (!res.ok) throw new Error(I18N.errText(await res.text()));
    loadHistory();
  } catch (e) {
    showToast("error", t("history.deleteFailed") + t("common.colon") + e.message);
  }
}

$("history-refresh").onclick = loadHistory;

$("history-clear").onclick = async () => {
  const modelId = historyModelId();
  if (!modelId || !window.confirm(t("history.confirmClear"))) return;
  try {
    const res = await fetch("/api/history/" + modelId, { method: "DELETE" });
    if (!res.ok) throw new Error(I18N.errText(await res.text()));
    loadHistory();
  } catch (e) {
    showToast("error", t("history.clearFailed") + t("common.colon") + e.message);
  }
};

/* "载入"：拉取单条完整记录回填 TTS 表单（提交组装 / collectParams 的逆操作） */
async function loadHistoryRecord(taskId) {
  const modelId = historyModelId();
  const m = selectedModel();
  if (!modelId || !m) return;
  let rec;
  try {
    const res = await fetch("/api/history/" + modelId + "/" + taskId);
    const text = await res.text();
    if (!res.ok) throw new Error(I18N.errText(text));
    rec = JSON.parse(text);
  } catch (e) {
    showToast("error", t("history.loadFailed") + t("common.colon") + e.message);
    return;
  }
  fillTtsForm(m, rec);
  showToast("info", t("history.loaded"));
}

function fillTtsForm(m, rec) {
  $("tts-text").value = rec.text || "";
  if (rec.language && ttsLanguageSel &&
      [...ttsLanguageSel.options].some(o => o.value === rec.language)) {
    ttsLanguageSel.value = rec.language;
  }

  const voice = rec.voice || { kind: "default" };
  if (m.family === "qwen3_tts") {
    // 按声音来源还原变体选择（speaker→CustomVoice，instruct→VoiceDesign，其余→Base）
    const variantSel = $("tts-variant");
    const variant = voice.kind === "speaker" ? "custom_voice"
      : voice.kind === "instruct" ? "voice_design" : "base";
    if (variantSel) {
      variantSel.value = variant;
      ttsVariant = variant;
      updateTtsBlocks(m);
    }
    if (voice.kind === "speaker" && voice.speaker) {
      const spk = $("tts-spk-speaker");
      if (spk) spk.value = voice.speaker;
    }
  }
  if (voice.kind === "voice_ref" && voice.voiceRef) {
    // voice_ref 是服务器路径：AudioPicker 没有对外设值接口，
    // 切到"本地路径"页签填入路径并探测（与手动粘贴路径等价，失败时仅提示不透传）
    setPickerPath(voicePicker, voice.voiceRef);
  }
  const rtInput = $("tts-reference-text");
  if (rtInput) rtInput.value = voice.referenceText || "";
  const insInput = $("tts-instruct");
  if (insInput) insInput.value = voice.instruct || "";

  applyHistoryOptions(m, rec.options || {});

  // VibeVoice：voice_samples 按顺序填回各行音色（本地路径页签 + 探测，与手动粘贴等价），
  // 脚本里的 Speaker N: 行按编号分发回各行台词框（0 起编号上移 1；无 Speaker 行的旧记录整段归入 Speaker 1）
  if (m.family === "vibevoice") {
    const paths = rec.options && rec.options.voice_samples
      ? String(rec.options.voice_samples).split(",").map(s => s.trim()).filter(Boolean)
      : [];
    const lines = [];
    for (const mm of String(rec.text || "").matchAll(/^Speaker\s+(\d+)\s*:\s*(.*)$/gim)) {
      lines.push({ id: parseInt(mm[1], 10), text: mm[2] });
    }
    const minId = lines.length ? Math.min(...lines.map(x => x.id)) : 1;
    const shift = lines.length && minId === 0 ? 1 : 0;
    const maxRow = Math.max(1, paths.length, ...lines.map(x => x.id + shift));
    clearSpeakerRows();
    for (let i = 0; i < maxRow; i++) addSpeakerRow(paths[i]);
    for (const x of lines) {
      const sp = speakerPickers[x.id + shift - 1];
      if (sp) sp.linesTa.value = (sp.linesTa.value ? sp.linesTa.value + "\n" : "") + x.text;
    }
    if (!lines.length && rec.text && speakerPickers[0]) speakerPickers[0].linesTa.value = rec.text;
  }
}

/* options 里的参数填回 paramSchema 渲染的控件：高级参数网格（adv- 前缀）+ 枚举行 */
function applyHistoryOptions(m, options) {
  for (const [key, p] of schemaParams(m)) {
    const input = $("adv-" + key);
    if (!input || options[key] === undefined) continue;
    if (p.type === "boolean") { input.checked = !!options[key]; continue; }
    input.value = String(options[key]);
  }
  const s = m.paramSchema || {};
  for (const [key, p] of Object.entries(s)) {
    if (!p || Array.isArray(p) || p.type !== "enum" || ["language", "speaker"].includes(key)) continue;
    const sel = $(`tts-enum-${key}`);
    if (sel && options[key] !== undefined) sel.value = String(options[key]);
  }
}

/* AudioPicker 无对外设值接口：切到"本地路径"页签，填入服务器路径并探测 */
function setPickerPath(picker, path) {
  const tab = picker.root.querySelector('.picker-tab[data-tab="path"]');
  if (tab) tab.click();
  picker.$(".path-input").value = path;
  picker.probePath();
}

/* ---------- ASR 面板 ---------- */
function renderAsrPanel(m) {
  $("asr-title").textContent = t("asr.title") + " — " + I18N.pick(m, "displayName");
  asrLanguageSel = buildLanguageRow($("asr-language-row"), m, "asr");
  const textRow = $("asr-text-row");
  textRow.innerHTML = "";
  if (m.inputs && m.inputs.text === "optional") {
    textRow.appendChild(el(`<label>${t("asr.contextLabel")}<textarea id="asr-text-input" rows="2" placeholder="${t("asr.contextPlaceholder")}"></textarea></label>`));
  }
  const hasAdvanced = renderAdvancedGrid($("asr-advanced-grid"), m, "asr-adv");
  $("asr-advanced").classList.toggle("hidden", !hasAdvanced);
  $("asr-result").classList.add("hidden");
  $("asr-msg").textContent = "";
  $("asr-stats").textContent = "";
}

$("asr-submit").onclick = async () => {
  const m = selectedModel();
  const msg = $("asr-msg");
  const btn = $("asr-submit");
  msg.textContent = "";
  $("asr-result").classList.add("hidden");
  if (!activeInstanceId) { msg.textContent = t("instance.noReady"); return; }

  const audio = asrAudioPicker.getValue();
  if (!audio) { msg.textContent = t("asr.errNoAudio"); return; }

  const req = { audio };
  if (asrLanguageSel && asrLanguageSel.value) req.language = asrLanguageSel.value;
  const ctxText = $("asr-text-input");
  if (ctxText && ctxText.value.trim()) req.text = ctxText.value.trim();
  collectParams(m, "asr-adv", req);

  btn.disabled = true;
  btn.textContent = t("asr.running");
  const stats = $("asr-stats");
  stats.textContent = "";
  const start = showBusy(t("asr.busy"));
  try {
    const json = await runTask(req);
    $("asr-text").textContent = json.text || t("asr.noText");
    const details = {};
    for (const k of ["language", "words", "segments", "speaker_turns", "timing"]) {
      if (json[k] !== undefined) details[k] = json[k];
    }
    const det = $("asr-json-details");
    if (Object.keys(details).length > 0) {
      $("asr-json").textContent = JSON.stringify(details, null, 2);
      det.classList.remove("hidden");
    } else {
      det.classList.add("hidden");
    }
    $("asr-result").classList.remove("hidden");
    stats.textContent = t("common.doneElapsed", { verb: t("asr.verb"), t: fmtElapsed(start) });
  } catch (e) {
    msg.textContent = t("common.failedElapsed", { verb: t("asr.verb"), t: fmtElapsed(start), msg: e.message });
  } finally {
    hideBusy();
    btn.disabled = false;
    btn.textContent = submitLabel("asr-submit");
  }
};

$("asr-copy").onclick = () => {
  navigator.clipboard.writeText($("asr-text").textContent);
  $("asr-copy").textContent = t("asr.copied");
  setTimeout(() => { $("asr-copy").textContent = t("asr.copy"); }, 1500);
};

/* ---------- SEP 面板 ---------- */
function renderSepPanel(m) {
  $("sep-title").textContent = t("sep.title") + " — " + I18N.pick(m, "displayName");
  clearResult($("sep-result"));
  $("sep-msg").textContent = "";
  $("sep-stats").textContent = "";
}

$("sep-submit").onclick = async () => {
  const msg = $("sep-msg");
  const btn = $("sep-submit");
  msg.textContent = "";
  clearResult($("sep-result"));
  if (!activeInstanceId) { msg.textContent = t("instance.noReady"); return; }

  const audio = sepAudioPicker.getValue();
  if (!audio) { msg.textContent = t("sep.errNoAudio"); return; }

  btn.disabled = true;
  btn.textContent = t("sep.running");
  const stats = $("sep-stats");
  stats.textContent = "";
  const start = showBusy(t("sep.busy"));
  try {
    const json = await runTask({ audio });
    const result = $("sep-result");
    if (json.named_audio_outputs && json.named_audio_outputs.length > 0) {
      for (const track of json.named_audio_outputs) {
        result.appendChild(makeTrackRow(track.id, track.audio));
      }
      stats.textContent = t("common.doneElapsed", { verb: t("sep.verb"), t: fmtElapsed(start) });
    } else if (json.audio) {
      result.appendChild(makeTrackRow("output", json.audio));
      stats.textContent = t("common.doneElapsed", { verb: t("sep.verb"), t: fmtElapsed(start) });
    } else {
      msg.textContent = t("sep.noTracks") + JSON.stringify(json).substring(0, 300);
    }
  } catch (e) {
    msg.textContent = t("common.failedElapsed", { verb: t("sep.verb"), t: fmtElapsed(start), msg: e.message });
  } finally {
    hideBusy();
    btn.disabled = false;
    btn.textContent = submitLabel("sep-submit");
  }
};

/* ---------- OTHER 面板 ---------- */
function renderOtherPanel(m) {
  $("other-title").textContent = I18N.pick(m, "displayName");
  const inputs = m.inputs || { text: "none", audio: "none", voiceRef: "none" };

  const textRow = $("other-text-row");
  textRow.innerHTML = "";
  if (inputs.text !== "none") {
    textRow.appendChild(el(`<label>${t("other.textLabel")}${inputs.text === "required" ? t("common.required") : t("common.optionalSuffix")}<textarea id="other-text-input" rows="3"></textarea></label>`));
  }
  $("other-audio-block").classList.toggle("hidden", inputs.audio === "none");
  $("other-voice-block").classList.toggle("hidden", inputs.voiceRef === "none");
  otherLanguageSel = buildLanguageRow($("other-language-row"), m, "other");

  // paramSchema 全部字段内联渲染
  const fields = $("other-fields");
  fields.innerHTML = "";
  for (const [key, p] of Object.entries(m.paramSchema || {})) {
    if (!p || Array.isArray(p) || !p.type) continue;
    fields.appendChild(paramInput(key, p, "other-field"));
  }

  clearResult($("other-result"));
  $("other-msg").textContent = "";
  $("other-stats").textContent = "";
}

$("other-submit").onclick = async () => {
  const m = selectedModel();
  const msg = $("other-msg");
  const btn = $("other-submit");
  msg.textContent = "";
  clearResult($("other-result"));
  if (!activeInstanceId) { msg.textContent = t("instance.noReady"); return; }
  const inputs = m.inputs || { text: "none", audio: "none", voiceRef: "none" };

  const req = {};
  if (inputs.text !== "none") {
    const txt = $("other-text-input").value.trim();
    if (inputs.text === "required" && !txt) { msg.textContent = t("other.errNoText"); return; }
    if (txt) req.text = txt;
  }
  if (inputs.audio !== "none") {
    const v = otherAudioPicker.getValue();
    if (inputs.audio === "required" && !v) { msg.textContent = t("other.errNoAudio"); return; }
    if (v) req.audio = v;
  }
  if (inputs.voiceRef !== "none") {
    const v = otherVoicePicker.getValue();
    if (inputs.voiceRef === "required" && !v) { msg.textContent = t("other.errNoVoice"); return; }
    if (v) req.voice_ref = v;
  }
  if (otherLanguageSel && otherLanguageSel.value) req.language = otherLanguageSel.value;

  // paramSchema 字段
  for (const [key, p] of Object.entries(m.paramSchema || {})) {
    if (!p || Array.isArray(p) || !p.type) continue;
    const input = $(`other-field-${key}`);
    if (!input) continue;
    if (p.type === "boolean") { req[key] = input.checked; continue; }
    const v = String(input.value).trim();
    if (v === "") continue;
    req[key] = (p.type === "string" || p.type === "enum") ? v
      : (p.type === "integer" ? parseInt(v, 10) : parseFloat(v));
  }

  // 额外参数 JSON 合并
  const extra = $("other-extra").value.trim();
  if (extra) {
    try {
      Object.assign(req, JSON.parse(extra));
    } catch (e) {
      msg.textContent = t("other.errExtraJson", { msg: e.message });
      return;
    }
  }

  btn.disabled = true;
  btn.textContent = t("other.running");
  const stats = $("other-stats");
  stats.textContent = "";
  const start = showBusy(t("other.busy"));
  try {
    const json = await runTask(req);
    const out = $("other-result");
    if (json.named_audio_outputs && json.named_audio_outputs.length > 0) {
      for (const track of json.named_audio_outputs) {
        out.appendChild(makeTrackRow(track.id, track.audio));
      }
    }
    if (json.audio) {
      out.appendChild(makeTrackRow("output", json.audio));
    }
    // JSON 摘要（剔除巨大的 base64 字段）
    const summary = {};
    for (const [k, v] of Object.entries(json)) {
      if (k === "audio") continue;
      if (k === "named_audio_outputs") {
        summary[k] = v.map(tr => ({ id: tr.id, sample_rate: tr.sample_rate, channels: tr.channels }));
        continue;
      }
      summary[k] = v;
    }
    if (Object.keys(summary).length > 0 || (!json.audio && !json.named_audio_outputs)) {
      const pre = el(`<pre class="json-pre"></pre>`);
      pre.textContent = JSON.stringify(summary, null, 2);
      out.appendChild(pre);
    }
    stats.textContent = t("common.doneElapsed", { verb: t("other.verb"), t: fmtElapsed(start) });
  } catch (e) {
    msg.textContent = t("common.failedElapsed", { verb: t("other.verb"), t: fmtElapsed(start), msg: e.message });
  } finally {
    hideBusy();
    btn.disabled = false;
    btn.textContent = submitLabel("other-submit");
  }
};

/* ---------- 初始化 ---------- */
const voicePicker = new AudioPicker($("voice-picker"), "picker.speakerRef");
const emotionPicker = new AudioPicker($("emotion-picker"), "picker.emotionRef");
const asrAudioPicker = new AudioPicker($("asr-audio-picker"), "picker.inputRequired");
const sepAudioPicker = new AudioPicker($("sep-audio-picker"), "picker.inputRequired");
const otherAudioPicker = new AudioPicker($("other-audio-picker"), "picker.input");
const otherVoicePicker = new AudioPicker($("other-voice-picker"), "picker.voiceRef");

I18N.onChange(rerenderAll);
I18N.applyI18n();
applyLangBtn();
buildEmotionSliders();
loadModels();
loadExecutables();
loadProfiles();
refreshInstances();
refreshEvents();
refreshDownloads();
setInterval(() => {
  refreshInstances();
  refreshEvents();
  refreshDownloads();
}, 2000);
