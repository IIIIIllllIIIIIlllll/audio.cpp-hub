package main

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// HistoryManager TTS 操作历史：按 modelId 隔离到 data/history/<modelId>/
// （index.jsonl 一行一条只追加 + <taskId>.wav 结果音频 + <taskId>.<快照名>.wav 参考音频快照
// + groups.json 手动分组），内存索引启动时回放重建。
// 与 Java 版的差异：无数量/容量淘汰——历史是用户资产，只由用户手动删除。
type HistoryManager struct {
	mu    sync.Mutex
	index map[string][]map[string]any // modelId → 记录（旧→新）
}

var historySafeKey = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

// historyRefName 参考音频快照名：ref（voice_ref）/ emo（情感参考 audio）/ spk0..spk99（voice_samples 逐项）
var historyRefName = regexp.MustCompile(`^(ref|emo|spk\d{1,2})$`)

const (
	historyMaxRefBytes  = 50 * 1024 * 1024
	historyListTextMax  = 100
	historyMaxGroupName = 50
	historyIndexFile    = "index.jsonl"
	historyGroupsFile   = "groups.json"
)

func NewHistoryManager() *HistoryManager {
	m := &HistoryManager{index: map[string][]map[string]any{}}
	m.replay()
	return m
}

func historyDir(modelID string) string {
	return filepath.Join("data", "history", modelID)
}

// replay 启动时回放 data/history/*/index.jsonl 重建内存索引，并清扫残留的响应临时文件。
func (m *HistoryManager) replay() {
	dirs, err := os.ReadDir(filepath.Join("data", "history"))
	if err != nil {
		return
	}
	for _, d := range dirs {
		if !d.IsDir() || !historySafeKey.MatchString(d.Name()) {
			continue
		}
		modelID := d.Name()
		dir := historyDir(modelID)
		// 上次运行残留的响应临时文件直接清扫
		if files, err := os.ReadDir(dir); err == nil {
			for _, f := range files {
				if strings.HasSuffix(f.Name(), ".resp.tmp") {
					os.Remove(filepath.Join(dir, f.Name()))
				}
			}
		}
		data, err := os.ReadFile(filepath.Join(dir, historyIndexFile))
		if err != nil {
			continue
		}
		var records []map[string]any
		for _, line := range strings.Split(string(data), "\n") {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			var rec map[string]any
			if err := json.Unmarshal([]byte(line), &rec); err == nil {
				records = append(records, rec)
			} else {
				log.Printf("跳过损坏的历史索引行: %s", historyIndexFile)
			}
		}
		if len(records) > 0 {
			m.index[modelID] = records
		}
	}
}

// RecordTTS 记录一条 TTS 任务：归一化请求结构（text/language/voice/options）+ 参考音频快照
// + 结果元数据。result 非空表示拿到音频；errMsg 非空表示失败。落盘失败只记日志。
func (m *HistoryManager) RecordTTS(inst *Instance, request map[string]any, taskID string,
	result map[string]any, errMsg string) {
	modelID := inst.ModelID
	if !historySafeKey.MatchString(modelID) || !historySafeKey.MatchString(taskID) {
		log.Printf("历史记录的 modelId/taskId 非法，跳过: %s/%s", modelID, taskID)
		return
	}
	rec := map[string]any{
		"taskId":       taskID,
		"time":         time.Now().UnixMilli(),
		"instanceName": inst.Name,
		"category":     "tts",
		"ok":           errMsg == "",
		"voice":        normalizeVoice(request),
	}
	if s := optString(request, "text"); s != "" {
		rec["text"] = s
	}
	if s := optString(request, "language"); s != "" {
		rec["language"] = s
	}
	options := map[string]any{}
	if o, ok := request["options"].(map[string]any); ok {
		for k, v := range o {
			options[k] = v
		}
	}
	// qwen3_tts_voicedesign 的 seed 在请求顶层，并入 options 供前端「载入」还原
	if seed, ok := request["seed"]; ok {
		options["seed"] = seed
	}
	if len(options) > 0 {
		rec["options"] = options
	}
	if result != nil {
		rec["result"] = result
	}
	if errMsg != "" {
		rec["error"] = summarize(errMsg)
	}

	dir := historyDir(modelID)
	if err := os.MkdirAll(dir, 0755); err != nil {
		log.Printf("历史记录落盘失败: %v", err)
		return
	}
	snapshotRefAudios(dir, taskID, request, rec)
	data, err := json.Marshal(rec)
	if err != nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	f, err := os.OpenFile(filepath.Join(dir, historyIndexFile), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		log.Printf("历史记录落盘失败: %v", err)
		return
	}
	f.Write(append(data, '\n'))
	f.Close()
	m.index[modelID] = append(m.index[modelID], rec)
}

