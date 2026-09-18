package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Task 一个异步推理任务（POST /api/tasks 创建）。
// 状态落盘 data/tasks/<id>.task.json，重启回放（进行中标记 CANCELLED）；
// 非 TTS 结果落盘 data/tasks/<id>.result.json；TTS 复用历史链路。
type Task struct {
	ID           string         `json:"id"`
	InstanceID   string         `json:"instanceId"`
	InstanceName string         `json:"instanceName"`
	ModelID      string         `json:"modelId"`
	Category     string         `json:"category"`
	Status       string         `json:"status"` // QUEUED/RUNNING/DONE/FAILED/CANCELLED
	CreatedAt    int64          `json:"createdAt"`
	StartedAt    *int64         `json:"startedAt,omitempty"`
	FinishedAt   *int64         `json:"finishedAt,omitempty"`
	Error        string         `json:"error,omitempty"`
	Text         *string        `json:"text,omitempty"`
	Result       map[string]any `json:"result,omitempty"`

	// 以下不参与持久化
	inst       *Instance
	request    map[string]any // body["request"]，历史记录与文本预览用
	requestRaw json.RawMessage
	resultPath string
}

func (t *Task) active() bool { return t.Status == "QUEUED" || t.Status == "RUNNING" }

const (
	taskStateDir   = "data/tasks"
	taskSuffix     = ".task.json"
	resultSuffix   = ".result.json"
	finishedKeep   = 100
	previewMaxSize = 8 << 20
)

// TaskManager 提交 → 同实例串行排队执行 → 前端轮询结果。与 ApiHandler 共享同一个 HistoryManager。
type TaskManager struct {
	mu        sync.Mutex
	tasks     map[string]*Task
	queues    map[string]chan *Task
	cancels   map[string]context.CancelFunc // RUNNING 任务的中断函数
	history   *HistoryManager
	forwarder *http.Client
}

func NewTaskManager(history *HistoryManager) *TaskManager {
	m := &TaskManager{
		tasks:     map[string]*Task{},
		queues:    map[string]chan *Task{},
		cancels:   map[string]context.CancelFunc{},
		history:   history,
		forwarder: &http.Client{}, // 无超时：生成任务时长不可预估
	}
	m.replay()
	return m
}

// replay 启动时回放 data/tasks/*.task.json；进行中任务标记 CANCELLED，孤儿文件清理。
func (m *TaskManager) replay() {
	os.MkdirAll(taskStateDir, 0755)
	entries, err := os.ReadDir(taskStateDir)
	if err != nil {
		return
	}
	loaded := map[string]bool{}
	for _, e := range entries {
		name := e.Name()
		if !strings.HasSuffix(name, taskSuffix) {
			continue
		}
		data, err := os.ReadFile(filepath.Join(taskStateDir, name))
		if err != nil {
			continue
		}
		var t Task
		if err := json.Unmarshal(data, &t); err != nil || t.ID == "" || t.ModelID == "" {
			log.Printf("跳过损坏的任务状态文件: %s", name)
			continue
		}
		if t.Status == "" || t.active() {
			t.Status = "CANCELLED"
		}
		if t.FinishedAt == nil {
			now := time.Now().UnixMilli()
			t.FinishedAt = &now
		}
		result := filepath.Join(taskStateDir, t.ID+resultSuffix)
		if isRegularFile(result) {
			t.resultPath = result
		}
		m.tasks[t.ID] = &t
		loaded[t.ID] = true
	}
	for _, e := range entries {
		name := e.Name()
		if strings.HasSuffix(name, ".tmp") {
			os.Remove(filepath.Join(taskStateDir, name))
		} else if strings.HasSuffix(name, resultSuffix) {
			id := strings.TrimSuffix(name, resultSuffix)
			if !loaded[id] {
				os.Remove(filepath.Join(taskStateDir, name))
			}
		}
	}
}

