/* 音色下拉选择器：从全局音色库直选，选中即生效（getValue 返回该音色的服务器路径，引擎直接读本地文件）。
   用法：const sel = new VoiceSelect(mountEl, "picker.speakerRef", { onChange });
        getValue() → 服务器端音频绝对路径或 null；getSelected() → 音色条目或 null；
        setVoice(vid) / setByPath(path)（历史载入用，库外路径走「外部路径」兜底选项）/ clear()。
   语言切换时由外部统一调用 refreshLabels()；音色库增删改后由 voices-panel.js 调 refreshVoiceSelects()。 */
/* 包在 IIFE 中：避免顶层 const 与 app.js 等其它脚本的同名声明冲突（经典脚本共享全局作用域） */
(() => {
const t = (k, p) => I18N.t(k, p);

window.VoiceSelect = class VoiceSelect {

  constructor(mountEl, title, opts) {
    opts = opts || {};
    this.onChange = opts.onChange || null;
    this.voices = [];           // 音色库列表缓存
    this.externalPath = null;   // setByPath 的库外路径兜底
    this.playing = false;

    this.root = mountEl;
    this.titleKey = title;
    this.root.classList.add("voice-select-box");
    this.root.innerHTML = `
      <div class="vs-title"></div>
      <div class="vs-row">
        <select class="vs-select"></select>
        <button type="button" class="vs-play btn-ghost" disabled></button>
        <button type="button" class="vs-manage btn-ghost"></button>
      </div>
      <audio class="vs-audio" preload="none"></audio>`;
    this.sel = this.root.querySelector(".vs-select");
    this.playBtn = this.root.querySelector(".vs-play");
    this.manageBtn = this.root.querySelector(".vs-manage");
    this.audioEl = this.root.querySelector(".vs-audio");

    this.sel.onchange = () => {
      this.stopAudio();
      this.playBtn.disabled = !this.getValue();
      if (this.onChange) this.onChange(this.getSelected());
    };
    this.playBtn.onclick = () => this.togglePlay();
    this.manageBtn.onclick = () => { if (window.openVoicesPanel) window.openVoicesPanel(); };
    this.audioEl.onplay = () => this.setPlaying(true);
    this.audioEl.onpause = () => this.setPlaying(false);
    this.audioEl.onended = () => this.setPlaying(false);

    // 注册到全局列表：语言切换时由 app.js 统一调用 refreshLabels()
    window.__voiceSelects = window.__voiceSelects || [];
    window.__voiceSelects.push(this);
    this.refreshLabels();
    this.refresh();
  }

  /* ---------- 语言切换 ---------- */
  refreshLabels() {
    this.root.querySelector(".vs-title").textContent = t(this.titleKey);
    this.playBtn.textContent = this.playing ? t("voiceSelect.pause") : t("voiceSelect.play");
    this.manageBtn.textContent = t("voiceSelect.manage");
    if (this.sel.options.length && this.sel.options[0].value === "") {
      this.sel.options[0].textContent = t("voiceSelect.placeholder");
    }
    const ext = [...this.sel.options].find(o => o.value === "__external__");
    if (ext) ext.textContent = this.externalLabel();
  }

  externalLabel() {
    return t("voiceSelect.externalPath") + " " + this.externalPath.split(/[\\/]/).pop();
  }

  /* ---------- 数据 ---------- */
  /* 重建选项并尽量保留选中；选中的音色被删时自动降级为「外部路径」选项（用其最后已知路径） */
  async refresh() {
    const keep = this.sel.value;
    const oldVoices = this.voices;
    try {
      const res = await fetch("/api/voices");
      this.voices = await res.json();
    } catch (e) {
      this.voices = [];
    }
    if (keep && keep !== "__external__" && !this.voices.some(v => v.vid === keep)) {
      const gone = oldVoices.find(v => v.vid === keep);
      if (gone) this.externalPath = gone.path;
    }
    this.rebuild(keep);
  }

  rebuild(keep) {
    this.sel.innerHTML = "";
    const ph = document.createElement("option");
    ph.value = "";
    ph.textContent = t("voiceSelect.placeholder");
    this.sel.appendChild(ph);
    for (const v of this.voices) {
      const opt = document.createElement("option");
      opt.value = v.vid;
      opt.textContent = v.name + (v.durationSec ? `（${WavUtil.formatDuration(v.durationSec)}）` : "");
      opt.title = v.text || "";
      this.sel.appendChild(opt);
    }
    if (this.externalPath) {
      const opt = document.createElement("option");
      opt.value = "__external__";
      opt.textContent = this.externalLabel();
      this.sel.appendChild(opt);
    }
    const values = [...this.sel.options].map(o => o.value);
    if (keep === "__external__" && this.externalPath) this.sel.value = "__external__";
    else if (keep && values.includes(keep)) this.sel.value = keep;
    else if (this.externalPath) this.sel.value = "__external__";
    this.playBtn.disabled = !this.getValue();
  }

  /* ---------- 播放（懒加载，点击才拉音频） ---------- */
  setPlaying(on) {
    this.playing = on;
    this.playBtn.textContent = on ? t("voiceSelect.pause") : t("voiceSelect.play");
  }

  stopAudio() {
    this.audioEl.pause();
    this.audioEl.removeAttribute("src");
  }

  togglePlay() {
    const v = this.getSelected();
    if (!v || v.external) return; // 外部路径没有可回放的库内音频
    if (this.playing) { this.audioEl.pause(); return; }
    const src = "/api/voices/" + v.vid + "/audio";
    if (this.audioEl.dataset.vid !== v.vid) {
      this.audioEl.src = src;
      this.audioEl.dataset.vid = v.vid;
    }
    this.audioEl.play();
  }

  /* ---------- 对外接口 ---------- */
  getSelected() {
    const val = this.sel.value;
    if (val === "__external__") return { path: this.externalPath, external: true };
    return this.voices.find(v => v.vid === val) || null;
  }

  getValue() {
    const v = this.getSelected();
    return v ? v.path : null;
  }

  async setVoice(vid) {
    this.externalPath = null;
    if (!this.voices.length) await this.refresh();
    this.sel.value = this.voices.some(v => v.vid === vid) ? vid : "";
    this.playBtn.disabled = !this.getValue();
  }

  /* 历史载入：path 匹配库中音色则选中；不匹配（已删音色/历史快照路径）走外部路径兜底 */
  async setByPath(path) {
    if (!path) { this.clear(); return; }
    if (!this.voices.length) await this.refresh();
    const hit = this.voices.find(v => v.path === path);
    this.externalPath = hit ? null : path;
    this.rebuild(hit ? hit.vid : "__external__");
    if (this.onChange) this.onChange(this.getSelected());
  }

  clear() {
    this.externalPath = null;
    this.sel.value = "";
    this.stopAudio();
    this.playBtn.disabled = true;
  }
};

/* 音色库增删改后由管理面板调用：所有已注册选择器刷新选项（尽量保留选中） */
window.refreshVoiceSelects = function () {
  (window.__voiceSelects || []).forEach(vs => vs.refresh());
};
})();