// normalizeVoice 归一化声音来源：voice_ref / speaker(+instruct) / instruct / default，另附情感参考 audio。
func normalizeVoice(request map[string]any) map[string]any {
	voice := map[string]any{}
	if s := optString(request, "voice_ref"); s != "" {
		voice["kind"] = "voice_ref"
		voice["voiceRef"] = s
		if t := optString(request, "reference_text"); t != "" {
			voice["referenceText"] = t
		}
	} else if s := optString(request, "speaker"); s != "" {
		voice["kind"] = "speaker"
		voice["speaker"] = s
		if t := optString(request, "instruct"); t != "" {
			voice["instruct"] = t
		}
	} else if s := optString(request, "instruct"); s != "" {
		voice["kind"] = "instruct"
		voice["instruct"] = s
	} else {
		voice["kind"] = "default"
	}
	if s := optString(request, "audio"); s != "" {
		voice["audio"] = s
	}
	return voice
}

// snapshotRefAudios 复制请求里的参考音频为快照文件 <taskId>.<快照名>.wav：
// voice_ref→ref、顶层 audio（情感参考）→emo、options.voice_samples（逗号拼接）逐项→spk0/spk1/…。
// 成功的在 rec.refs 记录 {快照名:原始文件名} 并汇总 refBytes；源缺失/超限/IO 失败只记日志跳过。
func snapshotRefAudios(dir, taskID string, request map[string]any, rec map[string]any) {
	type source struct{ name, path string }
	var sources []source
	if s := optString(request, "voice_ref"); s != "" {
		sources = append(sources, source{"ref", s})
	}
	if s := optString(request, "audio"); s != "" {
		sources = append(sources, source{"emo", s})
	}
	if options, ok := request["options"].(map[string]any); ok {
		if s := optString(options, "voice_samples"); s != "" {
			i := 0
			for _, p := range strings.Split(s, ",") {
				if p = strings.TrimSpace(p); p != "" {
					sources = append(sources, source{"spk" + itoa(i), p})
					i++
				}
			}
		}
	}
	if len(sources) == 0 {
		return
	}
	refs := map[string]any{}
	var total int64
	for _, src := range sources {
		st, err := os.Stat(src.path)
		if err != nil || !st.Mode().IsRegular() || st.Size() > historyMaxRefBytes {
			log.Printf("参考音频不存在或超过 50MB，跳过快照: %s", src.path)
			continue
		}
		if err := copyFile(src.path, filepath.Join(dir, taskID+"."+src.name+".wav")); err != nil {
			log.Printf("参考音频快照失败: %s (%v)", src.path, err)
			continue
		}
		refs[src.name] = filepath.Base(src.path)
		total += st.Size()
	}
	if len(refs) > 0 {
		rec["refs"] = refs
		rec["refBytes"] = total
	}
}

// deleteHistoryTaskFiles 删除任务的全部伴随文件：结果 wav + 快照 + 残留临时文件（前缀 taskId + "."）。
func deleteHistoryTaskFiles(modelID, taskID string) {
	dir := historyDir(modelID)
	files, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	prefix := taskID + "."
	for _, f := range files {
		if st, err := f.Info(); err == nil && st.Mode().IsRegular() && strings.HasPrefix(f.Name(), prefix) {
			os.Remove(filepath.Join(dir, f.Name()))
		}
	}
}

// List 简要列表（新→旧）：taskId/time/instanceName/ok/text(截断)/error/result{durationSec,size}/groupId。
func (m *HistoryManager) List(modelID string) []map[string]any {
	m.mu.Lock()
	records := m.index[modelID]
	m.mu.Unlock()
	out := []map[string]any{}
	for i := len(records) - 1; i >= 0; i-- {
		rec := records[i]
		item := map[string]any{
			"taskId":       rec["taskId"],
			"time":         rec["time"],
			"instanceName": rec["instanceName"],
			"ok":           rec["ok"],
		}
		if s, ok := rec["text"].(string); ok {
			if len([]rune(s)) > historyListTextMax {
				item["text"] = truncateRunes(s, historyListTextMax)
				item["textTruncated"] = true
			} else {
				item["text"] = s
			}
		}
		if g, ok := rec["groupId"]; ok {
			item["groupId"] = g
		}
		if e, ok := rec["error"]; ok {
			item["error"] = e
		}
		if r, ok := rec["result"].(map[string]any); ok {
			item["result"] = map[string]any{"durationSec": r["durationSec"], "size": r["size"]}
		}
		out = append(out, item)
	}
	return out
}

// Get 完整记录（含 voice/options/refs，供前端重新载入参数）；不存在返回 nil。
func (m *HistoryManager) Get(modelID, taskID string) map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.findLocked(modelID, taskID)
}

