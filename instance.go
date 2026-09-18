package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Instance 一个 audiocpp_server 进程实例。只有 STARTING/READY 的实例存在于管理器中。
type Instance struct {
	ID             string
	Name           string // 服务名（instanceName）：/v1/* 路由键，写进 server.json 的 model id
	ModelID        string
	WeightsPath    string
	Port           int
	Backend        string
	Device         *int
	ExecName       string
	Threads        *int
	SessionOptions map[string]string
	Status         string // STARTING / READY
	CreatedAt      string

	cmd    *exec.Cmd
	exited chan int // 进程退出后收到 exit code
}

// Event 事件日志条目（GET /api/events）。
type Event struct {
	Time    string `json:"time"`
	Level   string `json:"level"`
	Message string `json:"message"`
}

var instanceNamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)

const healthTimeoutSeconds = 120

// InstanceManager 子进程生命周期、端口分配、健康轮询、run/<id> 清理。
type InstanceManager struct {
	mu       sync.Mutex
	portBase int
	items    map[string]*Instance
	events   []Event // 新到旧，保留 20 条

	healthClient *http.Client
}

func NewInstanceManager(portBase int) *InstanceManager {
	return &InstanceManager{
		portBase:     portBase,
		items:        map[string]*Instance{},
		healthClient: &http.Client{Timeout: 2 * time.Second},
	}
}

// StartParams 启动一个实例所需的全部参数。
type StartParams struct {
	ModelID        string
	EngineFamily   string
	WeightsPath    string
	Backend        string
	Device         *int
	Port           *int
	Threads        *int
	ExecPath       string
	ExecName       string
	ServerTask     string
	Env            map[string]string
	Name           string
	SessionOptions map[string]string
}

func (m *InstanceManager) List() []*Instance {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*Instance, 0, len(m.items))
	for _, inst := range m.items {
		out = append(out, inst)
	}
	return out
}

func (m *InstanceManager) Get(id string) *Instance {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.items[id]
}

// FindByName 按服务名查找 READY 实例（/v1/* 路由键）。
func (m *InstanceManager) FindByName(name string) *Instance {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, inst := range m.items {
		if inst.Name == name && inst.Status == "READY" {
			return inst
		}
	}
	return nil
}

// FindAnyByName 按服务名查找任意状态实例（区分 404 不存在与 409 启动中）。
func (m *InstanceManager) FindAnyByName(name string) *Instance {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, inst := range m.items {
		if inst.Name == name {
			return inst
		}
	}
	return nil
}

func (m *InstanceManager) Events() []Event {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Event, len(m.events))
	copy(out, m.events)
	return out
}

func (m *InstanceManager) addEvent(level, message string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.events = append([]Event{{
		Time:    time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		Level:   level,
		Message: message,
	}}, m.events...)
	if len(m.events) > 20 {
		m.events = m.events[:20]
	}
}

