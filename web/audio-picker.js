/* 音频选择组件：上传 / 录制 / 音色库 / 本地路径，含波形、播放、裁剪、保存到音色库。
   用法：const picker = new AudioPicker(mountEl, "picker.speakerRef");  // 第二参数为 i18n 字典键
        picker.getValue() → 服务器端音频绝对路径或 null；picker.clear() 清空。
        语言切换时由外部调用 picker.refreshLabels()。 */
/* 包在 IIFE 中：避免顶层 const 与 app.js 等其它脚本的同名声明冲突（经典脚本共享全局作用域） */
(() => {
const t = (k, p) => I18N.t(k, p);

window.AudioPicker = class AudioPicker {

  static MAX_BYTES = 50 * 1024 * 1024;

  constructor(mountEl, title) {
    this.value = null;          // 当前音频的服务器绝对路径
    this.uploadId = null;       // 当前上传件 id（上传/录制/裁剪后）
    this.audioBuffer = null;    // 暂存原始 AudioBuffer（波形、裁剪用）
    this.duration = 0;
    this.selection = null;      // {start, end} 秒
    this.voices = [];           // 音色库列表缓存
    this.previewVoiceId = null; // 音色库预览中的 vid

    this.root = mountEl;
    this.titleKey = title;      // i18n 字典键（refreshLabels 时重取）
    this.root.classList.add("picker");
    this.root.innerHTML = `
      <div class="picker-title">${t(title)}</div>
      <div class="picker-tabs">
        <button type="button" class="picker-tab active" data-tab="upload">${t("picker.tabUpload")}</button>
        <button type="button" class="picker-tab" data-tab="record">${t("picker.tabRecord")}</button>
        <button type="button" class="picker-tab" data-tab="library">${t("picker.tabLibrary")}</button>
        <button type="button" class="picker-tab" data-tab="path">${t("picker.tabPath")}</button>
      </div>
      <div class="picker-pane" data-pane="upload">
        <input type="file" accept="audio/*" class="file-input" hidden>
        <div class="dropzone" role="button" tabindex="0">${t("picker.dropzone")}</div>
      </div>
      <div class="picker-pane hidden" data-pane="record">
        <div class="rec-row">
          <button type="button" class="rec-start">${t("picker.recStart")}</button>
          <button type="button" class="rec-stop" disabled>${t("picker.recStop")}</button>
          <span class="rec-time">0:00</span>
        </div>
        <div class="rec-msg msg"></div>
      </div>
      <div class="picker-pane hidden" data-pane="library">
        <div class="lib-row">
          <select class="voice-select"></select>
          <button type="button" class="voice-load">${t("picker.voiceLoad")}</button>
          <button type="button" class="voice-use hidden">${t("picker.voiceUse")}</button>
          <button type="button" class="voice-del stop-btn">${t("picker.voiceDelete")}</button>
        </div>
      </div>
      <div class="picker-pane hidden" data-pane="path">
        <div class="path-row">
          <input type="text" class="path-input" placeholder="${t("picker.pathPlaceholder")}">
          <button type="button" class="path-browse">${t("picker.browse")}</button>
          <button type="button" class="path-probe">${t("picker.probe")}</button>
        </div>
        <div class="path-info hint"></div>
      </div>
      <div class="picker-common hidden">
        <canvas class="wave" height="90"></canvas>
        <div class="audio-info hint"></div>
        <div class="player-bar">
          <button type="button" class="play-btn">${t("picker.play")}</button>
          <span class="time-text"><span class="time-cur">0:00.0</span> / <span class="time-total">-</span></span>
          <select class="rate">
            <option value="0.5">0.5x</option>
            <option value="1" selected>1x</option>
            <option value="1.5">1.5x</option>
            <option value="2">2x</option>
          </select>
          <span class="vol-label">${t("picker.volume")}</span>
          <input type="range" class="volume" min="0" max="1" step="0.05" value="1">
        </div>
        <div class="trim-bar">
          <button type="button" class="trim-apply" disabled>${t("picker.trimApply")}</button>
          <button type="button" class="trim-clear" disabled>${t("picker.trimClear")}</button>
          <span class="trim-info hint">${t("picker.trimHint")}</span>
        </div>
        <div class="save-voice-row">
          <input type="text" class="voice-name" placeholder="${t("picker.voiceNamePlaceholder")}">
          <button type="button" class="voice-save">${t("picker.voiceSave")}</button>
          <span class="save-msg hint"></span>
        </div>
      </div>
      <div class="picker-msg-row">
        <span class="picker-msg msg"></span>
        <button type="button" class="picker-clear hidden">${t("picker.clearCurrent")}</button>
      </div>
      <audio class="player-el" preload="auto"></audio>`;

    this.$ = (sel) => this.root.querySelector(sel);
    this.canvas = this.$(".wave");
    this.ctx2d = this.canvas.getContext("2d");
    this.audioEl = this.$(".player-el");
    this.recorder = null;
    this.recChunks = [];
    this.recTimer = null;
    this.recStartTs = 0;

    this.bind();
    // 注册到全局列表，语言切换时由 app.js 统一调用 refreshLabels()
    window.__audioPickers = window.__audioPickers || [];
    window.__audioPickers.push(this);
    // 主题切换时重绘波形
    window.addEventListener("themechange", () => this.drawWave(this.audioEl.currentTime || 0));
  }

  /* ---------- 语言切换：重设所有静态文案（由外部统一调用） ---------- */
  refreshLabels() {
    this.$(".picker-title").textContent = t(this.titleKey);
    const tabKeys = {
      upload: "picker.tabUpload", record: "picker.tabRecord",
      library: "picker.tabLibrary", path: "picker.tabPath"
    };
    this.root.querySelectorAll(".picker-tab").forEach(tab => {
      tab.textContent = t(tabKeys[tab.dataset.tab]);
    });
    this.$(".dropzone").textContent = t("picker.dropzone");
    this.$(".rec-start").textContent = t("picker.recStart");
    this.$(".rec-stop").textContent = t("picker.recStop");
    this.$(".voice-load").textContent = t("picker.voiceLoad");
    this.$(".voice-use").textContent = t("picker.voiceUse");
    this.$(".voice-del").textContent = t("picker.voiceDelete");
    this.$(".path-input").placeholder = t("picker.pathPlaceholder");
    this.$(".path-browse").textContent = t("picker.browse");
    this.$(".path-probe").textContent = t("picker.probe");
    this.$(".play-btn").textContent = this.audioEl.paused ? t("picker.play") : t("picker.pause");
    this.$(".vol-label").textContent = t("picker.volume");
    this.$(".trim-apply").textContent = t("picker.trimApply");
    this.$(".trim-clear").textContent = t("picker.trimClear");
    this.$(".trim-info").textContent = this.selection
      ? t("picker.trimSelection", {
          start: WavUtil.formatDuration(this.selection.start),
          end: WavUtil.formatDuration(this.selection.end)
        })
      : t("picker.trimHint");
    this.$(".voice-name").placeholder = t("picker.voiceNamePlaceholder");
    this.$(".voice-save").textContent = t("picker.voiceSave");
    this.$(".picker-clear").textContent = t("picker.clearCurrent");
    // 音色库下拉当前显示空占位时，按当前语言重建
    const sel = this.$(".voice-select");
    if (sel.options.length === 1 && sel.options[0].value === "") {
      sel.innerHTML = `<option value=''>${t("picker.libraryEmpty")}</option>`;
    }
  }

  /* ---------- DOM 事件绑定 ---------- */
  bind() {
    // Tab 切换
    this.root.querySelectorAll(".picker-tab").forEach(tab => {
      tab.onclick = () => {
        this.root.querySelectorAll(".picker-tab").forEach(t => t.classList.toggle("active", t === tab));
        this.root.querySelectorAll(".picker-pane").forEach(p =>
          p.classList.toggle("hidden", p.dataset.pane !== tab.dataset.tab));
        if (tab.dataset.tab === "library") this.loadVoices();
      };
    });

    // 上传
    this.$(".file-input").onchange = (e) => {
      if (e.target.files.length) this.handleFile(e.target.files[0]);
      e.target.value = "";
    };
    const dz = this.$(".dropzone");
    dz.onclick = () => this.$(".file-input").click();
    dz.onkeydown = (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); this.$(".file-input").click(); }
    };
    dz.ondragover = (e) => { e.preventDefault(); dz.classList.add("dragover"); };
    dz.ondragleave = () => dz.classList.remove("dragover");
    dz.ondrop = (e) => {
      e.preventDefault();
      dz.classList.remove("dragover");
      if (e.dataTransfer.files.length) this.handleFile(e.dataTransfer.files[0]);
    };

    // 录制
    this.$(".rec-start").onclick = () => this.startRecord();
    this.$(".rec-stop").onclick = () => this.stopRecord();

    // 音色库
    this.$(".voice-load").onclick = () => this.previewVoice();
    this.$(".voice-use").onclick = () => this.usePreviewVoice();
    this.$(".voice-del").onclick = () => this.deleteVoice();

    // 本地路径
    this.$(".path-probe").onclick = () => this.probePath();
    this.$(".path-browse").onclick = () => this.browsePath();

    // 播放
    this.$(".play-btn").onclick = () => {
      if (this.audioEl.paused) this.audioEl.play(); else this.audioEl.pause();
    };
    this.audioEl.onplay = () => { this.$(".play-btn").textContent = t("picker.pause"); };
    this.audioEl.onpause = () => { this.$(".play-btn").textContent = t("picker.play"); };
    this.audioEl.onended = () => { this.$(".play-btn").textContent = t("picker.play"); };
    this.audioEl.ontimeupdate = () => {
      this.$(".time-cur").textContent = WavUtil.formatDuration(this.audioEl.currentTime);
      this.drawWave(this.audioEl.currentTime);
    };
    this.$(".rate").onchange = (e) => { this.audioEl.playbackRate = parseFloat(e.target.value); };
    this.$(".volume").oninput = (e) => { this.audioEl.volume = parseFloat(e.target.value); };

    // 波形：点击定位 / 拖选裁剪区间
    let dragStart = null, dragged = false;
    this.canvas.onmousedown = (e) => {
      if (!this.audioBuffer) return;
      dragStart = this.xToSec(e);
      dragged = false;
    };
    this.canvas.onmousemove = (e) => {
      if (dragStart == null || !this.audioBuffer) return;
      const sec = this.xToSec(e);
      if (Math.abs(sec - dragStart) * this.canvas.width / this.duration > 3) dragged = true;
      if (dragged) {
        this.selection = { start: Math.min(dragStart, sec), end: Math.max(dragStart, sec) };
        this.updateTrimUi();
        this.drawWave(this.audioEl.currentTime || 0);
      }
    };
    this.canvas.onmouseup = (e) => {
      if (dragStart == null) return;
      if (!dragged && this.duration > 0) {
        // 单击：定位播放
        this.audioEl.currentTime = this.xToSec(e);
        this.drawWave(this.audioEl.currentTime);
      }
      dragStart = null;
    };
    this.canvas.onmouseleave = () => { dragStart = null; };

    // 裁剪
    this.$(".trim-apply").onclick = () => this.applyTrim();
    this.$(".trim-clear").onclick = () => {
      this.selection = null;
      this.updateTrimUi();
      this.drawWave(this.audioEl.currentTime || 0);
    };

    // 保存到音色库
    this.$(".voice-save").onclick = () => this.saveToLibrary();

    // 清除当前音频
    this.$(".picker-clear").onclick = () => this.clear();
  }

  xToSec(e) {
    const rect = this.canvas.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    return ratio * this.duration;
  }

  /* ---------- 上传管线（上传/录制/裁剪共用） ---------- */
  async handleFile(file) {
    this.setMsg("");
    if (file.size > AudioPicker.MAX_BYTES) {
      this.setMsg(t("picker.errTooLarge"));
      return;
    }
    try {
      const arrayBuffer = await file.arrayBuffer();
      const buffer = await WavUtil.decodeToAudioBuffer(arrayBuffer);
      const wavBlob = WavUtil.audioBufferToWav(buffer);
      if (wavBlob.size > AudioPicker.MAX_BYTES) {
        this.setMsg(t("picker.errWavTooLarge"));
        return;
      }
      const info = await this.uploadWav(wavBlob);
      this.showCommon(info, buffer);
    } catch (e) {
      this.setMsg(t("picker.errProcess", { msg: e.message || e }));
    }
  }

  async uploadWav(blob) {
    const res = await fetch("/api/audio/upload", { method: "POST", body: blob });
    const text = await res.text();
    if (!res.ok) throw new Error(I18N.errText(text));
    return JSON.parse(text);
  }

  /** 上传成功后展示公共区，并把当前值设为服务器路径。 */
  showCommon(info, buffer) {
    this.uploadId = info.id;
    this.value = info.path;
    this.$(".picker-clear").classList.remove("hidden");
    this.audioBuffer = buffer;
    this.duration = buffer.duration;
    this.selection = null;
    this.previewVoiceId = null;
    this.$(".picker-common").classList.remove("hidden");
    this.$(".save-voice-row").classList.remove("hidden");
    this.$(".voice-use").classList.add("hidden");
    this.audioEl.src = "/api/audio/file?id=" + info.id;
    this.$(".time-total").textContent = WavUtil.formatDuration(this.duration);
    this.$(".time-cur").textContent = "0:00.0";
    this.$(".audio-info").textContent = t("picker.audioInfo", {
      dur: WavUtil.formatDuration(info.durationSec),
      rate: info.sampleRate,
      ch: info.channels,
      bits: info.bitsPerSample,
      size: WavUtil.formatSize(info.sizeBytes)
    });
    this.$(".save-msg").textContent = "";
    this.updateTrimUi();
    // 等布局完成后按实际宽度画波形
    requestAnimationFrame(() => this.drawWave(0));
    this.setMsg("");
  }

  /* ---------- 波形绘制（颜色取自当前主题的 CSS 变量） ---------- */
  themeColors() {
    const cs = getComputedStyle(document.documentElement);
    const v = (name, fallback) => (cs.getPropertyValue(name).trim() || fallback);
    return {
      bg: v("--wave-bg", "#f1f3f8"),
      dim: v("--wave-dim", "#b9c0cf"),
      accent: v("--accent", "#6366f1"),
      accent2: v("--accent-2", "#22d3ee"),
      err: v("--err", "#dc2626")
    };
  }

  drawWave(progressSec) {
    const canvas = this.canvas;
    const width = canvas.parentElement.clientWidth || 300;
    if (canvas.width !== width) canvas.width = width;
    const height = canvas.height;
    const ctx = this.ctx2d;
    const colors = this.themeColors();
    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = colors.bg;
    ctx.fillRect(0, 0, width, height);
    if (!this.audioBuffer) return;

    // peaks 数据（每像素一列 min/max）
    const data = this.audioBuffer.getChannelData(0);
    const step = Math.max(1, Math.floor(data.length / width));
    const mid = height / 2;
    const peaks = new Array(width);
    for (let x = 0; x < width; x++) {
      let min = 1, max = -1;
      const base = x * step;
      for (let i = 0; i < step && base + i < data.length; i += 8) {
        const v = data[base + i];
        if (v < min) min = v;
        if (v > max) max = v;
      }
      peaks[x] = min > max ? [0, 0] : [min, max];
    }
    const drawPeaks = (xFrom, xTo) => {
      for (let x = xFrom; x < xTo; x++) {
        const [min, max] = peaks[x];
        const y1 = mid - max * mid;
        const y2 = mid - min * mid;
        ctx.fillRect(x, y1, 1, Math.max(1, y2 - y1));
      }
    };

    // 未播放部分：dim 色
    const progressX = (progressSec > 0 && this.duration > 0)
      ? Math.min(width, progressSec / this.duration * width) : 0;
    ctx.fillStyle = colors.dim;
    drawPeaks(0, width);

    // 已播放部分：accent 渐变（裁剪区域重绘）
    if (progressX > 0) {
      const grad = ctx.createLinearGradient(0, 0, width, 0);
      grad.addColorStop(0, colors.accent);
      grad.addColorStop(1, colors.accent2);
      ctx.save();
      ctx.beginPath();
      ctx.rect(0, 0, progressX, height);
      ctx.clip();
      ctx.fillStyle = grad;
      drawPeaks(0, Math.ceil(progressX));
      ctx.restore();
    }

    // 裁剪选区高亮
    if (this.selection && this.duration > 0) {
      const x1 = this.selection.start / this.duration * width;
      const x2 = this.selection.end / this.duration * width;
      ctx.fillStyle = colors.accent + "33";
      ctx.fillRect(x1, 0, x2 - x1, height);
      ctx.strokeStyle = colors.accent;
      ctx.strokeRect(x1 + 0.5, 0.5, x2 - x1 - 1, height - 1);
    }

    // 播放进度线
    if (progressSec > 0 && this.duration > 0) {
      ctx.strokeStyle = colors.err;
      ctx.beginPath();
      ctx.moveTo(progressX, 0);
      ctx.lineTo(progressX, height);
      ctx.stroke();
    }
  }

  updateTrimUi() {
    const has = !!this.selection;
    this.$(".trim-apply").disabled = !has;
    this.$(".trim-clear").disabled = !has;
    this.$(".trim-info").textContent = has
      ? t("picker.trimSelection", {
          start: WavUtil.formatDuration(this.selection.start),
          end: WavUtil.formatDuration(this.selection.end)
        })
      : t("picker.trimHint");
  }

  /* ---------- 裁剪 ---------- */
  async applyTrim() {
    if (!this.selection || !this.audioBuffer) return;
    this.setMsg("");
    try {
      const wavBlob = WavUtil.audioBufferToWav(this.audioBuffer, this.selection.start, this.selection.end);
      const info = await this.uploadWav(wavBlob);
      const buffer = await WavUtil.decodeToAudioBuffer(await wavBlob.arrayBuffer());
      this.showCommon(info, buffer);
      this.setMsg(t("picker.trimDone"));
    } catch (e) {
      this.setMsg(t("picker.trimFailed", { msg: e.message || e }));
    }
  }

  /* ---------- 录制 ---------- */
  async startRecord() {
    const msgEl = this.$(".rec-msg");
    msgEl.textContent = "";
    if (!navigator.mediaDevices || !window.MediaRecorder) {
      msgEl.textContent = t("picker.errNoRecord");
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      this.recChunks = [];
      this.recorder = new MediaRecorder(stream);
      this.recorder.ondataavailable = (e) => { if (e.data.size) this.recChunks.push(e.data); };
      this.recorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        clearInterval(this.recTimer);
        this.$(".rec-start").disabled = false;
        this.$(".rec-stop").disabled = true;
        try {
          const blob = new Blob(this.recChunks, { type: this.recorder.mimeType || "audio/webm" });
          const buffer = await WavUtil.decodeToAudioBuffer(await blob.arrayBuffer());
          const wavBlob = WavUtil.audioBufferToWav(buffer);
          const info = await this.uploadWav(wavBlob);
          this.showCommon(info, buffer);
        } catch (e) {
          msgEl.textContent = t("picker.errRecProcess", { msg: e.message || e });
        }
      };
      this.recorder.start();
      this.recStartTs = Date.now();
      this.$(".rec-start").disabled = true;
      this.$(".rec-stop").disabled = false;
      this.recTimer = setInterval(() => {
        this.$(".rec-time").textContent = WavUtil.formatDuration((Date.now() - this.recStartTs) / 1000);
      }, 200);
    } catch (e) {
      msgEl.textContent = t("picker.errMic", { msg: e.message || e });
    }
  }

  stopRecord() {
    if (this.recorder && this.recorder.state !== "inactive") this.recorder.stop();
  }

  /* ---------- 音色库 ---------- */
  /* 音色库的反馈是瞬时操作结果，统一走全局 toast（8 秒自动消失） */
  toast(level, message) {
    if (typeof window.showToast === "function") window.showToast(level, message);
  }

  async loadVoices() {
    const select = this.$(".voice-select");
    try {
      const res = await fetch("/api/voices");
      this.voices = await res.json();
    } catch (e) {
      this.voices = [];
    }
    select.innerHTML = "";
    if (this.voices.length === 0) {
      select.innerHTML = `<option value=''>${t("picker.libraryEmpty")}</option>`;
      return;
    }
    for (const v of this.voices) {
      const opt = document.createElement("option");
      opt.value = v.vid;
      opt.textContent = `${v.name}（${WavUtil.formatDuration(v.durationSec)}）`;
      select.appendChild(opt);
    }
  }

  selectedVoice() {
    const vid = this.$(".voice-select").value;
    return this.voices.find(v => v.vid === vid) || null;
  }

  async previewVoice() {
    const voice = this.selectedVoice();
    if (!voice) { this.toast("info", t("picker.errSelectVoice")); return; }
    try {
      const res = await fetch("/api/voices/" + voice.vid + "/audio");
      if (!res.ok) throw new Error(I18N.errText(await res.text()));
      const buffer = await WavUtil.decodeToAudioBuffer(await res.arrayBuffer());
      // 预览：显示公共区但不改变当前值，值由"选为当前"确认
      this.uploadId = null;
      this.audioBuffer = buffer;
      this.duration = buffer.duration;
      this.selection = null;
      this.previewVoiceId = voice.vid;
      this.$(".picker-common").classList.remove("hidden");
      this.$(".save-voice-row").classList.add("hidden");
      this.$(".voice-use").classList.remove("hidden");
      this.audioEl.src = "/api/voices/" + voice.vid + "/audio";
      this.$(".time-total").textContent = WavUtil.formatDuration(this.duration);
      this.$(".time-cur").textContent = "0:00.0";
      this.$(".audio-info").textContent = t("picker.voicePreviewInfo", {
        name: voice.name,
        dur: WavUtil.formatDuration(voice.durationSec),
        rate: voice.sampleRate,
        size: WavUtil.formatSize(voice.sizeBytes)
      });
      this.updateTrimUi();
      requestAnimationFrame(() => this.drawWave(0));
    } catch (e) {
      this.toast("error", t("picker.loadFailed", { msg: e.message || e }));
    }
  }

  usePreviewVoice() {
    const voice = this.voices.find(v => v.vid === this.previewVoiceId);
    if (!voice) return;
    this.value = voice.path;
    this.$(".picker-clear").classList.remove("hidden");
    this.toast("info", t("picker.voiceInUse", { name: voice.name }));
    this.setMsg(t("picker.currentAudio", { name: voice.name }));
  }

  async deleteVoice() {
    const voice = this.selectedVoice();
    if (!voice) { this.toast("info", t("picker.errSelectVoice")); return; }
    try {
      const res = await fetch("/api/voices/" + voice.vid, { method: "DELETE" });
      if (!res.ok) throw new Error(I18N.errText(await res.text()));
      this.toast("info", t("picker.deleted", { name: voice.name }));
      if (this.previewVoiceId === voice.vid) {
        this.$(".picker-common").classList.add("hidden");
        this.previewVoiceId = null;
      }
      this.loadVoices();
    } catch (e) {
      this.toast("error", t("picker.deleteFailed", { msg: e.message || e }));
    }
  }

  /* ---------- 保存到音色库 ---------- */
  async saveToLibrary() {
    const nameEl = this.$(".voice-name");
    const msgEl = this.$(".save-msg");
    msgEl.textContent = "";
    if (!this.uploadId) { msgEl.textContent = t("picker.errNoUpload"); return; }
    const name = nameEl.value.trim();
    if (!name) { msgEl.textContent = t("picker.errNoName"); return; }
    try {
      const res = await fetch("/api/voices", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, uploadId: this.uploadId })
      });
      const text = await res.text();
      if (!res.ok) throw new Error(I18N.errText(text));
      msgEl.textContent = t("picker.saved", { name });
      nameEl.value = "";
      this.loadVoices();
    } catch (e) {
      msgEl.textContent = t("picker.saveFailed", { msg: e.message || e });
    }
  }

  /* ---------- 本地路径 ---------- */
  /* 通过服务器端文件选择器挑选音频文件，选好后自动探测 */
  async browsePath() {
    if (!window.FileBrowser) return;
    const path = await FileBrowser.open({
      mode: "file",
      title: t("picker.browseTitle"),
      extensions: [".wav", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".opus"],
      startPath: this.$(".path-input").value.trim()
    });
    if (path) {
      this.$(".path-input").value = path;
      this.probePath();
    }
  }

  async probePath() {
    const input = this.$(".path-input");
    const infoEl = this.$(".path-info");
    const path = input.value.trim();
    infoEl.textContent = "";
    if (!path) { infoEl.textContent = t("picker.errNoPath"); return; }
    try {
      const res = await fetch("/api/audio/info", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ path })
      });
      const text = await res.text();
      if (!res.ok) {
        // 非 WAV / 不存在：仍按原路径透传
        this.value = path;
        this.$(".picker-clear").classList.remove("hidden");
        infoEl.textContent = t("picker.notWav", { msg: I18N.errText(text) });
        this.setMsg(t("picker.currentAudio", { name: path }));
        return;
      }
      const info = JSON.parse(text);
      this.value = info.path || path;
      this.$(".picker-clear").classList.remove("hidden");
      infoEl.textContent = t("picker.wavInfo", {
        dur: WavUtil.formatDuration(info.durationSec),
        rate: info.sampleRate,
        ch: info.channels,
        bits: info.bitsPerSample,
        size: WavUtil.formatSize(info.sizeBytes)
      });
      this.setMsg(t("picker.currentAudio", { name: this.value }));
    } catch (e) {
      infoEl.textContent = t("picker.probeFailed", { msg: e.message || e });
    }
  }

  /* ---------- 对外接口 ---------- */
  getValue() {
    return this.value;
  }

  clear() {
    this.value = null;
    this.uploadId = null;
    this.audioBuffer = null;
    this.duration = 0;
    this.selection = null;
    this.previewVoiceId = null;
    this.audioEl.pause();
    this.audioEl.removeAttribute("src");
    this.$(".picker-common").classList.add("hidden");
    this.$(".picker-clear").classList.add("hidden");
    this.$(".path-input").value = "";
    this.$(".path-info").textContent = "";
    this.$(".voice-use").classList.add("hidden");
    this.setMsg("");
  }

  setMsg(text) {
    this.$(".picker-msg").textContent = text;
  }
};
})();
