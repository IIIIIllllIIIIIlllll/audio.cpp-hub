package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// 下载管理器：多线程分段下载 + 断点续传 + 进度统计（移植自 Java 版 DownloadManager）。
// 任务状态落盘 data/downloads/<id>/task.json（原子写，约 1s 节流 + 状态迁移时）；
// 权重落盘 <modelsDir>/<targetDir>/，下载中的文件带 .part 后缀，完成校验后改名。
// 分段用 HTTP Range 请求 + os.File.WriteAt 写指定偏移（天然替代 Java 的 FileChannel）；
// 崩溃恢复时按 .part 实际大小收敛各分段进度（persisted done 一定对应已写入的字节，
// clamp 只损失少量进度，不会写坏文件）。远端不支持 Range 或大小未知的文件退化为
// 整流下载，中断后该文件从头重下。
// 与 Java 版的差异：暂停/取消不用异常，用每轮运行的 context cancel + runGeneration 代次。

const (
	dlChunkSize        = 64 * 1024
	dlSegmentMin       = 32 * 1024 * 1024 // 单分段最小字节数：小于该值不分段
	dlMaxRetry         = 6                // 分段失败重试次数，退避 1s 翻倍封顶 15s
	dlPersistInterval  = time.Second
	dlShutdownWaitSecs = 6
)

// 任务状态（字符串与 Java 版枚举一致，task.json 可直接互读）。
const (
	dlStatusPending = "PENDING"
	dlStatusRunning = "RUNNING"
	dlStatusPaused  = "PAUSED"
	dlStatusDone    = "DONE"
	dlStatusFailed  = "FAILED"
)

// 控制信号：暂停（进度已落盘可续传）与取消（删除/被新 runner 接替，安静退出）。
var (
	errDlPaused    = errors.New("下载已暂停")
	errDlCancelled = errors.New("下载已取消")
)

// dlSegment 下载分段：[Start, End] 闭区间；End=-1 表示远端未给大小的整流下载。
type dlSegment struct {
	Start int64 `json:"start"`
	End   int64 `json:"end"`
	Done  int64 `json:"done"` // 原子访问
}

// dlFileEntry 单个待下载文件。
type dlFileEntry struct {
	Path          string      `json:"path"` // 相对目标目录的路径（统一用 / 分隔）
	URL           string      `json:"url"`
	Size          int64       `json:"size"` // 字节数，-1 表示未知
	SupportsRange bool        `json:"supportsRange"`
	Completed     bool        `json:"completed"` // 改名落盘完成后置 true，续传时跳过
	Segments      []dlSegment `json:"segments"`
}

// dlFileRequest 创建任务的文件请求项。
type dlFileRequest struct {
	URL  string
	Path string
}

// DownloadTask 下载任务数据模型，Gson 兼容的 JSON 直接落盘 task.json
// （token 明文存储，与 hub.config.json 存密钥库密码同一级别；API 输出会剔除 token）。
type DownloadTask struct {
	ID        string         `json:"id"`
	TargetDir string         `json:"targetDir"`
	ModelID   string         `json:"modelId,omitempty"`
	PackageID string         `json:"packageId,omitempty"`
	Source    string         `json:"source,omitempty"` // 下载源：hf / modelscope（显式 files 任务为空）
	Status    string         `json:"status"`
	Error     string         `json:"error,omitempty"`
	Token     string         `json:"token,omitempty"`
	CreatedAt int64          `json:"createdAt"`
	UpdatedAt int64          `json:"updatedAt"`
	Files     []*dlFileEntry `json:"files"`

	// ---- 以下为运行态字段，不落盘（原子访问）----
	pauseRequested  int32 `json:"-"` // 请求暂停（含进程退出），worker 在块边界响应
	cancelRequested int32 `json:"-"` // 请求取消（delete）
	runGeneration   int32 `json:"-"` // 运行代次：resume/删除时递增，旧 worker 据此自杀
	lastPersistAt   int64 `json:"-"`
	speedBps        int64 `json:"-"`
	speedSampleAt   int64 `json:"-"`
	speedSampleBytes int64 `json:"-"`
	// 本轮运行的 context：暂停/取消/失败时 cancel，进行中的 HTTP 读立即中断
	runCtx context.Context    `json:"-"`
	cancel context.CancelFunc `json:"-"`
}

// totalBytes 已知大小文件的总字节数（未知大小的文件不计）。
func (t *DownloadTask) totalBytes() int64 {
	var total int64
	for _, f := range t.Files {
		if f.Size > 0 {
			total += f.Size
		}
	}
	return total
}

// downloadedBytes 已下载字节数（各分段 done 求和）。
func (t *DownloadTask) downloadedBytes() int64 {
	var total int64
	for _, f := range t.Files {
		for i := range f.Segments {
			total += atomic.LoadInt64(&f.Segments[i].Done)
		}
	}
	return total
}

func (t *DownloadTask) completedFiles() int {
	n := 0
	for _, f := range t.Files {
		if f.Completed {
			n++
		}
	}
	return n
}

// ------------------------------------------------------------------ 校验

var dlTargetDirRe = regexp.MustCompile(`^[a-zA-Z0-9._-]{1,64}$`)

