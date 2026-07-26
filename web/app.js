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
  (window.__audioPickers || []).forEach(p => p.refreshLabels && p.refreshLabels());
  if (window.FileBrowser && FileBrowser.relocalize) FileBrowser.relocalize();
}

/* ---------- 模型列表（按 category 分组） ---------- */
async function loadModels() {
  const res = await fetch("/api/models");
  models = await res.json();
  if (models.length && !selectedModelId) {
    selectedModelId = models[0].id;
  }
  renderModelList();
  updateQuickLaunchTitle();
  restoreWeightsPath();
  renderWorkspace();
}

function renderModelList() {
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
      const card = document.createElement("div");
      card.className = "card" + (m.id === selectedModelId ? " selected" : "");
      card.innerHTML = `<div class="card-title">${I18N.pick(m, "displayName")}</div>
        <div class="card-family">${m.family} <span class="cat-badge cat-${cat}">${categoryName(cat)}</span></div>
        <div class="card-desc">${I18N.pick(m, "description")}</div>`;
      card.onclick = () => {
        selectedModelId = m.id;
        renderModelList();
        updateQuickLaunchTitle();
        restoreWeightsPath();
        refreshInstances();
        renderWorkspace();
      };
      list.appendChild(card);
    }
  }
}

function updateQuickLaunchTitle() {
  const m = selectedModel();
  $("quick-launch-model").textContent = m ? I18N.pick(m, "displayName") : "";
}

/* ---------- 可执行文件（设置 modal） ---------- */
const execModal = $("exec-modal");
function openExecModal() {
  execModal.classList.remove("hidden");
  loadExecutables();
}
function closeExecModal() {
  execModal.classList.add("hidden");
}
$("settings-btn").onclick = openExecModal;
$("exec-goto-btn").onclick = openExecModal;
$("exec-modal-close").onclick = closeExecModal;
execModal.onclick = (e) => { if (e.target === execModal) closeExecModal(); };

/* ---------- 创建服务 modal ---------- */
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
    closeExecModal();
    closeLaunchModal();
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
    html += `</div><button class="stop-btn exec-del">${t("exec.delete")}</button>`;
    row.innerHTML = html;
    row.querySelector(".exec-del").onclick = async () => {
      await fetch("/api/executables/" + ex.id, { method: "DELETE" });
      loadExecutables();
    };
    list.appendChild(row);
  }
}

function updateLaunchExec() {
  const sel = $("launch-exec");
  sel.innerHTML = "";
  for (const ex of executables) {
    const opt = document.createElement("option");
    opt.value = ex.id;
    opt.textContent = ex.name + (ex.exists ? "" : t("exec.missingSuffix"));
    sel.appendChild(opt);
  }
  const empty = executables.length === 0;
  $("launch-btn").disabled = empty;
  $("exec-empty-hint").classList.toggle("hidden", !empty);
}

$("exec-add-btn").onclick = async () => {
  const msg = $("exec-msg");
  msg.textContent = "";
  const body = {
    name: $("exec-name").value.trim(),
    path: $("exec-path").value.trim(),
    note: $("exec-note").value.trim()
  };
  try {
    const res = await fetch("/api/executables", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const text = await res.text();
    if (!res.ok) {
      msg.textContent = t("exec.addFailed") + t("common.colon") + I18N.errText(text);
      return;
    }
    $("exec-name").value = "";
    $("exec-path").value = "";
    $("exec-note").value = "";
    loadExecutables();
  } catch (e) {
    msg.textContent = t("exec.addFailed") + t("common.colon") + e.message;
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
  const has = !!selectedProfile();
  $("profile-update-btn").classList.toggle("hidden", !has);
  $("profile-del-btn").classList.toggle("hidden", !has);
}

/* 选中配置 → 回填表单 */
$("launch-profile").onchange = () => {
  const p = selectedProfile();
  if (p) {
    $("launch-weights").value = p.weightsPath || "";
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

$("profile-update-btn").onclick = async () => {
  const p = selectedProfile();
  if (!p) return;
  if (await saveProfile("/api/profiles/" + p.id, "PUT", collectProfileFields(p.name), "profile.updateFailed")) {
    await loadProfiles();
    $("launch-profile").value = p.id;
    updateProfileButtons();
    showToast("info", t("profile.updated", { name: p.name }));
  }
};

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
    let html = `<div class="card-title">#${inst.id} <span class="badge ${statusClass}">${statusText(inst.status)}</span></div>
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
  const has = ready.length > 0;
  if (has) {
    if (!ready.some(i => i.id === activeInstanceId)) {
      activeInstanceId = ready[0].id;
    }
    select.value = activeInstanceId;
  } else {
    activeInstanceId = null;
  }
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
};

$("instance-stop").onclick = async () => {
  if (!activeInstanceId) return;
  $("instance-stop").disabled = true;
  await fetch("/api/instances/" + activeInstanceId, { method: "DELETE" });
  refreshInstances();
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
    <audio controls></audio>
    <a class="btn-ghost"></a>
  </div>`);
  row.querySelector(".track-name").textContent = name;
  row.querySelector("audio").src = url;
  const a = row.querySelector("a");
  a.textContent = t("common.download");
  a.href = url;
  a.download = name + ".wav";
  return row;
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
    return el(`<label>${key}<input type="text" id="${prefix}-${key}" value="${p.default ?? ""}"></label>`);
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
    container.appendChild(paramInput(key, p, `${prefix}-${key}`));
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

  const hasAdvanced = renderAdvancedGrid($("tts-advanced-grid"), m, "adv");
  $("tts-advanced").classList.toggle("hidden", !hasAdvanced);

  updateTtsBlocks(m);
  $("tts-result").classList.add("hidden");
  $("tts-msg").textContent = "";
  $("tts-stats").textContent = "";
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
  req.text = $("tts-text").value;
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
    const url = URL.createObjectURL(b64ToBlob(json.audio, "audio/wav"));
    $("tts-player").src = url;
    const download = $("tts-download");
    download.href = url;
    $("tts-result").classList.remove("hidden");
    stats.textContent = t("common.doneElapsed", { verb: t("tts.verb"), t: fmtElapsed(start) });
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
  $("sep-result").innerHTML = "";
  $("sep-msg").textContent = "";
  $("sep-stats").textContent = "";
}

$("sep-submit").onclick = async () => {
  const msg = $("sep-msg");
  const btn = $("sep-submit");
  msg.textContent = "";
  $("sep-result").innerHTML = "";
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

  $("other-result").innerHTML = "";
  $("other-msg").textContent = "";
  $("other-stats").textContent = "";
}

$("other-submit").onclick = async () => {
  const m = selectedModel();
  const msg = $("other-msg");
  const btn = $("other-submit");
  msg.textContent = "";
  $("other-result").innerHTML = "";
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
refreshInstances();
refreshEvents();
setInterval(() => {
  refreshInstances();
  refreshEvents();
}, 2000);
