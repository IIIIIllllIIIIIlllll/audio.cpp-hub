package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Executable 可执行文件登记表条目（executables.json）。
// Exists 仅用于输出（写文件时 omitempty 丢弃）。
type Executable struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	Path      string            `json:"path"`
	Note      string            `json:"note,omitempty"`
	Env       map[string]string `json:"env,omitempty"`
	CreatedAt string            `json:"createdAt"`
	Exists    bool              `json:"exists,omitempty"`
}

// ExecutableRegistry 持久化到工作目录下 executables.json（对应 Java 版 ExecutableRegistry）。
type ExecutableRegistry struct {
	mu   sync.Mutex
	file string
}

func NewExecutableRegistry(file string) *ExecutableRegistry {
	return &ExecutableRegistry{file: file}
}

var envKeyPattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func (r *ExecutableRegistry) read() ([]Executable, error) {
	data, err := os.ReadFile(r.file)
	if err != nil || len(strings.TrimSpace(string(data))) == 0 {
		return nil, nil
	}
	var list []Executable
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, err
	}
	return list, nil
}

func (r *ExecutableRegistry) write(list []Executable) error {
	for i := range list {
		list[i].Exists = false
	}
	data, err := json.Marshal(list)
	if err != nil {
		return err
	}
	return os.WriteFile(r.file, data, 0644)
}

// List 全部条目，附带实时探测的 exists。
func (r *ExecutableRegistry) List() []Executable {
	r.mu.Lock()
	defer r.mu.Unlock()
	list, _ := r.read()
	if list == nil {
		list = []Executable{}
	}
	for i := range list {
		_, err := resolveExecPath(list[i].Path)
		list[i].Exists = err == nil
	}
	return list
}

func (r *ExecutableRegistry) FindByID(id string) *Executable {
	r.mu.Lock()
	defer r.mu.Unlock()
	list, _ := r.read()
	for i := range list {
		if list[i].ID == id {
			return &list[i]
		}
	}
	return nil
}

// First 第一个条目（启动实例的默认值）。
func (r *ExecutableRegistry) First() *Executable {
	r.mu.Lock()
	defer r.mu.Unlock()
	list, _ := r.read()
	if len(list) == 0 {
		return nil
	}
	return &list[0]
}

// Add 添加条目：name/path 必填；文件不存在拒绝；目录路径自动定位 audiocpp_server。
func (r *ExecutableRegistry) Add(name, path, note string, env map[string]string) (*Executable, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	entry := Executable{
		ID:        newID(),
		Name:      strings.TrimSpace(name),
		Path:      strings.TrimSpace(path),
		Note:      strings.TrimSpace(note),
		Env:       env,
		CreatedAt: time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
	}
	if err := validateExec(&entry); err != nil {
		return nil, err
	}
	list, _ := r.read()
	list = append(list, entry)
	if err := r.write(list); err != nil {
		return nil, err
	}
	entry.Exists = true
	return &entry, nil
}

// Update 更新条目字段（id/createdAt 保留）；不存在返回 nil。
func (r *ExecutableRegistry) Update(id, name, path, note string, env map[string]string) (*Executable, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	list, _ := r.read()
	for i := range list {
		if list[i].ID != id {
			continue
		}
		updated := Executable{
			ID:        list[i].ID,
			Name:      strings.TrimSpace(name),
			Path:      strings.TrimSpace(path),
			Note:      strings.TrimSpace(note),
			Env:       env,
			CreatedAt: list[i].CreatedAt,
		}
		if err := validateExec(&updated); err != nil {
			return nil, err
		}
		list[i] = updated
		if err := r.write(list); err != nil {
			return nil, err
		}
		updated.Exists = true
		return &updated, nil
	}
	return nil, nil
}

func (r *ExecutableRegistry) Delete(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	list, _ := r.read()
	for i := range list {
		if list[i].ID == id {
			list = append(list[:i], list[i+1:]...)
			r.write(list)
			return true
		}
	}
	return false
}