// validateDlTargetDir 校验目标目录名；纯 "."/".." 这类无字母数字的名字一并拒绝。
func validateDlTargetDir(targetDir string) (string, error) {
	ok := dlTargetDirRe.MatchString(targetDir) &&
		strings.ContainsAny(targetDir, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
	if !ok {
		return "", &UserError{Code: "INVALID_TARGET_DIR",
			Params: map[string]any{"targetDir": targetDir}, Msg: "非法目标目录名: " + targetDir}
	}
	return targetDir, nil
}

// validateDlFilePath 校验并规范化文件相对路径：防路径穿越，统一为 / 分隔。
func validateDlFilePath(raw string) (string, error) {
	p := strings.ReplaceAll(strings.TrimSpace(raw), "\\", "/")
	ok := p != "" && len(p) <= 256 && !strings.HasPrefix(p, "/") && !strings.Contains(p, ":")
	if ok {
		for _, seg := range strings.Split(p, "/") {
			if seg == "" || seg == "." || seg == ".." {
				ok = false
				break
			}
		}
	}
	if !ok {
		return "", &UserError{Code: "INVALID_FILE_PATH",
			Params: map[string]any{"path": raw}, Msg: "非法文件路径: " + raw}
	}
	return p, nil
}

// validateDlURL 校验下载地址（仅 http/https）。
func validateDlURL(raw string) (string, error) {
	if !strings.HasPrefix(raw, "http://") && !strings.HasPrefix(raw, "https://") {
		return "", &UserError{Code: "INVALID_URL",
			Params: map[string]any{"url": raw}, Msg: "非法下载地址: " + raw}
	}
	return raw, nil
}

// dlHTTPError HTTP 状态码到用户错误的映射：401/403→授权，404→远端不存在，其它→访问失败。
func dlHTTPError(code int, path string) *UserError {
	switch {
	case code == 401 || code == 403:
		return &UserError{Code: "DOWNLOAD_AUTH", Params: map[string]any{"path": path, "status": code},
			Msg: "下载需要授权（gated 仓库请提供 HF token）: " + path}
	case code == 404:
		return &UserError{Code: "REMOTE_NOT_FOUND", Params: map[string]any{"path": path},
			Msg: "远端文件不存在: " + path}
	default:
		return &UserError{Code: "HEAD_FAILED", Params: map[string]any{"path": path, "status": code},
			Msg: fmt.Sprintf("下载地址返回 HTTP %d: %s", code, path)}
	}
}

// ------------------------------------------------------------------ 管理器

// DownloadManager 单例，由 main 创建并注入 Hub。
type DownloadManager struct {
	modelsDir       string
	stateDir        string
	segmentsPerFile int
	httpClient      *http.Client
	sem             chan struct{} // 全局分段并发限制（downloadThreads）
	shutdownFlag    int32         // 原子

	mu    sync.Mutex
	tasks map[string]*DownloadTask
	order []*DownloadTask // 创建顺序，列表输出反转（新→旧）
	wg    sync.WaitGroup  // runner goroutine 计数，退出时等待
}

// NewDownloadManager 创建下载管理器并回放 data/downloads/ 下未完成任务（自动续传）。
func NewDownloadManager(cfg HubConfig) *DownloadManager {
	modelsDir, err := filepath.Abs(cfg.ModelsDir)
	if err != nil {
		modelsDir = cfg.ModelsDir
	}
	threads := cfg.DownloadThreads
	if threads < 1 {
		threads = 1
	}
	segments := cfg.DownloadSegmentsPerFile
	if segments < 1 {
		segments = 1
	}
	m := &DownloadManager{
		modelsDir:       modelsDir,
		stateDir:        filepath.Join("data", "downloads"),
		segmentsPerFile: segments,
		sem:             make(chan struct{}, threads),
		tasks:           map[string]*DownloadTask{},
		httpClient: &http.Client{
			// 跟随重定向（modelscope 会 302 到 CDN）；不设整体超时（大文件下载数小时正常），
			// 中断靠 request context
			Transport: &http.Transport{
				DialContext:           (&net.Dialer{Timeout: 30 * time.Second}).DialContext,
				MaxIdleConns:          64,
				MaxIdleConnsPerHost:   32,
				IdleConnTimeout:       90 * time.Second,
				TLSHandshakeTimeout:   30 * time.Second,
				ResponseHeaderTimeout: 0,
			},
		},
	}
	if err := os.MkdirAll(m.modelsDir, 0755); err != nil {
		log.Printf("模型目录创建失败: %v", err)
	}
	if err := os.MkdirAll(m.stateDir, 0755); err != nil {
		log.Printf("下载状态目录创建失败: %v", err)
	}
	m.loadAll()
	return m
}

// ------------------------------------------------------------------ 任务生命周期

// Create 创建下载任务并立即开始：校验 → 并行探测大小/Range → 分段 → 磁盘空间检查 →
// 落盘 → 启动。用户可预期错误返回 *UserError。modelID/packageID/source 仅作来源记录（可空）。
func (m *DownloadManager) Create(targetDir string, files []dlFileRequest, token string,
	overwrite bool, modelID, packageID, source string) (map[string]any, error) {
	if _, err := validateDlTargetDir(targetDir); err != nil {
		return nil, err
	}
	if len(files) == 0 {
		return nil, &UserError{Code: "FILES_REQUIRED", Params: map[string]any{}, Msg: "files 不能为空"}
	}
	m.mu.Lock()
	for _, t := range m.order {
		if t.TargetDir == targetDir && dlActive(t.Status) {
			m.mu.Unlock()
			return nil, &UserError{Code: "DOWNLOAD_EXISTS",
				Params: map[string]any{"targetDir": targetDir, "id": t.ID},
				Msg:    "该目录已有进行中的下载任务: " + targetDir}
		}
	}
	m.mu.Unlock()

	seen := map[string]bool{}
	entries := make([]*dlFileEntry, 0, len(files))
	for _, fr := range files {
		u, err := validateDlURL(fr.URL)
		if err != nil {
			return nil, err
		}
		p, err := validateDlFilePath(fr.Path)
		if err != nil {
			return nil, err
		}
		if seen[p] {
			return nil, &UserError{Code: "DUPLICATE_FILE",
				Params: map[string]any{"path": p}, Msg: "重复文件: " + p}
		}
		seen[p] = true
		entries = append(entries, &dlFileEntry{Path: p, URL: u, Size: -1})
	}
	if err := m.probe(entries, token); err != nil {
		return nil, err
	}
	var need int64
	for _, e := range entries {
		e.Segments = m.buildSegments(e.Size, e.SupportsRange)
		if e.Size > 0 {
			need += e.Size
		}
	}
	if usable, ok := diskUsableSpace(m.modelsDir); ok {
		if need > 0 && float64(need)*1.05 > float64(usable) {
			return nil, &UserError{Code: "DISK_SPACE",
				Params: map[string]any{"need": need, "usable": usable},
				Msg:    fmt.Sprintf("磁盘空间不足：需要约 %d 字节，可用 %d 字节", need, usable)}
		}
	}
	for _, e := range entries {
		final := m.finalPath(targetDir, e.Path)
		if pathExists(final) {
			if !overwrite {
				return nil, &UserError{Code: "FILE_EXISTS",
					Params: map[string]any{"path": e.Path},
					Msg:    "目标文件已存在（如需重下请设置 overwrite）: " + e.Path}
			}
			os.Remove(final)
		}
		os.Remove(final + ".part")
	}

	t := &DownloadTask{
		ID:        newID(),
		TargetDir: targetDir,
		ModelID:   modelID,
		PackageID: packageID,
		Source:    source,
		Status:    dlStatusRunning,
		Token:     token,
		CreatedAt: time.Now().UnixMilli(),
		Files:     entries,
	}
	t.UpdatedAt = t.CreatedAt
	t.speedSampleBytes = -1

	m.mu.Lock()
	defer m.mu.Unlock()
	// 探测耗时较长，重新检查同目录任务（防并发创建）
	for _, o := range m.order {
		if o.TargetDir == targetDir && dlActive(o.Status) {
			return nil, &UserError{Code: "DOWNLOAD_EXISTS",
				Params: map[string]any{"targetDir": targetDir, "id": o.ID},
				Msg:    "该目录已有进行中的下载任务: " + targetDir}
		}
	}
	m.tasks[t.ID] = t
	m.order = append(m.order, t)
	m.persistLocked(t)
	m.startRunnerLocked(t)
	log.Printf("创建下载任务: %s -> %s (%d 个文件)", t.ID, targetDir, len(entries))
	return m.detailLocked(t), nil
}

// Pause 暂停任务（进行中的分段在块边界退出，进度落盘）。
func (m *DownloadManager) Pause(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, err := m.requireTaskLocked(id)
	if err != nil {
		return err
	}
	if t.Status != dlStatusRunning && t.Status != dlStatusPending {
		return &UserError{Code: "DOWNLOAD_STATE", Params: map[string]any{"status": t.Status},
			Msg: "任务不在进行中，无法暂停: " + t.Status}
	}
	atomic.StoreInt32(&t.pauseRequested, 1)
	t.Status = dlStatusPaused
	m.persistLocked(t)
	if t.cancel != nil {
		t.cancel() // 进行中的 HTTP 读立即中断
	}
	return nil
}

// Resume 继续任务（PAUSED/FAILED 均可重新排队，从各分段断点继续）。
func (m *DownloadManager) Resume(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, err := m.requireTaskLocked(id)
	if err != nil {
		return err
	}
	if t.Status != dlStatusPaused && t.Status != dlStatusFailed {
		return &UserError{Code: "DOWNLOAD_STATE", Params: map[string]any{"status": t.Status},
			Msg: "任务当前状态无法继续: " + t.Status}
	}
	atomic.StoreInt32(&t.pauseRequested, 0)
	t.Error = ""
	t.Status = dlStatusRunning
	m.persistLocked(t)
	m.startRunnerLocked(t)
	return nil
}

// Delete 取消并移除任务；purge=true 时删除残留的 .part（不动已完成改名的权重文件）。
func (m *DownloadManager) Delete(id string, purge bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, err := m.requireTaskLocked(id)
	if err != nil {
		return err
	}
	atomic.StoreInt32(&t.cancelRequested, 1)
	atomic.AddInt32(&t.runGeneration, 1)
	if t.cancel != nil {
		t.cancel()
	}
	delete(m.tasks, id)
	for i, o := range m.order {
		if o == t {
			m.order = append(m.order[:i], m.order[i+1:]...)
			break
		}
	}
	if purge && t.Status != dlStatusDone {
		for _, f := range t.Files {
			m.deleteWithRetry(m.finalPath(t.TargetDir, f.Path) + ".part")
		}
	}
	dir := filepath.Join(m.stateDir, id)
	m.deleteWithRetry(filepath.Join(dir, "task.json"))
	m.deleteWithRetry(filepath.Join(dir, "task.json.tmp"))
	m.deleteWithRetry(dir)
	log.Printf("删除下载任务: %s (purge=%v)", id, purge)
	return nil
}

// List 全部任务简要列表（新→旧），附带进度与速率。
func (m *DownloadManager) List() []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]map[string]any, 0, len(m.order))
	for i := len(m.order) - 1; i >= 0; i-- {
		t := m.order[i]
		m.sampleSpeed(t)
		o := map[string]any{
			"id":              t.ID,
			"targetDir":       t.TargetDir,
			"status":          t.Status,
			"createdAt":       t.CreatedAt,
			"updatedAt":       t.UpdatedAt,
			"fileCount":       len(t.Files),
			"completedFiles":  t.completedFiles(),
			"totalBytes":      t.totalBytes(),
			"downloadedBytes": t.downloadedBytes(),
			"percent":         dlPercent(t),
			"speedBps":        atomic.LoadInt64(&t.speedBps),
		}
		if t.ModelID != "" {
			o["modelId"] = t.ModelID
		}
		if t.Source != "" {
			o["source"] = t.Source
		}
		if t.Error != "" {
			o["error"] = t.Error
		}
		out = append(out, o)
	}
	return out
}