func (m *HistoryManager) findLocked(modelID, taskID string) map[string]any {
	for _, rec := range m.index[modelID] {
		if optString(rec, "taskId") == taskID {
			return rec
		}
	}
	return nil
}

// AudioPath 结果音频路径；key 非法、记录或文件不存在返回空串。
func (m *HistoryManager) AudioPath(modelID, taskID string) string {
	if !historySafeKey.MatchString(modelID) || !historySafeKey.MatchString(taskID) {
		return ""
	}
	m.mu.Lock()
	found := m.findLocked(modelID, taskID) != nil
	m.mu.Unlock()
	if !found {
		return ""
	}
	p := filepath.Join(historyDir(modelID), taskID+".wav")
	if isRegularFile(p) {
		return p
	}
	return ""
}

// RefAudioPath 参考音频快照文件路径（<taskId>.<name>.wav）；key/name 非法或文件不存在返回空串。
func (m *HistoryManager) RefAudioPath(modelID, taskID, name string) string {
	if !historySafeKey.MatchString(modelID) || !historySafeKey.MatchString(taskID) ||
		!historyRefName.MatchString(name) {
		return ""
	}
	p := filepath.Join(historyDir(modelID), taskID+"."+name+".wav")
	if isRegularFile(p) {
		return p
	}
	return ""
}

// Delete 单删一条记录（含 wav 与参考音频快照），不存在返回 false。
func (m *HistoryManager) Delete(modelID, taskID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !historySafeKey.MatchString(taskID) {
		return false
	}
	records := m.index[modelID]
	for i, rec := range records {
		if optString(rec, "taskId") == taskID {
			m.index[modelID] = append(records[:i], records[i+1:]...)
			deleteHistoryTaskFiles(modelID, taskID)
			m.rewriteLocked(modelID)
			return true
		}
	}
	return false
}

// Clear 清空某模型的全部历史（分组保留）。
func (m *HistoryManager) Clear(modelID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	records := m.index[modelID]
	delete(m.index, modelID)
	if !historySafeKey.MatchString(modelID) {
		return
	}
	for _, rec := range records {
		deleteHistoryTaskFiles(modelID, optString(rec, "taskId"))
	}
	os.Remove(filepath.Join(historyDir(modelID), historyIndexFile))
}

// rewriteLocked 按内存索引重写 index.jsonl；空则删除文件（调用方须持锁）。
func (m *HistoryManager) rewriteLocked(modelID string) {
	indexFile := filepath.Join(historyDir(modelID), historyIndexFile)
	records := m.index[modelID]
	if len(records) == 0 {
		os.Remove(indexFile)
		return
	}
	var sb strings.Builder
	for _, rec := range records {
		if data, err := json.Marshal(rec); err == nil {
			sb.Write(data)
			sb.WriteByte('\n')
		}
	}
	if err := writeFileAtomic(indexFile, []byte(sb.String())); err != nil {
		log.Printf("历史索引重写失败: %s: %v", modelID, err)
	}
}

// ---------- 手动分组（groups.json） ----------

// ListGroups 分组列表（按创建顺序）。modelId 非法返回空列表。
func (m *HistoryManager) ListGroups(modelID string) []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !historySafeKey.MatchString(modelID) {
		return []map[string]any{}
	}
	return m.readGroupsLocked(modelID)
}