// Submit 创建任务并入队。调用方负责实例存在/READY 校验。
func (m *TaskManager) Submit(inst *Instance, request map[string]any, requestRaw json.RawMessage) *Task {
	t := &Task{
		ID:           newID(),
		InstanceID:   inst.ID,
		InstanceName: inst.Name,
		ModelID:      inst.ModelID,
		Category:     modelCategory(inst.ModelID),
		Status:       "QUEUED",
		CreatedAt:    time.Now().UnixMilli(),
		inst:         inst,
		request:      request,
		requestRaw:   requestRaw,
	}
	if s, ok := request["text"].(string); ok {
		preview := truncateRunes(s, 100)
		t.Text = &preview
	}
	m.mu.Lock()
	m.tasks[t.ID] = t
	queue := m.queueForLocked(inst.ID)
	m.mu.Unlock()
	m.persist(t)
	queue <- t
	log.Printf("任务已入队: %s (实例 %s, category %s)", t.ID, inst.Name, t.Category)
	return t
}

// queueForLocked 每实例一个串行队列（与引擎 busy 锁语义一致）。
func (m *TaskManager) queueForLocked(instanceID string) chan *Task {
	if q, ok := m.queues[instanceID]; ok {
		return q
	}
	q := make(chan *Task, 100)
	m.queues[instanceID] = q
	go func() {
		for t := range q {
			m.execute(t)
		}
	}()
	return q
}

// Cancel QUEUED/RUNNING → CANCELLED（RUNNING 中断 hub 侧等待）；已结束 → 删除记录。
func (m *TaskManager) Cancel(id string) bool {
	m.mu.Lock()
	t, ok := m.tasks[id]
	if !ok {
		m.mu.Unlock()
		return false
	}
	if t.active() {
		t.Status = "CANCELLED"
		now := time.Now().UnixMilli()
		t.FinishedAt = &now
		if cancel, ok := m.cancels[id]; ok {
			cancel()
			delete(m.cancels, id)
		}
		m.mu.Unlock()
		m.persist(t)
		log.Printf("任务已取消: %s", id)
		return true
	}
	delete(m.tasks, id)
	m.mu.Unlock()
	os.Remove(t.resultPath)
	os.Remove(filepath.Join(taskStateDir, id+taskSuffix))
	return true
}

// List 活跃在前（组内创建时间倒序）；activeOnly 只留 QUEUED/RUNNING，modelID 非空时过滤。
func (m *TaskManager) List(activeOnly bool, modelID string) []map[string]any {
	m.mu.Lock()
	all := make([]*Task, 0, len(m.tasks))
	for _, t := range m.tasks {
		all = append(all, t)
	}
	m.mu.Unlock()
	sort.SliceStable(all, func(i, j int) bool { return all[i].CreatedAt > all[j].CreatedAt })
	sort.SliceStable(all, func(i, j int) bool {
		ai, aj := 1, 1
		if all[i].active() {
			ai = 0
		}
		if all[j].active() {
			aj = 0
		}
		return ai < aj
	})
	out := []map[string]any{}
	for _, t := range all {
		if activeOnly && !t.active() {
			continue
		}
		if modelID != "" && modelID != t.ModelID {
			continue
		}
		out = append(out, m.outputJSON(t))
	}
	return out
}

// ActiveCountFor 指定实例当前活跃任务数（实例卡片“工作中”徽标）。
func (m *TaskManager) ActiveCountFor(instanceID string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, t := range m.tasks {
		if t.active() && t.InstanceID == instanceID {
			n++
		}
	}
	return n
}

// Get 单任务详情（含 position），不存在返回 nil。
func (m *TaskManager) Get(id string) map[string]any {
	m.mu.Lock()
	t := m.tasks[id]
	m.mu.Unlock()
	if t == nil {
		return nil
	}
	return m.outputJSON(t)
}

// ResultPath 非 TTS 已完成任务的结果文件路径，其余返回空串。
func (m *TaskManager) ResultPath(id string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.tasks[id]
	if t == nil || t.Status != "DONE" || t.Category == "tts" {
		return ""
	}
	return t.resultPath
}