// Get 单任务详情（含 files/segments；剔除 token）。
func (m *DownloadManager) Get(id string) (map[string]any, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, err := m.requireTaskLocked(id)
	if err != nil {
		return nil, err
	}
	return m.detailLocked(t), nil
}

// Shutdown 进程退出：进行中的任务转暂停（进度落盘，下次启动自动续传），
// 等分段 goroutine 退出，超时强制返回。
func (m *DownloadManager) Shutdown() {
	atomic.StoreInt32(&m.shutdownFlag, 1)
	m.mu.Lock()
	for _, t := range m.order {
		if t.Status == dlStatusRunning || t.Status == dlStatusPending {
			atomic.StoreInt32(&t.pauseRequested, 1)
			if t.cancel != nil {
				t.cancel()
			}
		}
	}
	m.mu.Unlock()
	done := make(chan struct{})
	go func() {
		m.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(dlShutdownWaitSecs * time.Second):
		log.Printf("等待下载任务退出超时，强制退出")
	}
}

// ------------------------------------------------------------------ 运行循环

// startRunnerLocked 启动该任务的运行 goroutine（调用方需持有 m.mu）。
func (m *DownloadManager) startRunnerLocked(t *DownloadTask) {
	gen := atomic.AddInt32(&t.runGeneration, 1)
	t.runCtx, t.cancel = context.WithCancel(context.Background())
	m.wg.Add(1)
	go m.runTask(t, gen, t.runCtx)
}