func validateExec(e *Executable) error {
	if e.Name == "" {
		return newUserError("NAME_REQUIRED", "名称不能为空")
	}
	if e.Path == "" {
		return newUserError("PATH_REQUIRED", "路径不能为空")
	}
	for k := range e.Env {
		if !envKeyPattern.MatchString(k) {
			return newUserError("ENV_KEY_INVALID", "环境变量名不合法: "+k)
		}
	}
	resolved, err := resolveExecPath(e.Path)
	if err != nil {
		return err
	}
	e.Path = resolved
	return nil
}

// resolveExecPath 解析为绝对路径：相对路径相对工作目录；Windows 补 .exe 探测；
// 目录路径在目录内（含 bin/）自动定位 audiocpp_server。
func resolveExecPath(raw string) (string, error) {
	p := raw
	if !filepath.IsAbs(p) {
		abs, err := filepath.Abs(p)
		if err == nil {
			p = abs
		}
	}
	if runtime.GOOS == "windows" && !strings.HasSuffix(strings.ToLower(p), ".exe") && !pathExists(p) {
		if pathExists(p + ".exe") {
			p += ".exe"
		}
	}
	if isDir(p) {
		for _, candidate := range []string{
			"audiocpp_server.exe", "audiocpp_server",
			filepath.Join("bin", "audiocpp_server.exe"), filepath.Join("bin", "audiocpp_server"),
		} {
			full := filepath.Join(p, candidate)
			if isRegularFile(full) {
				return full, nil
			}
		}
		return "", newUserError("EXEC_NOT_FOUND_IN_DIR", "目录下未找到 audiocpp_server 可执行文件: "+p)
	}
	if !isRegularFile(p) {
		return "", newUserError("FILE_NOT_FOUND", "文件不存在: "+p)
	}
	return p, nil
}

// ProfileRegistry 启动配置档案，持久化到 data/profiles.json。
// 条目字段随前端需要变化，直接用 map 持有。
type ProfileRegistry struct {
	mu   sync.Mutex
	file string
}

func NewProfileRegistry(file string) *ProfileRegistry {
	return &ProfileRegistry{file: file}
}

func (r *ProfileRegistry) read() []map[string]any {
	data, err := os.ReadFile(r.file)
	if err != nil || len(strings.TrimSpace(string(data))) == 0 {
		return nil
	}
	var list []map[string]any
	if err := json.Unmarshal(data, &list); err != nil {
		return nil
	}
	return list
}

func (r *ProfileRegistry) write(list []map[string]any) error {
	if err := os.MkdirAll(filepath.Dir(r.file), 0755); err != nil {
		return err
	}
	data, err := json.Marshal(list)
	if err != nil {
		return err
	}
	return os.WriteFile(r.file, data, 0644)
}

// List 全部条目，附带 weightsExists（前端据此判断权重是否仍有效）。
func (r *ProfileRegistry) List() []map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	list := r.read()
	if list == nil {
		list = []map[string]any{}
	}
	for _, p := range list {
		p["weightsExists"] = pathExists(optString(p, "weightsPath"))
	}
	return list
}

// profileFields 允许持久化的字段白名单。
var profileFields = []string{
	"name", "modelId", "weightsPath", "backend", "device", "port", "threads",
	"executableId", "instanceName", "sessionOptions",
}

// Save 新增（id 为空）或按 id 更新；更新且不存在时返回 nil。
func (r *ProfileRegistry) Save(id string, body map[string]any) (map[string]any, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	fields := map[string]any{}
	for _, k := range profileFields {
		if v, ok := body[k]; ok {
			fields[k] = v
		}
	}
	now := time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
	list := r.read()
	if id == "" {
		fields["id"] = newID()
		fields["createdAt"] = now
		fields["updatedAt"] = now
		list = append(list, fields)
		if err := r.write(list); err != nil {
			return nil, err
		}
		return fields, nil
	}
	for i, p := range list {
		if optString(p, "id") == id {
			fields["id"] = id
			fields["createdAt"] = p["createdAt"]
			fields["updatedAt"] = now
			list[i] = fields
			if err := r.write(list); err != nil {
				return nil, err
			}
			return fields, nil
		}
	}
	return nil, nil
}

func (r *ProfileRegistry) Delete(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	list := r.read()
	for i, p := range list {
		if optString(p, "id") == id {
			list = append(list[:i], list[i+1:]...)
			r.write(list)
			return true
		}
	}
	return false
}
