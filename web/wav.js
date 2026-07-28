/* WAV 工具：解码 / PCM16 单声道 WAV 编码 / 格式化。挂 window.WavUtil */
window.WavUtil = (function () {

  /** ArrayBuffer → AudioBuffer（mp3/m4a/wav/webm 等浏览器可解码格式均可）。 */
  function decodeToAudioBuffer(arrayBuffer) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    return ctx.decodeAudioData(arrayBuffer).finally(() => ctx.close());
  }

  /**
   * AudioBuffer → PCM16 单声道 WAV Blob。
   * startSec/endSec 可省略（省略则整段）；多声道混成单声道。
   */
  function audioBufferToWav(buffer, startSec, endSec) {
    const sampleRate = buffer.sampleRate;
    const start = Math.max(0, Math.floor((startSec || 0) * sampleRate));
    const end = Math.min(buffer.length, endSec != null ? Math.ceil(endSec * sampleRate) : buffer.length);
    const length = Math.max(0, end - start);
    const numCh = buffer.numberOfChannels;
    const channels = [];
    for (let c = 0; c < numCh; c++) channels.push(buffer.getChannelData(c));

    const dataSize = length * 2;
    const buf = new ArrayBuffer(44 + dataSize);
    const view = new DataView(buf);
    const writeStr = (off, s) => { for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i)); };

    writeStr(0, "RIFF");
    view.setUint32(4, 36 + dataSize, true);
    writeStr(8, "WAVE");
    writeStr(12, "fmt ");
    view.setUint32(16, 16, true);          // fmt chunk 大小
    view.setUint16(20, 1, true);           // PCM
    view.setUint16(22, 1, true);           // 单声道
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true); // byteRate
    view.setUint16(32, 2, true);           // blockAlign
    view.setUint16(34, 16, true);          // bitsPerSample
    writeStr(36, "data");
    view.setUint32(40, dataSize, true);

    let offset = 44;
    for (let i = 0; i < length; i++) {
      let sample = 0;
      for (let c = 0; c < numCh; c++) sample += channels[c][start + i];
      sample /= numCh;
      sample = Math.max(-1, Math.min(1, sample));
      view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7FFF, true);
      offset += 2;
    }
    return new Blob([buf], { type: "audio/wav" });
  }

  /** 秒 → m:ss.s */
  function formatDuration(sec) {
    if (sec == null || !isFinite(sec)) return "-";
    const m = Math.floor(sec / 60);
    const s = sec - m * 60;
    return m + ":" + s.toFixed(1).padStart(4, "0");
  }
  /** 字节数 → 可读字符串 */
  function formatSize(bytes) {
    if (bytes == null) return "-";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / 1024 / 1024).toFixed(2) + " MB";
  }

  /**
   * 预热音频输出设备。首次播放 <audio> 时浏览器才打开系统输出流，
   * 设备挂起 / 蓝牙休眠时打开要几百 ms，这段时间的样本会被丢弃（开头少一个音节）。
   * 在首次用户手势时就让一个无声 AudioContext 常驻 running，保持输出流常开。
   */
  let warmCtx = null;
  function warmAudioOutput() {
    if (warmCtx) return;
    try {
      warmCtx = new (window.AudioContext || window.webkitAudioContext)();
      const silence = warmCtx.createBuffer(1, warmCtx.sampleRate, warmCtx.sampleRate);
      const src = warmCtx.createBufferSource();
      src.buffer = silence;
      src.loop = true;
      const gain = warmCtx.createGain();
      gain.gain.value = 0;
      src.connect(gain).connect(warmCtx.destination);
      src.start();
      warmCtx.resume();
    } catch (e) { warmCtx = null; }
  }
  for (const evt of ["pointerdown", "keydown", "touchstart"]) {
    document.addEventListener(evt, warmAudioOutput, { once: true, passive: true });
  }

  return { decodeToAudioBuffer, audioBufferToWav, formatDuration, formatSize, warmAudioOutput };
})();