func (m *DownloadManager) runTask(t *DownloadTask, gen int32, ctx context.Context) {
	defer m.wg.Done()
	defer func() {
		atomic.StoreInt64(&t.speedBps, 0)
		atomic.StoreInt64(&t.speedSampleBytes, -1)
	}()
	err := m.runTaskInner(t, gen, ctx)
	// context 中断导致的杂散错误统一归一到暂停/取消语义
	if err != nil && !errors.Is(err, errDlPaused) && !errors.Is(err, errDlCancelled) {
		if ferr := m.checkFlags(t, gen); ferr != nil {
			err = ferr
		}
	}
	switch {
	case err == nil:
		m.mu.Lock()
		if atomic.LoadInt32(&t.runGeneration) != gen {
			m.mu.Unlock()
			return
		}
		t.Status = dlStatusDone
		t.Error = ""
		m.persistLocked(t)
		m.mu.Unlock()
		log.Printf("下载完成: %s -> %s", t.ID, t.TargetDir)
	case errors.Is(err, errDlPaused):
		m.mu.Lock()
		if t.Status == dlStatusRunning || t.Status == dlStatusPending {
			t.Status = dlStatusPaused
			m.persistLocked(t)
		}
		m.mu.Unlock()
	case errors.Is(err, errDlCancelled):
		// 任务已被删除或被新一轮 runner 接替，安静退出
	default:
		m.mu.Lock()
		// 递增代次并 cancel，让残余分段 goroutine 尽快退出
		atomic.AddInt32(&t.runGeneration, 1)
		if t.cancel != nil {
			t.cancel()
		}
		t.Status = dlStatusFailed
		t.Error = summarize(err.Error())
		m.persistLocked(t)
		m.mu.Unlock()
		log.Printf("下载任务失败 %s: %v", t.ID, err)
	}
}

func (m *DownloadManager) runTaskInner(t *DownloadTask, gen int32, ctx context.Context) error {
	m.reconcile(t)
	for _, f := range t.Files {
		if err := m.checkFlags(t, gen); err != nil {
			return err
		}
		if f.Completed {
			continue
		}
		if err := m.downloadFile(t, f, gen, ctx); err != nil {
			return err
		}
	}
	return nil
}