// Start 拉起一个实例，立即返回（STARTING），后台 goroutine 轮询健康状态。
func (m *InstanceManager) Start(p StartParams) (*Instance, error) {
	name := strings.TrimSpace(p.Name)
	if name == "" {
		name = p.ModelID
	}
	if !instanceNamePattern.MatchString(name) {
		return nil, newUserError("INSTANCE_NAME_INVALID", "服务名不合法（字母数字开头，可含 . _ -，最长 64）: "+name)
	}
	m.mu.Lock()
	for _, existing := range m.items {
		if existing.Name == name {
			m.mu.Unlock()
			return nil, newUserError("INSTANCE_NAME_DUPLICATE", "服务名已被实例 #"+existing.ID+" 占用: "+name)
		}
	}
	port := m.allocatePortLocked(p.Port)
	m.mu.Unlock()

	id := newID()
	dir := filepath.Join("run", id)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}
	serverJSON := filepath.Join(dir, "server.json")
	if err := writeServerJSON(serverJSON, port, p, name); err != nil {
		return nil, err
	}
	absServerJSON, _ := filepath.Abs(serverJSON)

	logFile, err := os.OpenFile(filepath.Join(dir, "server.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(p.ExecPath, "--config", absServerJSON)
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if len(p.Env) > 0 {
		env := map[string]string{}
		for _, kv := range os.Environ() {
			if k, v, ok := strings.Cut(kv, "="); ok {
				env[k] = v
			}
		}
		keys := make([]string, 0, len(p.Env))
		for k, v := range p.Env {
			env[k] = expandEnv(v, func(name string) string { return env[name] })
			keys = append(keys, k)
		}
		cmd.Env = make([]string, 0, len(env))
		for k, v := range env {
			cmd.Env = append(cmd.Env, k+"="+v)
		}
		log.Printf("[%s] 注入环境变量: %s", id, strings.Join(keys, ", "))
	}
	if err := cmd.Start(); err != nil {
		logFile.Close()
		m.addEvent("error", "实例启动失败（"+p.ExecName+"）: "+summarize(err.Error()))
		cleanupRunDir(id)
		return nil, err
	}

	inst := &Instance{
		ID:             id,
		Name:           name,
		ModelID:        p.ModelID,
		WeightsPath:    p.WeightsPath,
		Port:           port,
		Backend:        p.Backend,
		Device:         p.Device,
		ExecName:       p.ExecName,
		Threads:        p.Threads,
		SessionOptions: p.SessionOptions,
		Status:         "STARTING",
		CreatedAt:      time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		cmd:            cmd,
		exited:         make(chan int, 1),
	}
	m.mu.Lock()
	m.items[id] = inst
	m.mu.Unlock()
	go func() {
		err := cmd.Wait()
		logFile.Close()
		code := 0
		if err != nil {
			code = 1
			if exitErr, ok := err.(*exec.ExitError); ok {
				code = exitErr.ExitCode()
			}
		}
		inst.exited <- code
	}()

	log.Printf("[%s] 实例已启动: executable=%s, name=%s, modelId=%s, backend=%s, port=%d, pid=%d",
		id, p.ExecName, name, p.ModelID, p.Backend, port, cmd.Process.Pid)
	m.addEvent("info", fmt.Sprintf("实例 #%s 启动中（%s，端口 %d）", id, p.ExecName, port))
	go m.awaitReady(inst)
	return inst, nil
}

// Stop 停止实例：进程终止后从 map 移除并记事件。
func (m *InstanceManager) Stop(id string) bool {
	m.mu.Lock()
	inst, ok := m.items[id]
	if ok {
		delete(m.items, id)
	}
	m.mu.Unlock()
	if !ok {
		return false
	}
	if inst.cmd != nil && inst.cmd.Process != nil {
		inst.cmd.Process.Kill()
		select {
		case <-inst.exited:
		case <-time.After(5 * time.Second):
		}
	}
	log.Printf("[%s] 实例已停止: port=%d", id, inst.Port)
	m.addEvent("info", "实例 #"+id+" 已停止")
	cleanupRunDir(id)
	return true
}

func (m *InstanceManager) stopAll() {
	for _, inst := range m.List() {
		m.Stop(inst.ID)
	}
}

// allocatePortLocked 从 portBase 起分配未被占用的端口（调用方须持锁）。
func (m *InstanceManager) allocatePortLocked(requested *int) int {
	if requested != nil {
		return *requested
	}
	port := m.portBase
	for m.isPortUsedLocked(port) {
		port++
	}
	return port
}

func (m *InstanceManager) isPortUsedLocked(port int) bool {
	for _, inst := range m.items {
		if inst.Port == port {
			return true
		}
	}
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return true
	}
	ln.Close()
	return false
}

// awaitReady 每 1s 轮询 /health，最多 120s；失败路径移除实例并记事件。
func (m *InstanceManager) awaitReady(inst *Instance) {
	deadline := time.Now().Add(healthTimeoutSeconds * time.Second)
	healthURL := fmt.Sprintf("http://127.0.0.1:%d/health", inst.Port)
	for time.Now().Before(deadline) {
		select {
		case code := <-inst.exited:
			reason := fmt.Sprintf("实例 #%s 进程提前退出 (exit=%d)，日志尾部: %s", inst.ID, code, readLogTail(inst.ID))
			log.Printf("[%s] 实例进程提前退出: exit=%d", inst.ID, code)
			m.addEvent("error", reason)
			m.mu.Lock()
			delete(m.items, inst.ID)
			m.mu.Unlock()
			cleanupRunDir(inst.ID)
			return
		case <-time.After(1 * time.Second):
		}
		resp, err := m.healthClient.Get(healthURL)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == 200 {
				m.mu.Lock()
				inst.Status = "READY"
				m.mu.Unlock()
				log.Printf("[%s] 实例就绪: port=%d", inst.ID, inst.Port)
				m.addEvent("info", fmt.Sprintf("实例 #%s 已就绪（端口 %d）", inst.ID, inst.Port))
				return
			}
		}
	}
	log.Printf("[%s] 实例等待就绪超时", inst.ID)
	m.addEvent("error", fmt.Sprintf("实例 #%s 等待就绪超时 (%ds)，日志尾部: %s",
		inst.ID, healthTimeoutSeconds, readLogTail(inst.ID)))
	m.mu.Lock()
	delete(m.items, inst.ID)
	m.mu.Unlock()
	if inst.cmd != nil && inst.cmd.Process != nil {
		inst.cmd.Process.Kill()
	}
	cleanupRunDir(inst.ID)
}

