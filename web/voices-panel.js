/* 音色库管理面板：页头 🎙 打开。音色是全局资源：名称（唯一）+ 音频文本内容 + 音频文件。
   上半为音色列表（试听/行内编辑名称与文本/删除），下半为添加区（名称 + 文本 + AudioPicker 取音频）。
   增删改成功后刷新所有 VoiceSelect（refreshVoiceSelects）。 */
(() => {
const t = (k, p) => I18N.t(k, p);
let addPicker = null;   // 添加区的 AudioPicker（延迟到首次打开时创建）
let voices = [];

window.openVoicesPanel = function () {
  $("voices-panel").classList.remove("hidden");
  if (!addPicker) {
    addPicker = new AudioPicker($("voice-add-picker"), "voices.addAudio");
    // 管理面板里添加音色时，"音色库"页签无意义（从库选库），隐藏
    const libTab = addPicker.root.querySelector('.picker-tab[data-tab="library"]');
    if (libTab) libTab.style.display = "none";
  }
  loadVoices();
};

function closeVoicesPanel() {
  $("voices-panel").classList.add("hidden");
}
window.closeVoicesPanel = closeVoicesPanel;

$("voices-btn").onclick = window.openVoicesPanel;
$("voices-close").onclick = closeVoicesPanel;
$("voices-panel").addEventListener("mousedown", (e) => {
  if (e.target === e.currentTarget) closeVoicesPanel();
});

/* ---------- 列表 ---------- */
async function loadVoices() {
  try {
    const res = await fetch("/api/voices");
    voices = await res.json();
  } catch (e) {
    voices = [];
  }
  renderVoicesList();
}

function renderVoicesList() {
  const list = $("voices-list");
  list.innerHTML = "";
  if (!voices.length) {
    const hint = document.createElement("div");
    hint.className = "hint history-empty";
    hint.textContent = t("voices.empty");
    list.appendChild(hint);
    return;
  }
  for (const v of voices) list.appendChild(makeVoiceRow(v));
}

/* 单行音色：名称 + 时长 + 文本预览 + 试听/编辑/删除；编辑态行内改名与文本 */
function makeVoiceRow(v) {
  const row = document.createElement("div");
  row.className = "voice-row";
  const info = document.createElement("div");
  info.className = "voice-info";
  const name = document.createElement("span");
  name.className = "voice-name";
  name.textContent = v.name;
  const meta = document.createElement("span");
  meta.className = "voice-meta hint";
  meta.textContent = (v.durationSec ? WavUtil.formatDuration(v.durationSec) : "")
    + (v.text ? " ｜ " + (v.text.length > 40 ? v.text.slice(0, 40) + "…" : v.text) : "");
  if (v.text) meta.title = v.text;
  info.appendChild(name);
  info.appendChild(meta);
  const btns = document.createElement("span");
  btns.className = "voice-btns";

  const audio = document.createElement("audio");
  audio.preload = "none";
  const playBtn = document.createElement("button");
  playBtn.type = "button";
  playBtn.className = "btn-ghost";
  playBtn.textContent = t("voiceSelect.play");
  playBtn.onclick = () => {
    if (audio.paused) {
      if (!audio.src) audio.src = "/api/voices/" + v.vid + "/audio";
      audio.play();
    } else audio.pause();
  };
  audio.onplay = () => { playBtn.textContent = t("voiceSelect.pause"); };
  audio.onpause = () => { playBtn.textContent = t("voiceSelect.play"); };
  audio.onended = () => { playBtn.textContent = t("voiceSelect.play"); };
  row.appendChild(audio);

  const editBtn = document.createElement("button");
  editBtn.type = "button";
  editBtn.className = "btn-ghost";
  editBtn.textContent = t("voices.edit");
  editBtn.onclick = () => enterEdit(row, v);

  const delBtn = document.createElement("button");
  delBtn.type = "button";
  delBtn.className = "stop-btn";
  delBtn.textContent = t("voices.delete");
  delBtn.onclick = () => deleteVoice(v);

  btns.appendChild(playBtn);
  btns.appendChild(editBtn);
  btns.appendChild(delBtn);
  row.appendChild(info);
  row.appendChild(btns);
  return row;
}

/* 行内编辑：名称 input + 文本 textarea + 保存/取消 */
function enterEdit(row, v) {
  row.innerHTML = "";
  row.classList.add("editing");
  const nameInput = document.createElement("input");
  nameInput.type = "text";
  nameInput.value = v.name;
  nameInput.className = "voice-edit-name";
  const textInput = document.createElement("textarea");
  textInput.rows = 2;
  textInput.value = v.text || "";
  textInput.placeholder = t("voices.textPlaceholder");
  textInput.className = "voice-edit-text";
  const btns = document.createElement("div");
  btns.className = "voice-edit-btns";
  const saveBtn = document.createElement("button");
  saveBtn.type = "button";
  saveBtn.className = "btn";
  saveBtn.textContent = t("voices.save");
  const cancelBtn = document.createElement("button");
  cancelBtn.type = "button";
  cancelBtn.className = "btn-ghost";
  cancelBtn.textContent = t("voices.cancel");
  const msg = document.createElement("span");
  msg.className = "hint";
  saveBtn.onclick = async () => {
    msg.textContent = "";
    try {
      const res = await fetch("/api/voices/" + v.vid, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: nameInput.value, text: textInput.value })
      });
      const text = await res.text();
      if (!res.ok) throw new Error(I18N.errText(text));
      afterChange();
    } catch (e) {
      msg.textContent = e.message;
    }
  };
  cancelBtn.onclick = () => renderVoicesList();
  btns.appendChild(saveBtn);
  btns.appendChild(cancelBtn);
  btns.appendChild(msg);
  row.appendChild(nameInput);
  row.appendChild(textInput);
  row.appendChild(btns);
  nameInput.focus();
}

async function deleteVoice(v) {
  if (!window.confirm(t("voices.confirmDelete", { name: v.name }))) return;
  try {
    const res = await fetch("/api/voices/" + v.vid, { method: "DELETE" });
    if (!res.ok) throw new Error(I18N.errText(await res.text()));
    afterChange();
  } catch (e) {
    showToast("error", t("voices.deleteFailed") + t("common.colon") + e.message);
  }
}

/* ---------- 添加 ---------- */
$("voice-add-btn").onclick = async () => {
  const nameEl = $("voice-add-name");
  const textEl = $("voice-add-text");
  const msg = $("voice-add-msg");
  msg.textContent = "";
  const name = nameEl.value.trim();
  const path = addPicker ? addPicker.getValue() : null;
  if (!name) { msg.textContent = t("voices.errNoName"); return; }
  if (!path) { msg.textContent = t("voices.errNoAudio"); return; }
  const body = { name, text: textEl.value.trim() };
  // AudioPicker 上传/录制/裁剪后有 uploadId；本地路径则直接传 path
  if (addPicker.uploadId) body.uploadId = addPicker.uploadId;
  else body.path = path;
  try {
    const res = await fetch("/api/voices", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const text = await res.text();
    if (!res.ok) throw new Error(I18N.errText(text));
    nameEl.value = "";
    textEl.value = "";
    addPicker.clear();
    showToast("info", t("voices.added", { name }));
    afterChange();
  } catch (e) {
    msg.textContent = e.message;
  }
};

/* 增删改后的统一收尾：刷新面板列表 + 全部 VoiceSelect */
function afterChange() {
  loadVoices();
  if (window.refreshVoiceSelects) window.refreshVoiceSelects();
}
})();