// reconcile 崩溃恢复：按 .part 实际大小收敛各分段 done。
// persisted done 一定对应已写入字节；clamp 只损失未落盘的少量进度。
func (m *DownloadManager) reconcile(t *DownloadTask) {
	for _, f := range t.Files {
		if f.Completed || len(f.Segments) == 0 {
			continue
		}
		var partSize int64
		if st, err := os.Stat(m.finalPath(t.TargetDir, f.Path) + ".part"); err == nil {
			partSize = st.Size()
		}
		for i := range f.Segments {
			seg := &f.Segments[i]
			if seg.End < 0 {
				atomic.StoreInt64(&seg.Done, 0)
				continue
			}
			done := atomic.LoadInt64(&seg.Done)
			if max := partSize - seg.Start; done > max {
				done = max
			}
			if done < 0 {
				done = 0
			}
			atomic.StoreInt64(&seg.Done, done)
		}
	}
}

func (m *DownloadManager) downloadFile(t *DownloadTask, f *dlFileEntry, gen int32, ctx context.Context) error {
	final := m.finalPath(t.TargetDir, f.Path)
	if parent := filepath.Dir(final); parent != "" {
		if err := os.MkdirAll(parent, 0755); err != nil {
			return err
		}
	}
	part := final + ".part"
	if f.Size > 0 {
		if st, err := os.Stat(final); err == nil && st.Mode().IsRegular() && st.Size() == f.Size {
			// 上次在改名后、落盘前崩溃：直接判定完成
			f.Completed = true
			os.Remove(part)
			m.mu.Lock()
			m.persistLocked(t)
			m.mu.Unlock()
			return nil
		}
	}
	if len(f.Segments) == 1 && f.Segments[0].End < 0 {
		return m.streamFile(t, f, part, final, gen, ctx)
	}
	ch, err := os.OpenFile(part, os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	// 同文件分段并发下载；任一分段失败即取消本文件其余分段
	fctx, fcancel := context.WithCancel(ctx)
	defer fcancel()
	var wg sync.WaitGroup
	results := make(chan error, len(f.Segments))
	n := 0
	for i := range f.Segments {
		seg := &f.Segments[i]
		if atomic.LoadInt64(&seg.Done) >= seg.End-seg.Start+1 {
			continue
		}
		n++
		wg.Add(1)
		go func() {
			defer wg.Done()
			m.sem <- struct{}{}
			defer func() { <-m.sem }()
			results <- m.downloadSegment(t, f, seg, ch, gen, fctx)
		}()
	}
	wg.Wait()
	ch.Close()
	close(results)
	var firstErr error
	for err := range results {
		if err == nil {
			continue
		}
		if firstErr == nil || (errors.Is(firstErr, context.Canceled) && !errors.Is(err, context.Canceled)) {
			firstErr = err
		}
		fcancel()
	}
	if firstErr != nil {
		return firstErr
	}
	var total int64
	for i := range f.Segments {
		total += atomic.LoadInt64(&f.Segments[i].Done)
	}
	if f.Size > 0 && total != f.Size {
		return fmt.Errorf("分段字节总量与预期不符: %d != %d", total, f.Size)
	}
	if err := movePartFile(part, final); err != nil {
		return err
	}
	f.Completed = true
	m.mu.Lock()
	m.persistLocked(t)
	m.mu.Unlock()
	return nil
}

// downloadSegment 分段 worker：Range 请求 + 64KB 块 WriteAt 指定偏移，失败退避重试。
func (m *DownloadManager) downloadSegment(t *DownloadTask, f *dlFileEntry, seg *dlSegment,
	ch *os.File, gen int32, ctx context.Context) error {
	for attempt := 1; ; attempt++ {
		if err := m.checkFlags(t, gen); err != nil {
			return err
		}
		pos := seg.Start + atomic.LoadInt64(&seg.Done)
		if pos > seg.End {
			return nil
		}
		err := m.fetchSegment(t, f, seg, ch, pos, gen, ctx)
		if err == nil {
			return nil
		}
		if ferr := m.checkFlags(t, gen); ferr != nil {
			return ferr
		}
		if errors.Is(err, context.Canceled) {
			return err // 同文件其它分段失败触发的取消
		}
		if attempt >= dlMaxRetry {
			return fmt.Errorf("分段下载失败（重试 %d 次）: %s: %w", dlMaxRetry, f.Path, err)
		}
		if berr := dlSleepBackoff(ctx, attempt); berr != nil {
			if ferr := m.checkFlags(t, gen); ferr != nil {
				return ferr
			}
			return berr
		}
	}
}

// fetchSegment 执行一次分段请求：从 pos 续传到 seg.End。
func (m *DownloadManager) fetchSegment(t *DownloadTask, f *dlFileEntry, seg *dlSegment,
	ch *os.File, pos int64, gen int32, ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.URL, nil)
	if err != nil {
		return err
	}
	if t.Token != "" {
		req.Header.Set("Authorization", "Bearer "+t.Token)
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", pos, seg.End))
	resp, err := m.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return dlHTTPError(resp.StatusCode, f.Path)
	}
	// 服务器忽略 Range 返回 200：仅全文件起点可接受，否则写入位置会错。
	// 注意 modelscope 的怪癖：分段请求回 200 但带 Content-Range，校验起始偏移后视为正常分段响应
	if resp.StatusCode == http.StatusOK && pos != 0 {
		if start, ok := parseContentRangeStart(resp.Header.Get("Content-Range")); !ok || start != pos {
			return fmt.Errorf("服务器忽略了 Range 请求: %s", f.Path)
		}
	}
	buf := make([]byte, dlChunkSize)
	for {
		if ferr := m.checkFlags(t, gen); ferr != nil {
			return ferr
		}
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := ch.WriteAt(buf[:n], pos); werr != nil {
				return werr
			}
			pos += int64(n)
			atomic.StoreInt64(&seg.Done, pos-seg.Start)
			m.maybePersist(t)
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	if pos <= seg.End {
		return fmt.Errorf("响应体提前结束: %s", f.Path)
	}
	return nil
}

// streamFile 整流下载（远端无大小/不支持 Range）：TRUNCATE 重写，中断后该文件从头重下。
func (m *DownloadManager) streamFile(t *DownloadTask, f *dlFileEntry, part, final string,
	gen int32, ctx context.Context) error {
	seg := &f.Segments[0]
	for attempt := 1; ; attempt++ {
		if err := m.checkFlags(t, gen); err != nil {
			return err
		}
		atomic.StoreInt64(&seg.Done, 0)
		err := m.fetchStream(t, f, seg, part, gen, ctx)
		if err == nil {
			break
		}
		if ferr := m.checkFlags(t, gen); ferr != nil {
			return ferr
		}
		if attempt >= dlMaxRetry {
			return fmt.Errorf("整流下载失败（重试 %d 次）: %s: %w", dlMaxRetry, f.Path, err)
		}
		if berr := dlSleepBackoff(ctx, attempt); berr != nil {
			if ferr := m.checkFlags(t, gen); ferr != nil {
				return ferr
			}
			return berr
		}
	}
	if f.Size > 0 && atomic.LoadInt64(&seg.Done) != f.Size {
		return fmt.Errorf("文件大小不符: %d != %d", atomic.LoadInt64(&seg.Done), f.Size)
	}
	if err := movePartFile(part, final); err != nil {
		return err
	}
	f.Completed = true
	m.mu.Lock()
	m.persistLocked(t)
	m.mu.Unlock()
	return nil
}

// fetchStream 执行一次整流请求（不带 Range）。
func (m *DownloadManager) fetchStream(t *DownloadTask, f *dlFileEntry, seg *dlSegment,
	part string, gen int32, ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.URL, nil)
	if err != nil {
		return err
	}
	if t.Token != "" {
		req.Header.Set("Authorization", "Bearer "+t.Token)
	}
	resp, err := m.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return dlHTTPError(resp.StatusCode, f.Path)
	}
	out, err := os.OpenFile(part, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	defer out.Close()
	var pos int64
	buf := make([]byte, dlChunkSize)
	for {
		if ferr := m.checkFlags(t, gen); ferr != nil {
			return ferr
		}
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := out.Write(buf[:n]); werr != nil {
				return werr
			}
			pos += int64(n)
			atomic.StoreInt64(&seg.Done, pos)
			m.maybePersist(t)
		}
		if readErr == io.EOF {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}

// ------------------------------------------------------------------ 探测与分段

// probe 并行探测每个文件的大小与 Range 支持：先 HEAD；HEAD 成功但拿不到
// Content-Length 时（modelscope 的坑）回退 GET + Range: bytes=0-0，从 206 响应的
// Content-Range 解析总大小。失败返回 *UserError。
func (m *DownloadManager) probe(entries []*dlFileEntry, token string) error {
	var wg sync.WaitGroup
	errs := make([]error, len(entries))
	for i := range entries {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = m.probeOne(entries[i], token)
		}()
	}
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			return err
		}
	}
	return nil
}