// readLogTail 读实例日志末尾 10 行，用于错误诊断。
func readLogTail(id string) string {
	data, err := os.ReadFile(filepath.Join("run", id, "server.log"))
	if err != nil {
		return "(无日志)"
	}
	lines := strings.Split(strings.TrimRight(string(data), "\r\n"), "\n")
	if len(lines) > 10 {
		lines = lines[len(lines)-10:]
	}
	return summarize(strings.Join(lines, " | "))
}

// cleanupRunDir 删除 run/<id>（Windows 文件句柄释放有延迟，带有限重试）。
func cleanupRunDir(id string) {
	dir := filepath.Join("run", id)
	for attempt := 1; attempt <= 5; attempt++ {
		err1 := os.Remove(filepath.Join(dir, "server.json"))
		err2 := os.Remove(filepath.Join(dir, "server.log"))
		err3 := os.Remove(dir)
		if (err1 == nil || os.IsNotExist(err1)) && (err2 == nil || os.IsNotExist(err2)) &&
			(err3 == nil || os.IsNotExist(err3)) {
			return
		}
		if attempt == 5 {
			log.Printf("清理实例运行目录失败: %s", dir)
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// writeServerJSON 生成 audiocpp_server 的 server.json（对应 Java 版 ServerConfigWriter）。
func writeServerJSON(path string, port int, p StartParams, instanceName string) error {
	model := map[string]any{
		"id":     instanceName,
		"family": p.EngineFamily,
		"path":   p.WeightsPath,
		"task":   p.ServerTask,
		"mode":   "offline",
	}
	if len(p.SessionOptions) > 0 {
		model["session_options"] = p.SessionOptions
	}
	threads := 1
	if p.Threads != nil {
		threads = *p.Threads
	} else if p.Backend == "cpu" {
		threads = runtime.NumCPU()
	}
	root := map[string]any{
		"host":      "127.0.0.1",
		"port":      port,
		"backend":   p.Backend,
		"threads":   threads,
		"lazy_load": true,
		"models":    []map[string]any{model},
	}
	if p.Device != nil {
		root["device"] = *p.Device
	}
	data, err := json.Marshal(root)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}

// ToJSON 实例的 API 输出形状（对应 Java 版 ApiHandler.toJson）。
func (m *InstanceManager) ToJSON(inst *Instance, taskCount int) map[string]any {
	m.mu.Lock()
	status := inst.Status
	m.mu.Unlock()
	out := map[string]any{
		"id":             inst.ID,
		"instanceName":   inst.Name,
		"modelId":        inst.ModelID,
		"weightsPath":    inst.WeightsPath,
		"port":           inst.Port,
		"backend":        inst.Backend,
		"executableName": inst.ExecName,
		"status":         status,
		"createdAt":      inst.CreatedAt,
		"taskCount":      taskCount,
		"sessionOptions": inst.SessionOptions,
	}
	if inst.Device != nil {
		out["device"] = *inst.Device
	}
	if inst.Threads != nil {
		out["threads"] = *inst.Threads
	}
	return out
}

var deviceLinePattern = regexp.MustCompile(`^([A-Za-z0-9_]+):(\d+)(?:\s+"([^"]*)")?\s+\[([^\]]+)\]\s*$`)

// ListDevices 运行 <可执行文件> --list-devices 并解析输出（对应 Java 版 DeviceLister）。
func ListDevices(execPath string, env map[string]string) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, execPath, "--list-devices")
	if len(env) > 0 {
		cmd.Env = os.Environ()
		for k, v := range env {
			cmd.Env = append(cmd.Env, k+"="+expandEnv(v, os.Getenv))
		}
	}
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("--list-devices 执行失败: %w", err)
	}
	devices := []map[string]any{}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimRight(line, "\r")
		m := deviceLinePattern.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		var index int
		fmt.Sscanf(m[2], "%d", &index)
		devices = append(devices, map[string]any{
			"backend": m[1],
			"index":   index,
			"name":    m[3],
			"type":    m[4],
		})
	}
	return map[string]any{"devices": devices, "raw": string(out)}, nil
}
