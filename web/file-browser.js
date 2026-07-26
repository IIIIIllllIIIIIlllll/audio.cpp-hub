/* 服务器端文件选择器：浏览 hub 所在机器的文件系统（浏览器原生选择器只能选客户端文件）。
   用法：const path = await FileBrowser.open({
            mode: "file" | "dir",   // file=选文件，dir=选目录
            title: "选择文件",
            extensions: [".wav"],   // 可选，仅 file 模式；自动附带“所有文件”选项
            startPath: "D:/models"  // 可选，初始目录
          });
        用户取消时返回 null。 */
window.FileBrowser = (() => {
  const t = (k, p) => I18N.t(k, p);

  let overlay = null;
  let resolvePromise = null;
  let opts = null;
  let cwd = "";              // 当前目录（"" 表示根列表视图）
  let cwdParent = "";
  let entries = [];          // 当前目录条目（服务端已按目录优先排序）
  let selectedPath = null;   // 列表中选中的条目
  let selectedIsDir = false;
  let extFilter = "";        // 生效的扩展名过滤，"" 为全部
  let showHidden = false;

  const $ = (sel) => overlay.querySelector(sel);

  function buildOverlay() {
    overlay = document.createElement("div");
    overlay.className = "modal-overlay fb-overlay hidden";
    overlay.innerHTML = `
      <div class="modal fb-modal">
        <div class="modal-header">
          <span class="modal-title fb-title">${t("fb.titleFile")}</span>
          <button type="button" class="modal-close fb-close">×</button>
        </div>
        <div class="fb-toolbar">
          <button type="button" class="fb-up" title="${t("fb.upTitle")}">${t("fb.up")}</button>
          <input type="text" class="fb-path" placeholder="${t("fb.pathPlaceholder")}">
          <button type="button" class="fb-go">${t("fb.go")}</button>
          <button type="button" class="fb-refresh" title="${t("fb.refreshTitle")}">⟳</button>
        </div>
        <div class="fb-roots"></div>
        <div class="fb-subbar">
          <input type="text" class="fb-search" placeholder="${t("fb.searchPlaceholder")}">
          <select class="fb-ext hidden"></select>
          <label class="checkbox-label fb-hidden-toggle"><input type="checkbox" class="fb-hidden"> ${t("fb.showHidden")}</label>
          <button type="button" class="fb-mkdir">${t("fb.mkdir")}</button>
        </div>
        <div class="fb-list"></div>
        <div class="fb-footer">
          <span class="fb-selection" title=""></span>
          <button type="button" class="fb-pick-current btn-ghost hidden">${t("fb.pickCurrent")}</button>
          <button type="button" class="fb-cancel">${t("fb.cancel")}</button>
          <button type="button" class="fb-confirm btn" disabled>${t("fb.confirm")}</button>
        </div>
      </div>`;
    document.body.appendChild(overlay);

    $(".fb-close").onclick = () => cancel();
    $(".fb-cancel").onclick = () => cancel();
    overlay.onclick = (e) => { if (e.target === overlay) cancel(); };
    $(".fb-up").onclick = () => goUp();
    $(".fb-go").onclick = () => navigate($(".fb-path").value.trim());
    $(".fb-path").onkeydown = (e) => { if (e.key === "Enter") navigate($(".fb-path").value.trim()); };
    $(".fb-refresh").onclick = () => cwd ? navigate(cwd) : showRoots();
    $(".fb-search").oninput = renderList;
    $(".fb-ext").onchange = (e) => { extFilter = e.target.value; renderList(); };
    $(".fb-hidden").onchange = (e) => { showHidden = e.target.checked; renderList(); };
    $(".fb-mkdir").onclick = mkdir;
    $(".fb-confirm").onclick = confirmSelection;
    $(".fb-pick-current").onclick = () => finish(cwd || null);

    // Esc 关闭：capture 阶段拦截，避免触发下层 modal 的 Esc 处理
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && isOpen()) {
        e.stopPropagation();
        cancel();
      }
    }, true);
  }

  function isOpen() {
    return overlay && !overlay.classList.contains("hidden");
  }

  async function open(options) {
    if (!overlay) buildOverlay();
    if (isOpen()) cancel(); // 已有打开的实例：按取消处理
    opts = options || {};
    cwd = "";
    cwdParent = "";
    entries = [];
    selectedPath = null;
    extFilter = "";
    showHidden = false;
    $(".fb-hidden").checked = false;
    $(".fb-search").value = "";
    $(".fb-title").textContent = opts.title || (opts.mode === "dir" ? t("fb.titleDir") : t("fb.titleFile"));
    $(".fb-pick-current").classList.toggle("hidden", opts.mode !== "dir");
    buildExtFilter();
    loadRoots();
    overlay.classList.remove("hidden");
    const start = (opts.startPath || "").trim();
    if (start) {
      navigate(start);
    } else {
      showRoots();
    }
    return new Promise((resolve) => { resolvePromise = resolve; });
  }

  function buildExtFilter() {
    const sel = $(".fb-ext");
    sel.innerHTML = "";
    const exts = opts.mode === "dir" ? null : (opts.extensions || []);
    if (!exts || exts.length === 0) {
      sel.classList.add("hidden");
      return;
    }
    for (const ext of exts) {
      const opt = document.createElement("option");
      opt.value = ext;
      opt.textContent = "*" + ext;
      sel.appendChild(opt);
    }
    const all = document.createElement("option");
    all.value = "";
    all.textContent = t("fb.allFiles");
    sel.appendChild(all);
    // defaultAll：默认不过滤（如 Linux 下可执行文件无扩展名）
    sel.value = opts.defaultAll ? "" : exts[0];
    extFilter = sel.value;
    sel.classList.remove("hidden");
  }

  /* ---------- 导航 ---------- */
  async function loadRoots() {
    const bar = $(".fb-roots");
    bar.innerHTML = "";
    let roots = [];
    try {
      const res = await fetch("/api/fs/roots");
      roots = await res.json();
    } catch (e) {
      return;
    }
    for (const r of roots) {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "fb-root-chip";
      chip.textContent = r.name;
      chip.onclick = () => navigate(r.path);
      bar.appendChild(chip);
    }
  }

  function showRoots() {
    cwd = "";
    cwdParent = "";
    entries = [];
    selectedPath = null;
    $(".fb-path").value = "";
    renderList();
    setStatus(t("fb.selectRoot"));
  }

  function goUp() {
    if (cwdParent) {
      navigate(cwdParent);
    } else {
      showRoots();
    }
  }

  async function navigate(path) {
    if (!path) { showRoots(); return; }
    setStatus(t("fb.loading"));
    try {
      const res = await fetch("/api/fs/list?path=" + encodeURIComponent(path));
      const text = await res.text();
      if (!res.ok) {
        // 输入的是文件路径：跳到其父目录并选中该文件
        const parent = parentOf(path);
        if (parent && parent !== path) {
          await navigateInto(parent, path);
          return;
        }
        setStatus(t("fb.cannotOpen", { msg: I18N.errText(text) }));
        return;
      }
      const data = JSON.parse(text);
      cwd = data.path;
      cwdParent = data.parent || "";
      entries = data.entries || [];
      selectedPath = null;
      $(".fb-path").value = cwd;
      renderList();
      setStatus("");
    } catch (e) {
      setStatus(t("fb.loadFailed", { msg: e.message || e }));
    }
  }

  /* 进入目录并选中指定条目（用于“输入完整文件路径后跳转”） */
  async function navigateInto(dirPath, selectPath) {
    const res = await fetch("/api/fs/list?path=" + encodeURIComponent(dirPath));
    const text = await res.text();
    if (!res.ok) { setStatus(t("fb.cannotOpen", { msg: I18N.errText(text) })); return; }
    const data = JSON.parse(text);
    cwd = data.path;
    cwdParent = data.parent || "";
    entries = data.entries || [];
    $(".fb-path").value = cwd;
    const hit = entries.find(e => e.path === selectPath);
    selectedPath = hit ? hit.path : null;
    selectedIsDir = hit ? hit.dir : false;
    renderList();
    setStatus(hit ? "" : t("fb.notInDir", { path: selectPath }));
  }

  /* 去掉末级路径段（同时兼容 / 与 \ 分隔符） */
  function parentOf(path) {
    const trimmed = path.replace(/[\\/]+$/, "");
    const idx = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
    if (idx < 0) return "";
    // 末级直接挂在根下：/file → /；\file → \
    if (idx === 0) return trimmed.charAt(0);
    const parent = trimmed.substring(0, idx);
    // Windows 盘符根：C: → C:\
    return /^[A-Za-z]:$/.test(parent) ? parent + "\\" : parent;
  }

  /* ---------- 列表渲染 ---------- */
  function visibleEntries() {
    const kw = $(".fb-search").value.trim().toLowerCase();
    return entries.filter(e => {
      if (!showHidden && e.hidden) return false;
      if (kw && !e.name.toLowerCase().includes(kw)) return false;
      if (!e.dir && extFilter && e.ext !== extFilter) return false;
      return true;
    });
  }

  function renderList() {
    const list = $(".fb-list");
    list.innerHTML = "";
    if (!cwd) {
      list.innerHTML = `<div class="hint fb-empty">${t("fb.empty")}</div>`;
      updateFooter();
      return;
    }
    const rows = visibleEntries();
    if (rows.length === 0) {
      list.innerHTML = `<div class="hint fb-empty">${t("fb.noMatch")}</div>`;
      updateFooter();
      return;
    }
    for (const e of rows) {
      const row = document.createElement("div");
      row.className = "fb-row" + (e.path === selectedPath ? " selected" : "");
      const icon = e.dir ? "📁" : "📄";
      const size = e.dir ? "" : formatSize(e.size);
      row.innerHTML = `<span class="fb-icon">${icon}</span>
        <span class="fb-name"></span>
        <span class="fb-size">${size}</span>
        <span class="fb-mtime">${e.mtime || ""}</span>`;
      row.querySelector(".fb-name").textContent = e.name;
      row.title = e.path;
      row.onclick = () => onRowClick(e);
      row.ondblclick = () => onRowDblClick(e);
      list.appendChild(row);
    }
    updateFooter();
  }

  function onRowClick(e) {
    if (e.dir) {
      if (opts.mode === "dir") {
        select(e);
      } else {
        navigate(e.path);
      }
    } else if (opts.mode !== "dir") {
      select(e);
    }
  }

  function onRowDblClick(e) {
    if (e.dir) {
      navigate(e.path);
    } else if (opts.mode !== "dir") {
      finish(e.path);
    }
  }

  function select(e) {
    selectedPath = e.path;
    selectedIsDir = e.dir;
    overlay.querySelectorAll(".fb-row").forEach(r => r.classList.remove("selected"));
    const rows = overlay.querySelectorAll(".fb-row");
    visibleEntries().forEach((item, i) => {
      if (item.path === e.path && rows[i]) rows[i].classList.add("selected");
    });
    updateFooter();
  }

  function updateFooter() {
    const selEl = $(".fb-selection");
    const confirm = $(".fb-confirm");
    if (selectedPath) {
      selEl.textContent = selectedPath;
      selEl.title = selectedPath;
      confirm.disabled = opts.mode === "dir" ? !selectedIsDir : selectedIsDir;
    } else {
      selEl.textContent = opts.mode === "dir" ? t("fb.noSelectionDir") : t("fb.noSelectionFile");
      selEl.title = "";
      confirm.disabled = true;
    }
    $(".fb-pick-current").disabled = !cwd;
  }

  function confirmSelection() {
    if (!selectedPath) return;
    if (opts.mode === "dir" && !selectedIsDir) return;
    if (opts.mode !== "dir" && selectedIsDir) return;
    finish(selectedPath);
  }

  /* ---------- 新建文件夹 ---------- */
  async function mkdir() {
    if (!cwd) { setStatus(t("fb.needDir")); return; }
    const name = window.prompt(t("fb.mkdirPrompt"));
    if (name == null) return;
    try {
      const res = await fetch("/api/fs/mkdir", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ parent: cwd, name: name.trim() })
      });
      const text = await res.text();
      if (!res.ok) { setStatus(t("fb.mkdirFailed", { msg: I18N.errText(text) })); return; }
      navigate(cwd);
    } catch (e) {
      setStatus(t("fb.mkdirFailed", { msg: e.message || e }));
    }
  }

  /* ---------- 收尾 ---------- */
  function finish(path) {
    close();
    if (resolvePromise) resolvePromise(path);
    resolvePromise = null;
  }

  function cancel() {
    finish(null);
  }

  function close() {
    if (overlay) overlay.classList.add("hidden");
  }

  function setStatus(text) {
    if (text) {
      let el = $(".fb-status");
      if (!el) {
        el = document.createElement("div");
        el.className = "hint fb-status";
        overlay.querySelector(".fb-modal").insertBefore(el, $(".fb-footer"));
      }
      el.textContent = text;
    } else {
      const el = $(".fb-status");
      if (el) el.remove();
    }
  }

  /* ---------- 语言切换：重设所有静态文案（由 app.js 统一调用） ---------- */
  function relocalize() {
    if (!overlay) return;
    opts = opts || {};
    $(".fb-title").textContent = opts.title || (opts.mode === "dir" ? t("fb.titleDir") : t("fb.titleFile"));
    const up = $(".fb-up");
    up.textContent = t("fb.up");
    up.title = t("fb.upTitle");
    $(".fb-path").placeholder = t("fb.pathPlaceholder");
    $(".fb-go").textContent = t("fb.go");
    $(".fb-refresh").title = t("fb.refreshTitle");
    $(".fb-search").placeholder = t("fb.searchPlaceholder");
    const hiddenLabel = $(".fb-hidden-toggle");
    hiddenLabel.childNodes.forEach(n => {
      if (n.nodeType === Node.TEXT_NODE) n.textContent = " " + t("fb.showHidden");
    });
    $(".fb-mkdir").textContent = t("fb.mkdir");
    $(".fb-pick-current").textContent = t("fb.pickCurrent");
    $(".fb-cancel").textContent = t("fb.cancel");
    $(".fb-confirm").textContent = t("fb.confirm");
    // 重建扩展名下拉（buildExtFilter 会重置选中值，先记录再恢复）
    const prevExt = extFilter;
    buildExtFilter();
    const extSel = $(".fb-ext");
    if (!extSel.classList.contains("hidden")) {
      extSel.value = prevExt;
      extFilter = extSel.value;
    }
    // footer 的 selection 提示：无选中时按 mode 重填
    if (!selectedPath) {
      $(".fb-selection").textContent = opts.mode === "dir" ? t("fb.noSelectionDir") : t("fb.noSelectionFile");
    }
    // status 提示是旧语言文本，直接移除
    const status = $(".fb-status");
    if (status) status.remove();
  }

  function formatSize(bytes) {
    if (bytes == null) return "";
    if (window.WavUtil && WavUtil.formatSize) return WavUtil.formatSize(bytes);
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + " MB";
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + " GB";
  }

  return { open, isOpen, cancel, relocalize };
})();