func (m *DownloadManager) probeOne(e *dlFileEntry, token string) error {
	size, supportsRange, err := m.probeHead(e, token)
	if err != nil {
		return err
	}
	if size >= 0 {
		e.Size = size
		e.SupportsRange = supportsRange
		return nil
	}
	// HEAD 未给大小：回退 GET Range bytes=0-0（GET 跟随重定向，modelscope 302 到 CDN 后正常支持 Range）
	return m.probeRangeGet(e, token)
}

// probeHead HEAD 探测：返回 (大小, 是否支持 Range, 错误)；大小未知返回 -1。
func (m *DownloadManager) probeHead(e *dlFileEntry, token string) (int64, bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, e.URL, nil)
	if err != nil {
		return -1, false, &UserError{Code: "HEAD_FAILED",
			Params: map[string]any{"path": e.Path, "msg": summarize(err.Error())},
			Msg:    "无法访问下载地址: " + e.Path}
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := m.httpClient.Do(req)
	if err != nil {
		return -1, false, &UserError{Code: "HEAD_FAILED",
			Params: map[string]any{"path": e.Path, "msg": summarize(err.Error())},
			Msg:    "无法访问下载地址: " + e.Path}
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return -1, false, dlHTTPError(resp.StatusCode, e.Path)
	}
	supportsRange := strings.Contains(strings.ToLower(resp.Header.Get("Accept-Ranges")), "bytes")
	return resp.ContentLength, supportsRange, nil
}