// outputJSON 序列化任务并附加队列位置（position 不落盘）。
func (m *TaskManager) outputJSON(t *Task) map[string]any {
	data, _ := json.Marshal(t)
	var out map[string]any
	json.Unmarshal(data, &out)
	out["position"] = m.position(t)
	return out
}

// position 同实例排在该任务前面的 QUEUED 任务数；非 QUEUED 恒为 0。
func (m *TaskManager) position(task *Task) int {
	if task.Status != "QUEUED" {
		return 0
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, o := range m.tasks {
		if o.Status == "QUEUED" && o.InstanceID == task.InstanceID && o.CreatedAt < task.CreatedAt {
			n++
		}
	}
	return n
}

func (m *TaskManager) execute(t *Task) {
	m.mu.Lock()
	if t.Status != "QUEUED" {
		m.mu.Unlock()
		return
	}
	t.Status = "RUNNING"
	now := time.Now().UnixMilli()
	t.StartedAt = &now
	ctx, cancel := context.WithCancel(context.Background())
	m.cancels[t.ID] = cancel
	m.mu.Unlock()
	m.persist(t)

	var err error
	if t.Category == "tts" {
		err = m.runTTS(ctx, t)
	} else {
		out := filepath.Join(taskStateDir, t.ID+resultSuffix)
		err = m.forwardToFile(ctx, t.inst, t.requestRaw, out)
		if err == nil {
			t.resultPath = out
			if t.Text == nil {
				t.Text = resultTextPreview(out)
			}
		}
	}

	m.mu.Lock()
	delete(m.cancels, t.ID)
	cancel()
	if err != nil {
		if t.Status == "RUNNING" { // 已被 Cancel 标记的保持 CANCELLED
			t.Status = "FAILED"
			t.Error = summarize(err.Error())
			if t.Category == "tts" {
				m.history.RecordTTS(t.inst, t.request, t.ID, nil, t.Error)
			}
		}
	} else if t.Status == "RUNNING" {
		t.Status = "DONE"
	}
	if t.FinishedAt == nil {
		now := time.Now().UnixMilli()
		t.FinishedAt = &now
	}
	m.mu.Unlock()
	if err != nil {
		log.Printf("任务执行失败: %s: %v", t.ID, err)
	}
	m.persist(t)
	m.evictFinished()
}

// runTTS 响应落盘临时文件 → 提取 audio 写成 wav → 解析 WAV 头取元数据 → 记历史。
func (m *TaskManager) runTTS(ctx context.Context, t *Task) error {
	dir := filepath.Join("data", "history", t.ModelID)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("历史目录不可用: %w", err)
	}
	tmp := filepath.Join(dir, t.ID+".resp.tmp")
	defer os.Remove(tmp)
	if err := m.forwardToFile(ctx, t.inst, t.requestRaw, tmp); err != nil {
		m.history.RecordTTS(t.inst, t.request, t.ID, nil, summarize(err.Error()))
		return err
	}
	wav := filepath.Join(dir, t.ID+".wav")
	var result map[string]any
	var errMsg string
	found, err := extractAudio(tmp, wav)
	if err != nil {
		errMsg = "结果音频提取失败: " + summarize(err.Error())
		os.Remove(wav)
	} else if !found {
		errMsg = "响应中未找到音频数据"
	} else if info, perr := parseWAVFile(wav); perr != nil {
		errMsg = "结果音频提取失败: " + summarize(perr.Error())
		os.Remove(wav)
	} else {
		st, _ := os.Stat(wav)
		var size int64
		if st != nil {
			size = st.Size()
		}
		result = map[string]any{
			"file":        t.ID + ".wav",
			"size":        size,
			"durationSec": round3(info.durationSec),
			"sampleRate":  info.sampleRate,
			"channels":    info.channels,
		}
	}
	m.history.RecordTTS(t.inst, t.request, t.ID, result, errMsg)
	if errMsg != "" {
		return fmt.Errorf("%s", errMsg)
	}
	t.Result = result
	return nil
}