// CreateGroup 新建分组：名称去空白后必填且不超过 50 字，同模型内不重名；返回组对象。
func (m *HistoryManager) CreateGroup(modelID, name string) (map[string]any, error) {
	trimmed, err := validateGroupName(name)
	if err != nil {
		return nil, err
	}
	if !historySafeKey.MatchString(modelID) {
		return nil, &UserError{Code: "MODEL_ID_INVALID",
			Params: map[string]any{"modelId": modelID}, Msg: "模型 ID 非法: " + modelID}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	groups := m.readGroupsLocked(modelID)
	for _, g := range groups {
		if optString(g, "name") == trimmed {
			return nil, &UserError{Code: "GROUP_EXISTS",
				Params: map[string]any{"name": trimmed}, Msg: "分组已存在: " + trimmed}
		}
	}
	group := map[string]any{
		"id":        newID(),
		"name":      trimmed,
		"createdAt": time.Now().UnixMilli(),
	}
	groups = append(groups, group)
	if err := m.writeGroupsLocked(modelID, groups); err != nil {
		return nil, err
	}
	return group, nil
}

// RenameGroup 重命名分组；组不存在返回 (false, nil)。
func (m *HistoryManager) RenameGroup(modelID, groupID, name string) (bool, error) {
	trimmed, err := validateGroupName(name)
	if err != nil {
		return false, err
	}
	if !historySafeKey.MatchString(modelID) || !historySafeKey.MatchString(groupID) {
		return false, nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	groups := m.readGroupsLocked(modelID)
	for _, g := range groups {
		if optString(g, "name") == trimmed && optString(g, "id") != groupID {
			return false, &UserError{Code: "GROUP_EXISTS",
				Params: map[string]any{"name": trimmed}, Msg: "分组已存在: " + trimmed}
		}
	}
	for _, g := range groups {
		if optString(g, "id") == groupID {
			g["name"] = trimmed
			if err := m.writeGroupsLocked(modelID, groups); err != nil {
				return false, err
			}
			return true, nil
		}
	}
	return false, nil
}

// DeleteGroup 删除分组：先把该模型所有记录的 groupId 字段移除（回未分组），再删分组条目。
func (m *HistoryManager) DeleteGroup(modelID, groupID string) bool {
	if !historySafeKey.MatchString(modelID) || !historySafeKey.MatchString(groupID) {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	groups := m.readGroupsLocked(modelID)
	found := -1
	for i, g := range groups {
		if optString(g, "id") == groupID {
			found = i
			break
		}
	}
	if found < 0 {
		return false
	}
	changed := false
	for _, rec := range m.index[modelID] {
		if optString(rec, "groupId") == groupID {
			delete(rec, "groupId")
			changed = true
		}
	}
	if changed {
		m.rewriteLocked(modelID)
	}
	groups = append(groups[:found], groups[found+1:]...)
	m.writeGroupsLocked(modelID, groups)
	return true
}

// SetRecordGroup 设置记录所属分组：groupId 为空表示移回未分组；非空时校验组存在。
// 记录不存在返回 (false, nil)。
func (m *HistoryManager) SetRecordGroup(modelID, taskID, groupID string) (bool, error) {
	if !historySafeKey.MatchString(modelID) || !historySafeKey.MatchString(taskID) {
		return false, nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	rec := m.findLocked(modelID, taskID)
	if rec == nil {
		return false, nil
	}
	if groupID == "" {
		delete(rec, "groupId")
	} else {
		if !historySafeKey.MatchString(groupID) || !m.groupExistsLocked(modelID, groupID) {
			return false, &UserError{Code: "GROUP_NOT_FOUND",
				Params: map[string]any{"groupId": groupID}, Msg: "分组不存在: " + groupID}
		}
		rec["groupId"] = groupID
	}
	m.rewriteLocked(modelID)
	return true, nil
}

// validateGroupName 分组名称校验：去空白后必填且不超过 50 字。
func validateGroupName(name string) (string, error) {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return "", newUserError("GROUP_NAME_REQUIRED", "分组名称不能为空")
	}
	if len([]rune(trimmed)) > historyMaxGroupName {
		return "", &UserError{Code: "GROUP_NAME_TOO_LONG",
			Params: map[string]any{"max": historyMaxGroupName},
			Msg:    "分组名称不能超过 50 字"}
	}
	return trimmed, nil
}

func (m *HistoryManager) groupExistsLocked(modelID, groupID string) bool {
	for _, g := range m.readGroupsLocked(modelID) {
		if optString(g, "id") == groupID {
			return true
		}
	}
	return false
}

// readGroupsLocked 读分组文件；不存在/为空/损坏即空列表（调用方须持锁）。
// 注意必须返回非 nil 空切片：nil 会序列化成 JSON null，前端按数组用会抛 TypeError。
func (m *HistoryManager) readGroupsLocked(modelID string) []map[string]any {
	data, err := os.ReadFile(filepath.Join(historyDir(modelID), historyGroupsFile))
	if err != nil || len(strings.TrimSpace(string(data))) == 0 {
		return []map[string]any{}
	}
	var groups []map[string]any
	if err := json.Unmarshal(data, &groups); err != nil || groups == nil {
		return []map[string]any{}
	}
	return groups
}

// writeGroupsLocked 写分组文件（整体重写，分组数量很小）（调用方须持锁）。
func (m *HistoryManager) writeGroupsLocked(modelID string, groups []map[string]any) error {
	if err := os.MkdirAll(historyDir(modelID), 0755); err != nil {
		return err
	}
	data, err := json.Marshal(groups)
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(historyDir(modelID), historyGroupsFile), data, 0644); err != nil {
		return &UserError{Code: "GROUP_SAVE_FAILED", Params: map[string]any{},
			Msg: "分组保存失败: " + err.Error()}
	}
	return nil
}

// itoa 避免为此引入 strconv 的语义噪音。
func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b [8]byte
	pos := len(b)
	for i > 0 {
		pos--
		b[pos] = byte('0' + i%10)
		i /= 10
	}
	return string(b[pos:])
}