// probeRangeGet 回退探测：GET Range bytes=0-0，从 Content-Range 解析总大小
// （modelscope 回 200 但也带 Content-Range，不能只看 206）；服务器真忽略 Range
// 回 200 且无 Content-Range 时取 Content-Length、标记不支持分段。
func (m *DownloadManager) probeRangeGet(e *dlFileEntry, token string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, e.URL, nil)
	if err != nil {
		return &UserError{Code: "HEAD_FAILED",
			Params: map[string]any{"path": e.Path, "msg": summarize(err.Error())},
			Msg:    "无法访问下载地址: " + e.Path}
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("Range", "bytes=0-0")
	resp, err := m.httpClient.Do(req)
	if err != nil {
		return &UserError{Code: "HEAD_FAILED",
			Params: map[string]any{"path": e.Path, "msg": summarize(err.Error())},
			Msg:    "无法访问下载地址: " + e.Path}
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, dlChunkSize))
	if resp.StatusCode >= 400 {
		return dlHTTPError(resp.StatusCode, e.Path)
	}
	// Content-Range: bytes 0-0/<total>（200 或 206 都可能带）
	if total := parseContentRangeTotal(resp.Header.Get("Content-Range")); total >= 0 {
		e.Size = total
		e.SupportsRange = true
		return nil
	}
	if resp.StatusCode == http.StatusPartialContent {
		// 206 但无法解析大小：退化为整流
		e.Size = -1
		e.SupportsRange = false
		return nil
	}
	e.Size = resp.ContentLength
	e.SupportsRange = false
	return nil
}

// parseContentRangeTotal 解析 Content-Range 头的总大小（/ 之后部分）；失败或 * 返回 -1。
func parseContentRangeTotal(cr string) int64 {
	i := strings.LastIndex(cr, "/")
	if i < 0 {
		return -1
	}
	total, err := strconv.ParseInt(strings.TrimSpace(cr[i+1:]), 10, 64)
	if err != nil {
		return -1
	}
	return total
}

// parseContentRangeStart 解析 Content-Range 头的起始偏移（"bytes <start>-<end>/<total>"）。
func parseContentRangeStart(cr string) (int64, bool) {
	s := strings.TrimSpace(cr)
	i := strings.IndexByte(s, ' ')
	if i < 0 {
		return 0, false
	}
	rng := s[i+1:]
	j := strings.IndexByte(rng, '-')
	if j < 0 {
		return 0, false
	}
	start, err := strconv.ParseInt(strings.TrimSpace(rng[:j]), 10, 64)
	if err != nil {
		return 0, false
	}
	return start, true
}

// buildSegments 分段：n = clamp(ceil(size / 32MB), 1, segmentsPerFile)；
// 未知大小/不支持 Range 退化为单段整流（end=-1）。
func (m *DownloadManager) buildSegments(size int64, supportsRange bool) []dlSegment {
	if size <= 0 || !supportsRange {
		return []dlSegment{{Start: 0, End: -1}}
	}
	n := (size + dlSegmentMin - 1) / dlSegmentMin
	if n < 1 {
		n = 1
	}
	if n > int64(m.segmentsPerFile) {
		n = int64(m.segmentsPerFile)
	}
	step := (size + n - 1) / n
	list := make([]dlSegment, 0, n)
	for i := int64(0); i < n; i++ {
		end := (i+1)*step - 1
		if end > size-1 {
			end = size - 1
		}
		list = append(list, dlSegment{Start: i * step, End: end})
	}
	return list
}

// ------------------------------------------------------------------ 内部工具

// checkFlags 块边界检查控制标志：代次不符/取消 → errDlCancelled；暂停/关停 → errDlPaused。
func (m *DownloadManager) checkFlags(t *DownloadTask, gen int32) error {
	if atomic.LoadInt32(&t.runGeneration) != gen || atomic.LoadInt32(&t.cancelRequested) == 1 {
		return errDlCancelled
	}
	if atomic.LoadInt32(&t.pauseRequested) == 1 || atomic.LoadInt32(&m.shutdownFlag) == 1 {
		return errDlPaused
	}
	return nil
}

func (m *DownloadManager) maybePersist(t *DownloadTask) {
	now := time.Now().UnixMilli()
	if now-atomic.LoadInt64(&t.lastPersistAt) < dlPersistInterval.Milliseconds() {
		return
	}
	m.mu.Lock()
	if now-t.lastPersistAt >= dlPersistInterval.Milliseconds() {
		m.persistLocked(t)
	}
	m.mu.Unlock()
}

// persistLocked 原子写 task.json（tmp + rename）；调用方需持有 m.mu。
func (m *DownloadManager) persistLocked(t *DownloadTask) {
	t.UpdatedAt = time.Now().UnixMilli()
	atomic.StoreInt64(&t.lastPersistAt, t.UpdatedAt)
	dir := filepath.Join(m.stateDir, t.ID)
	if err := os.MkdirAll(dir, 0755); err != nil {
		log.Printf("下载进度落盘失败 %s: %v", t.ID, err)
		return
	}
	data, err := json.Marshal(t)
	if err != nil {
		log.Printf("下载进度序列化失败 %s: %v", t.ID, err)
		return
	}
	if err := writeFileAtomic(filepath.Join(dir, "task.json"), data); err != nil {
		log.Printf("下载进度落盘失败 %s: %v", t.ID, err)
	}
}