// forwardToFile 把 {"model":<服务名>,"request":{...}} POST 到实例 /v1/tasks/run，
// 200 响应体流式落盘；非 200 读错误体抛异常（对应 Java 版 SpeechForwarder）。
func (m *TaskManager) forwardToFile(ctx context.Context, inst *Instance, requestRaw json.RawMessage, target string) error {
	var body bytes.Buffer
	modelName, _ := json.Marshal(inst.Name)
	body.WriteString(`{"model":`)
	body.Write(modelName)
	body.WriteString(`,"request":`)
	if len(requestRaw) > 0 {
		body.Write(requestRaw)
	} else {
		body.WriteString(`{}`)
	}
	body.WriteByte('}')

	url := fmt.Sprintf("http://127.0.0.1:%d/v1/tasks/run", inst.Port)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, &body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := m.forwarder.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		errBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		return fmt.Errorf("audiocpp_server 返回 %d: %s", resp.StatusCode, summarize(string(errBody)))
	}
	out, err := os.Create(target)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, resp.Body)
	return err
}

// extractAudio 从响应 JSON 中流式提取顶层 "audio"（base64）解码写 wav。
func extractAudio(src, dst string) (bool, error) {
	f, err := os.Open(src)
	if err != nil {
		return false, err
	}
	defer f.Close()
	dec := json.NewDecoder(f)
	if _, err := dec.Token(); err != nil { // 顶层 '{'
		return false, err
	}
	for dec.More() {
		kt, err := dec.Token()
		if err != nil {
			return false, err
		}
		key, _ := kt.(string)
		if key == "audio" {
			vt, err := dec.Token()
			if err != nil {
				return false, err
			}
			s, ok := vt.(string)
			if !ok {
				return false, nil
			}
			data, err := base64.StdEncoding.DecodeString(s)
			if err != nil {
				return false, err
			}
			return true, os.WriteFile(dst, data, 0644)
		}
		var skip json.RawMessage
		if err := dec.Decode(&skip); err != nil {
			return false, err
		}
	}
	return false, nil
}

// resultTextPreview 非 TTS 结果 JSON 顶层 "text" 截断 100 字（如 ASR 转写文本）。
func resultTextPreview(resultPath string) *string {
	st, err := os.Stat(resultPath)
	if err != nil || st.Size() > previewMaxSize {
		return nil
	}
	data, err := os.ReadFile(resultPath)
	if err != nil {
		return nil
	}
	var obj map[string]any
	if err := json.Unmarshal(data, &obj); err != nil {
		return nil
	}
	if s, ok := obj["text"].(string); ok {
		preview := truncateRunes(s, 100)
		return &preview
	}
	return nil
}

// evictFinished 已完成任务超出保留上限时淘汰最旧（连带删除状态与结果文件）。
func (m *TaskManager) evictFinished() {
	m.mu.Lock()
	defer m.mu.Unlock()
	var finished []*Task
	for _, t := range m.tasks {
		if !t.active() {
			finished = append(finished, t)
		}
	}
	if len(finished) <= finishedKeep {
		return
	}
	sort.Slice(finished, func(i, j int) bool { return finished[i].CreatedAt < finished[j].CreatedAt })
	for _, t := range finished[:len(finished)-finishedKeep] {
		delete(m.tasks, t.ID)
		os.Remove(t.resultPath)
		os.Remove(filepath.Join(taskStateDir, t.ID+taskSuffix))
	}
}

// persist 任务状态原子落盘，失败只记日志。
func (m *TaskManager) persist(t *Task) {
	m.mu.Lock()
	data, err := json.Marshal(t)
	m.mu.Unlock()
	if err != nil {
		return
	}
	if err := os.MkdirAll(taskStateDir, 0755); err != nil {
		return
	}
	if err := writeFileAtomic(filepath.Join(taskStateDir, t.ID+taskSuffix), data); err != nil {
		log.Printf("任务状态落盘失败: %s: %v", t.ID, err)
	}
}