// sampleSpeed 速率采样：每次查询时按时间窗增量估算 bytes/s；非 RUNNING 归零。
// 调用方需持有 m.mu。
func (m *DownloadManager) sampleSpeed(t *DownloadTask) {
	if t.Status != dlStatusRunning {
		atomic.StoreInt64(&t.speedBps, 0)
		atomic.StoreInt64(&t.speedSampleBytes, -1)
		return
	}
	now := time.Now().UnixMilli()
	bytes := t.downloadedBytes()
	prevBytes := atomic.LoadInt64(&t.speedSampleBytes)
	prevAt := atomic.LoadInt64(&t.speedSampleAt)
	if prevBytes >= 0 && now > prevAt {
		bps := (bytes - prevBytes) * 1000 / (now - prevAt)
		if bps < 0 {
			bps = 0
		}
		atomic.StoreInt64(&t.speedBps, bps)
	}
	atomic.StoreInt64(&t.speedSampleBytes, bytes)
	atomic.StoreInt64(&t.speedSampleAt, now)
}

func dlPercent(t *DownloadTask) int64 {
	total := t.totalBytes()
	if total <= 0 {
		return -1
	}
	return t.downloadedBytes() * 100 / total
}

// detailLocked 详情 JSON：任务全字段（剔除 token）+ 进度/速率派生字段。调用方需持有 m.mu。
func (m *DownloadManager) detailLocked(t *DownloadTask) map[string]any {
	m.sampleSpeed(t)
	o := map[string]any{
		"id":              t.ID,
		"targetDir":       t.TargetDir,
		"status":          t.Status,
		"createdAt":       t.CreatedAt,
		"updatedAt":       t.UpdatedAt,
		"files":           t.Files,
		"fileCount":       len(t.Files),
		"completedFiles":  t.completedFiles(),
		"totalBytes":      t.totalBytes(),
		"downloadedBytes": t.downloadedBytes(),
		"percent":         dlPercent(t),
		"speedBps":        atomic.LoadInt64(&t.speedBps),
	}
	if t.ModelID != "" {
		o["modelId"] = t.ModelID
	}
	if t.PackageID != "" {
		o["packageId"] = t.PackageID
	}
	if t.Source != "" {
		o["source"] = t.Source
	}
	if t.Error != "" {
		o["error"] = t.Error
	}
	return o
}

func (m *DownloadManager) requireTaskLocked(id string) (*DownloadTask, error) {
	t := m.tasks[id]
	if t == nil {
		return nil, &UserError{Code: "DOWNLOAD_NOT_FOUND",
			Params: map[string]any{"id": id}, Msg: "下载任务不存在: " + id}
	}
	return t, nil
}

func (m *DownloadManager) finalPath(targetDir, rel string) string {
	return filepath.Join(m.modelsDir, targetDir, filepath.FromSlash(rel))
}

// loadAll 启动时回放 data/downloads/<id>/task.json；RUNNING/PENDING 任务自动续传。
func (m *DownloadManager) loadAll() {
	dirs, err := os.ReadDir(m.stateDir)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("下载状态目录不可读: %v", err)
		}
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		taskFile := filepath.Join(m.stateDir, d.Name(), "task.json")
		data, err := os.ReadFile(taskFile)
		if err != nil {
			continue
		}
		var t DownloadTask
		if err := json.Unmarshal(data, &t); err != nil || t.ID == "" || t.TargetDir == "" {
			log.Printf("下载任务状态损坏，忽略 %s", taskFile)
			continue
		}
		t.speedSampleBytes = -1
		m.tasks[t.ID] = &t
		m.order = append(m.order, &t)
	}
	for _, t := range m.order {
		if t.Status == dlStatusRunning || t.Status == dlStatusPending {
			t.Status = dlStatusRunning
			t.Error = ""
			log.Printf("恢复下载任务: %s -> %s", t.ID, t.TargetDir)
			m.startRunnerLocked(t)
		}
	}
}

// deleteWithRetry 删除带重试：分段 goroutine 退出与文件句柄释放有延迟（尤其 Windows）。
func (m *DownloadManager) deleteWithRetry(path string) {
	for i := 0; i < 3; i++ {
		if err := os.Remove(path); err == nil || os.IsNotExist(err) {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	if pathExists(path) {
		log.Printf("删除失败（已重试）: %s", path)
	}
}

// movePartFile .part 改名落盘（Windows 下目标存在会导致改名失败，先删）。
func movePartFile(src, dst string) error {
	os.Remove(dst)
	return os.Rename(src, dst)
}

// dlSleepBackoff 重试退避：1s 翻倍封顶 15s；context 中断立即返回。
func dlSleepBackoff(ctx context.Context, attempt int) error {
	d := time.Second << (attempt - 1)
	if d > 15*time.Second {
		d = 15 * time.Second
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func dlActive(status string) bool {
	return status == dlStatusRunning || status == dlStatusPaused || status == dlStatusPending
}
