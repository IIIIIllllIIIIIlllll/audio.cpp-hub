package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var safeTaskID = regexp.MustCompile(`^[a-zA-Z0-9-]{1,32}$`)

// registerRoutes 注册全部 API 路由与静态文件服务。
// 未匹配的 /api/* 走 JSON 404，其余 GET 交给 web/ 静态文件。
func (h *Hub) registerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/models", h.handleModels)
	mux.HandleFunc("GET /api/models/{modelId}/packages", h.handleModelPackages)
	mux.HandleFunc("GET /api/instances", h.handleInstanceList)
	mux.HandleFunc("POST /api/instances", h.handleInstanceStart)
	mux.HandleFunc("DELETE /api/instances/{id}", h.handleInstanceStop)
	mux.HandleFunc("GET /api/events", h.handleEvents)
	mux.HandleFunc("GET /api/executables", h.handleExecList)
	mux.HandleFunc("POST /api/executables", h.handleExecAdd)
	mux.HandleFunc("PUT /api/executables/{id}", h.handleExecUpdate)
	mux.HandleFunc("DELETE /api/executables/{id}", h.handleExecDelete)
	mux.HandleFunc("GET /api/executables/{id}/devices", h.handleExecDevices)
	mux.HandleFunc("GET /api/profiles", h.handleProfileList)
	mux.HandleFunc("POST /api/profiles", h.handleProfileAdd)
	mux.HandleFunc("PUT /api/profiles/{id}", h.handleProfileUpdate)
	mux.HandleFunc("DELETE /api/profiles/{id}", h.handleProfileDelete)
	mux.HandleFunc("POST /api/run/{id}", h.handleRun)
	mux.HandleFunc("POST /api/tasks", h.handleTaskCreate)
	mux.HandleFunc("GET /api/tasks", h.handleTaskList)
	mux.HandleFunc("GET /api/tasks/{id}", h.handleTaskGet)
	mux.HandleFunc("GET /api/tasks/{id}/result", h.handleTaskResult)
	mux.HandleFunc("DELETE /api/tasks/{id}", h.handleTaskDelete)
	mux.HandleFunc("GET /api/history/{modelId}", h.handleHistoryList)
	mux.HandleFunc("DELETE /api/history/{modelId}", h.handleHistoryClear)
	mux.HandleFunc("GET /api/history/{modelId}/{taskId}", h.handleHistoryGet)
	mux.HandleFunc("DELETE /api/history/{modelId}/{taskId}", h.handleHistoryDelete)
	mux.HandleFunc("GET /api/history/{modelId}/{taskId}/audio", h.handleHistoryAudio)
	mux.HandleFunc("GET /api/history/{modelId}/{taskId}/audio/{name}", h.handleHistoryRefAudio)
	// PUT/DELETE 的四段路径合用一个通配模式再分发（groups/{gid} 与 {taskId}/group 在 mux 里互相冲突）
	mux.HandleFunc("PUT /api/history/{modelId}/{seg3}/{seg4}", h.handleHistoryPut4)
	mux.HandleFunc("DELETE /api/history/{modelId}/{seg3}/{seg4}", h.handleHistoryDelete4)
	mux.HandleFunc("GET /api/history/{modelId}/groups", h.handleHistoryGroupList)
	mux.HandleFunc("POST /api/history/{modelId}/groups", h.handleHistoryGroupCreate)
	mux.HandleFunc("POST /api/audio/upload", h.handleAudioUpload)
	mux.HandleFunc("POST /api/audio/info", h.handleAudioInfo)
	mux.HandleFunc("GET /api/audio/file", h.handleAudioFile)
	mux.HandleFunc("GET /api/voices", h.handleVoiceList)
	mux.HandleFunc("POST /api/voices", h.handleVoiceSave)
	mux.HandleFunc("PUT /api/voices/{vid}", h.handleVoiceUpdate)
	mux.HandleFunc("DELETE /api/voices/{vid}", h.handleVoiceDelete)
	mux.HandleFunc("GET /api/voices/{vid}/audio", h.handleVoiceAudio)
	mux.HandleFunc("GET /api/fs/roots", h.handleFsRoots)
	mux.HandleFunc("GET /api/fs/list", h.handleFsList)
	mux.HandleFunc("GET /api/fs/stat", h.handleFsStat)
	mux.HandleFunc("POST /api/fs/mkdir", h.handleFsMkdir)
	mux.HandleFunc("GET /api/downloads", h.handleDownloadList)
	mux.HandleFunc("POST /api/downloads", h.handleDownloadCreate)
	mux.HandleFunc("GET /api/downloads/{id}", h.handleDownloadGet)
	mux.HandleFunc("DELETE /api/downloads/{id}", h.handleDownloadDelete)
	mux.HandleFunc("POST /api/downloads/{id}/pause", h.handleDownloadPause)
	mux.HandleFunc("POST /api/downloads/{id}/resume", h.handleDownloadResume)
	mux.HandleFunc("/v1/", h.handleV1Proxy)
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		errJSON(w, http.StatusNotFound, "UNKNOWN_API", map[string]any{"path": r.URL.Path}, "unknown api: "+r.URL.Path)
	})
	mux.Handle("/", http.FileServer(http.Dir("web")))
}

// ---------- 模型清单 ----------

func (h *Hub) handleModels(w http.ResponseWriter, r *http.Request) {
	writeRawJSON(w, http.StatusOK, modelsRaw)
}

// ---------- 实例 ----------

func (h *Hub) handleInstanceList(w http.ResponseWriter, r *http.Request) {
	out := []map[string]any{}
	for _, inst := range h.instances.List() {
		out = append(out, h.instances.ToJSON(inst, h.tasks.ActiveCountFor(inst.ID)))
	}
	writeJSON(w, http.StatusOK, out)
}

// handleInstanceStart 启动实例：{"modelId","weightsPath","backend"?,"device"?,"port"?,
// "threads"?,"executableId"?,"name"?,"sessionOptions"?}
func (h *Hub) handleInstanceStart(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	modelID := optString(body, "modelId")
	weightsPath := optString(body, "weightsPath")
	modelEntry := findModel(modelID)
	if modelEntry == nil {
		errJSON(w, http.StatusBadRequest, "MODEL_UNKNOWN", map[string]any{"modelId": modelID},
			"modelId 不在注册表中: "+modelID)
		return
	}
	if weightsPath == "" {
		errJSON(w, http.StatusBadRequest, "WEIGHTS_REQUIRED", nil, "weightsPath 不能为空")
		return
	}
	if !pathExists(weightsPath) {
		errJSON(w, http.StatusBadRequest, "WEIGHTS_NOT_FOUND", map[string]any{"path": weightsPath},
			"权重路径不存在: "+weightsPath)
		return
	}
	// 相对路径绝对化，确保写入 server.json 的是绝对路径
	absWeights, err := filepath.Abs(weightsPath)
	if err != nil {
		absWeights = weightsPath
	}
	backend := optString(body, "backend")
	if backend == "" {
		backend = "cpu"
	}
	threads := optIntPtr(body, "threads")
	if threads != nil && *threads <= 0 {
		errJSON(w, http.StatusBadRequest, "THREADS_POSITIVE", nil, "threads 必须为正整数")
		return
	}
	var execEntry *Executable
	if execID := optString(body, "executableId"); execID != "" {
		execEntry = h.execs.FindByID(execID)
		if execEntry == nil {
			errJSON(w, http.StatusBadRequest, "EXEC_NOT_FOUND", map[string]any{"id": execID},
				"可执行文件不存在: "+execID)
			return
		}
	} else {
		execEntry = h.execs.First()
		if execEntry == nil {
			errJSON(w, http.StatusBadRequest, "NO_EXECUTABLE", nil,
				"尚未配置可执行文件，请点击右上角设置添加")
			return
		}
	}
	execPath, err := resolveExecPath(execEntry.Path)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "EXEC_NOT_FOUND", err)
		return
	}
	serverTask := "tts"
	if s, ok := modelEntry["serverTask"].(string); ok {
		serverTask = s
	}
	engineFamily := modelID
	if s, ok := modelEntry["family"].(string); ok && s != "" {
		engineFamily = s
	}
	sessionOptions, err := optStringMap(body, "sessionOptions")
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "OPTIONS_INVALID", err)
		return
	}
	inst, err := h.instances.Start(StartParams{
		ModelID:        modelID,
		EngineFamily:   engineFamily,
		WeightsPath:    absWeights,
		Backend:        backend,
		Device:         optIntPtr(body, "device"),
		Port:           optIntPtr(body, "port"),
		Threads:        threads,
		ExecPath:       execPath,
		ExecName:       execEntry.Name,
		ServerTask:     serverTask,
		Env:            execEntry.Env,
		Name:           optString(body, "name"),
		SessionOptions: sessionOptions,
	})
	if err != nil {
		errFromErr(w, http.StatusInternalServerError, "LAUNCH_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, h.instances.ToJSON(inst, 0))
}

func (h *Hub) handleInstanceStop(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if h.instances.Stop(id) {
		okJSON(w, map[string]any{"id": id})
	} else {
		errJSON(w, http.StatusNotFound, "INSTANCE_NOT_FOUND", map[string]any{"id": id}, "实例不存在: "+id)
	}
}

func (h *Hub) handleEvents(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.instances.Events())
}

// ---------- 可执行文件 ----------

func (h *Hub) handleExecList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.execs.List())
}

// handleExecAdd 添加：{"name","path","note"?,"env"?}
func (h *Hub) handleExecAdd(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	env, _ := optStringMap(body, "env")
	entry, err := h.execs.Add(optString(body, "name"), optString(body, "path"),
		optString(body, "note"), env)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "EXEC_ADD_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, entry)
}

func (h *Hub) handleExecUpdate(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	env, _ := optStringMap(body, "env")
	entry, err := h.execs.Update(id, optString(body, "name"), optString(body, "path"),
		optString(body, "note"), env)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "EXEC_UPDATE_FAILED", err)
		return
	}
	if entry == nil {
		errJSON(w, http.StatusNotFound, "EXEC_NOT_FOUND", map[string]any{"id": id}, "可执行文件不存在: "+id)
		return
	}
	writeJSON(w, http.StatusOK, entry)
}

func (h *Hub) handleExecDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if h.execs.Delete(id) {
		okJSON(w, map[string]any{"id": id})
	} else {
		errJSON(w, http.StatusNotFound, "EXEC_NOT_FOUND", map[string]any{"id": id}, "可执行文件不存在: "+id)
	}
}

// handleExecDevices 设备探测：运行 <可执行文件> --list-devices 并解析输出。
func (h *Hub) handleExecDevices(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	execEntry := h.execs.FindByID(id)
	if execEntry == nil {
		errJSON(w, http.StatusNotFound, "EXEC_NOT_FOUND", map[string]any{"id": id}, "可执行文件不存在: "+id)
		return
	}
	execPath, err := resolveExecPath(execEntry.Path)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "FILE_NOT_FOUND", err)
		return
	}
	result, err := ListDevices(execPath, execEntry.Env)
	if err != nil {
		errJSON(w, http.StatusInternalServerError, "DEVICE_LIST_FAILED", nil, summarize(err.Error()))
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// ---------- 启动配置（Profile） ----------

func (h *Hub) handleProfileList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.profiles.List())
}

func (h *Hub) handleProfileAdd(w http.ResponseWriter, r *http.Request) {
	h.handleProfileSave(w, r, "")
}

func (h *Hub) handleProfileUpdate(w http.ResponseWriter, r *http.Request) {
	h.handleProfileSave(w, r, r.PathValue("id"))
}

// handleProfileSave 新增/更新启动配置，校验与 Java 版一致。
func (h *Hub) handleProfileSave(w http.ResponseWriter, r *http.Request, id string) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	name := optString(body, "name")
	modelID := optString(body, "modelId")
	weightsPath := optString(body, "weightsPath")
	if name == "" {
		errJSON(w, http.StatusBadRequest, "NAME_REQUIRED", nil, "name 不能为空")
		return
	}
	if findModel(modelID) == nil {
		errJSON(w, http.StatusBadRequest, "MODEL_UNKNOWN", map[string]any{"modelId": modelID},
			"modelId 不在注册表中: "+modelID)
		return
	}
	if weightsPath == "" {
		errJSON(w, http.StatusBadRequest, "WEIGHTS_REQUIRED", nil, "weightsPath 不能为空")
		return
	}
	if optString(body, "backend") == "" {
		body["backend"] = "cpu"
	}
	entry, err := h.profiles.Save(id, body)
	if err != nil {
		errJSON(w, http.StatusInternalServerError, "PROFILE_SAVE_FAILED", nil, "保存配置失败: "+summarize(err.Error()))
		return
	}
	if entry == nil {
		errJSON(w, http.StatusNotFound, "PROFILE_NOT_FOUND", map[string]any{"id": id}, "配置不存在: "+id)
		return
	}
	writeJSON(w, http.StatusOK, entry)
}

func (h *Hub) handleProfileDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if h.profiles.Delete(id) {
		okJSON(w, map[string]any{"id": id})
	} else {
		errJSON(w, http.StatusNotFound, "PROFILE_NOT_FOUND", map[string]any{"id": id}, "配置不存在: "+id)
	}
}

// ---------- 同步任务转发（兼容旧链路） ----------

// handleRun POST /api/run/<instanceId>：body {"request":{...}} → 实例 /v1/tasks/run。
func (h *Hub) handleRun(w http.ResponseWriter, r *http.Request) {
	inst := h.instances.Get(r.PathValue("id"))
	if inst == nil {
		errJSON(w, http.StatusNotFound, "INSTANCE_NOT_FOUND", nil, "实例不存在: "+r.PathValue("id"))
		return
	}
	if inst.Status != "READY" {
		errJSON(w, http.StatusConflict, "INSTANCE_NOT_READY", map[string]any{"status": inst.Status},
			"实例未就绪，当前状态: "+inst.Status)
		return
	}
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	request := requestMap(body)
	requestRaw, _ := json.Marshal(request)
	if modelCategory(inst.ModelID) != "tts" {
		// 非 TTS：响应 JSON 原样透传
		tmp, err := os.CreateTemp("", "audiohub-run-*.json")
		if err != nil {
			errJSON(w, http.StatusInternalServerError, "FORWARD_FAILED", nil, summarize(err.Error()))
			return
		}
		defer os.Remove(tmp.Name())
		defer tmp.Close()
		if err := h.tasks.forwardToFile(r.Context(), inst, requestRaw, tmp.Name()); err != nil {
			errJSON(w, http.StatusBadGateway, "FORWARD_FAILED", nil, summarize(err.Error()))
			return
		}
		tmp.Seek(0, io.SeekStart)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		io.Copy(w, tmp)
		return
	}
	// TTS：响应落盘 → 提取音频进历史 → 临时文件流式回写
	taskID := newID()
	dir := filepath.Join("data", "history", inst.ModelID)
	if err := os.MkdirAll(dir, 0755); err != nil {
		errJSON(w, http.StatusInternalServerError, "HISTORY_IO", nil, "历史目录不可用: "+err.Error())
		return
	}
	tmp := filepath.Join(dir, taskID+".resp.tmp")
	if err := h.tasks.forwardToFile(r.Context(), inst, requestRaw, tmp); err != nil {
		h.history.RecordTTS(inst, request, taskID, nil, summarize(err.Error()))
		os.Remove(tmp)
		errJSON(w, http.StatusBadGateway, "FORWARD_FAILED", nil, summarize(err.Error()))
		return
	}
	var result map[string]any
	var errMsg string
	wav := filepath.Join(dir, taskID+".wav")
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
		var size int64
		if st, err := os.Stat(wav); err == nil {
			size = st.Size()
		}
		result = map[string]any{
			"file":        taskID + ".wav",
			"size":        size,
			"durationSec": round3(info.durationSec),
			"sampleRate":  info.sampleRate,
			"channels":    info.channels,
		}
	}
	h.history.RecordTTS(inst, request, taskID, result, errMsg)
	// 响应 JSON 原样回写前端（写完删临时文件）
	f, err := os.Open(tmp)
	if err != nil {
		errJSON(w, http.StatusInternalServerError, "FORWARD_FAILED", nil, summarize(err.Error()))
		return
	}
	defer func() {
		f.Close()
		os.Remove(tmp)
	}()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	io.Copy(w, f)
}

// requestMap 取 body["request"] 对象，缺失返回空对象。
func requestMap(body map[string]any) map[string]any {
	if req, ok := body["request"].(map[string]any); ok {
		return req
	}
	return map[string]any{}
}

// ---------- 异步推理任务 ----------

// handleTaskCreate 创建任务：body {"instanceId","request":{...}}，202 返回任务详情。
func (h *Hub) handleTaskCreate(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	instanceID := optString(body, "instanceId")
	if instanceID == "" {
		errJSON(w, http.StatusBadRequest, "INSTANCE_REQUIRED", nil, "缺少 instanceId")
		return
	}
	inst := h.instances.Get(instanceID)
	if inst == nil {
		errJSON(w, http.StatusNotFound, "INSTANCE_NOT_FOUND", map[string]any{"id": instanceID},
			"实例不存在: "+instanceID)
		return
	}
	if inst.Status != "READY" {
		errJSON(w, http.StatusConflict, "INSTANCE_NOT_READY", map[string]any{"status": inst.Status},
			"实例未就绪，当前状态: "+inst.Status)
		return
	}
	request := requestMap(body)
	requestRaw, _ := json.Marshal(request)
	task := h.tasks.Submit(inst, request, requestRaw)
	writeJSON(w, http.StatusAccepted, h.tasks.Get(task.ID))
}

func (h *Hub) handleTaskList(w http.ResponseWriter, r *http.Request) {
	activeOnly := r.URL.Query().Get("active") == "1"
	writeJSON(w, http.StatusOK, h.tasks.List(activeOnly, r.URL.Query().Get("modelId")))
}

func (h *Hub) handleTaskGet(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !safeTaskID.MatchString(id) {
		errJSON(w, http.StatusNotFound, "TASK_NOT_FOUND", map[string]any{"id": id}, "任务不存在: "+id)
		return
	}
	task := h.tasks.Get(id)
	if task == nil {
		errJSON(w, http.StatusNotFound, "TASK_NOT_FOUND", map[string]any{"id": id}, "任务不存在: "+id)
		return
	}
	writeJSON(w, http.StatusOK, task)
}

// handleTaskResult 非 TTS 结果文件流式回写（TTS 结果走 /api/history/.../audio）。
func (h *Hub) handleTaskResult(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !safeTaskID.MatchString(id) {
		errJSON(w, http.StatusNotFound, "TASK_NOT_FOUND", map[string]any{"id": id}, "任务不存在: "+id)
		return
	}
	resultFile := h.tasks.ResultPath(id)
	if resultFile == "" || !isRegularFile(resultFile) {
		errJSON(w, http.StatusNotFound, "RESULT_NOT_FOUND", map[string]any{"id": id},
			"结果不存在（TTS 结果请走 /api/history）: "+id)
		return
	}
	f, err := os.Open(resultFile)
	if err != nil {
		errJSON(w, http.StatusInternalServerError, "RESULT_IO", nil, summarize(err.Error()))
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	io.Copy(w, f)
}

func (h *Hub) handleTaskDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if h.tasks.Cancel(id) {
		okJSON(w, map[string]any{"id": id})
	} else {
		errJSON(w, http.StatusNotFound, "TASK_NOT_FOUND", map[string]any{"id": id}, "任务不存在: "+id)
	}
}

// ---------- TTS 操作历史 ----------

func (h *Hub) historyKeysOK(w http.ResponseWriter, modelID, taskID string) bool {
	if !historySafeKey.MatchString(modelID) || (taskID != "" && !historySafeKey.MatchString(taskID)) {
		errJSON(w, http.StatusNotFound, "HISTORY_NOT_FOUND", nil, "历史记录不存在")
		return false
	}
	return true
}

func (h *Hub) handleHistoryList(w http.ResponseWriter, r *http.Request) {
	modelID := r.PathValue("modelId")
	if !h.historyKeysOK(w, modelID, "") {
		return
	}
	writeJSON(w, http.StatusOK, h.history.List(modelID))
}

func (h *Hub) handleHistoryGet(w http.ResponseWriter, r *http.Request) {
	modelID, taskID := r.PathValue("modelId"), r.PathValue("taskId")
	if !h.historyKeysOK(w, modelID, taskID) {
		return
	}
	rec := h.history.Get(modelID, taskID)
	if rec == nil {
		errJSON(w, http.StatusNotFound, "HISTORY_NOT_FOUND", nil, "历史记录不存在")
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func (h *Hub) handleHistoryAudio(w http.ResponseWriter, r *http.Request) {
	modelID, taskID := r.PathValue("modelId"), r.PathValue("taskId")
	if !h.historyKeysOK(w, modelID, taskID) {
		return
	}
	wav := h.history.AudioPath(modelID, taskID)
	if wav == "" {
		errJSON(w, http.StatusNotFound, "HISTORY_NOT_FOUND", nil, "历史音频不存在")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	http.ServeFile(w, r, wav)
}

func (h *Hub) handleHistoryDelete(w http.ResponseWriter, r *http.Request) {
	modelID, taskID := r.PathValue("modelId"), r.PathValue("taskId")
	if !h.historyKeysOK(w, modelID, taskID) {
		return
	}
	if h.history.Delete(modelID, taskID) {
		okJSON(w, map[string]any{"taskId": taskID})
	} else {
		errJSON(w, http.StatusNotFound, "HISTORY_NOT_FOUND", nil, "历史记录不存在")
	}
}

func (h *Hub) handleHistoryClear(w http.ResponseWriter, r *http.Request) {
	modelID := r.PathValue("modelId")
	if !h.historyKeysOK(w, modelID, "") {
		return
	}
	h.history.Clear(modelID)
	okJSON(w, map[string]any{"modelId": modelID})
}

// ---------- 音频上传 ----------

// handleAudioUpload 上传 WAV（body 为原始字节，上限 50MB），保存到 data/uploads/。
func (h *Hub) handleAudioUpload(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxUploadBytes+1))
	if err != nil || len(data) > maxUploadBytes {
		errJSON(w, http.StatusRequestEntityTooLarge, "FILE_TOO_LARGE", nil, "文件超过 50MB 上限")
		return
	}
	info, err := saveUpload(data)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "UPLOAD_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, info)
}

// handleAudioInfo 探测本地路径的 WAV 信息：{"path"}。
func (h *Hub) handleAudioInfo(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	path := optString(body, "path")
	if path == "" {
		errJSON(w, http.StatusBadRequest, "PATH_REQUIRED", nil, "path 不能为空")
		return
	}
	info, err := probeWAV(path)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "PROBE_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, info)
}

// handleAudioFile 读取上传件音频：?id=...
func (h *Hub) handleAudioFile(w http.ResponseWriter, r *http.Request) {
	path := uploadPath(r.URL.Query().Get("id"))
	if path == "" {
		errJSON(w, http.StatusNotFound, "UPLOAD_NOT_FOUND", nil, "上传件不存在或 id 非法")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	http.ServeFile(w, r, path)
}

// ---------- 音色库 ----------

func (h *Hub) handleVoiceList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.voices.List())
}

// handleVoiceSave 保存音色：{"name","text"?,"uploadId"} 或 {"name","text"?,"path"}。
func (h *Hub) handleVoiceSave(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	entry, err := h.voices.Save(optString(body, "name"), optString(body, "text"),
		optString(body, "uploadId"), optString(body, "path"))
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "VOICE_SAVE_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, entry)
}

// handleVoiceUpdate 更新音色名称与文本内容：{"name"?,"text"?}，缺省字段不修改。
func (h *Hub) handleVoiceUpdate(w http.ResponseWriter, r *http.Request) {
	vid := r.PathValue("vid")
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	var name, text *string
	if _, ok := body["name"]; ok {
		s := optString(body, "name")
		name = &s
	}
	if _, ok := body["text"]; ok {
		s := optString(body, "text")
		text = &s
	}
	found, err := h.voices.Update(vid, name, text)
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "VOICE_UPDATE_FAILED", err)
		return
	}
	if !found {
		errJSON(w, http.StatusNotFound, "VOICE_NOT_FOUND", map[string]any{"id": vid}, "音色不存在: "+vid)
		return
	}
	okJSON(w, map[string]any{"vid": vid})
}

func (h *Hub) handleVoiceDelete(w http.ResponseWriter, r *http.Request) {
	vid := r.PathValue("vid")
	if h.voices.Delete(vid) {
		okJSON(w, map[string]any{"vid": vid})
	} else {
		errJSON(w, http.StatusNotFound, "VOICE_NOT_FOUND", map[string]any{"id": vid}, "音色不存在: "+vid)
	}
}

func (h *Hub) handleVoiceAudio(w http.ResponseWriter, r *http.Request) {
	vid := r.PathValue("vid")
	path := h.voices.AudioPath(vid)
	if path == "" {
		errJSON(w, http.StatusNotFound, "VOICE_NOT_FOUND", map[string]any{"id": vid}, "音色不存在: "+vid)
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	http.ServeFile(w, r, path)
}

// ---------- 文件浏览 ----------

func (h *Hub) handleFsRoots(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, fsRoots())
}

func (h *Hub) handleFsList(w http.ResponseWriter, r *http.Request) {
	result, err := fsList(r.URL.Query().Get("path"))
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "FS_LIST_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (h *Hub) handleFsStat(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, fsStat(r.URL.Query().Get("path")))
}

// handleFsMkdir 新建文件夹：{"parent","name"}。
func (h *Hub) handleFsMkdir(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	entry, err := fsMkdir(optString(body, "parent"), optString(body, "name"))
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "FS_MKDIR_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, entry)
}

// ---------- 历史参考音频快照与分组 ----------

// handleHistoryRefAudio 参考音频快照：GET /api/history/<modelId>/<taskId>/audio/<name>（name 为 ref|emo|spkN）。
func (h *Hub) handleHistoryRefAudio(w http.ResponseWriter, r *http.Request) {
	modelID, taskID, name := r.PathValue("modelId"), r.PathValue("taskId"), r.PathValue("name")
	wav := h.history.RefAudioPath(modelID, taskID, name)
	if wav == "" {
		errJSON(w, http.StatusNotFound, "HISTORY_NOT_FOUND", nil, "历史参考音频不存在")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	http.ServeFile(w, r, wav)
}

// handleHistoryPut4 PUT 四段路径分发：.../groups/<gid> 重命名分组，.../<taskId>/group 设置记录分组。
func (h *Hub) handleHistoryPut4(w http.ResponseWriter, r *http.Request) {
	modelID, seg3, seg4 := r.PathValue("modelId"), r.PathValue("seg3"), r.PathValue("seg4")
	if seg3 == "groups" {
		h.handleHistoryGroupRename(w, r, modelID, seg4)
		return
	}
	if seg4 == "group" {
		h.handleHistorySetGroup(w, r, modelID, seg3)
		return
	}
	errJSON(w, http.StatusNotFound, "UNKNOWN_API", map[string]any{"path": r.URL.Path}, "unknown api: "+r.URL.Path)
}

// handleHistoryDelete4 DELETE 四段路径分发：.../groups/<gid> 删除分组，.../<taskId>/xxx 不存在。
func (h *Hub) handleHistoryDelete4(w http.ResponseWriter, r *http.Request) {
	if r.PathValue("seg3") == "groups" {
		h.handleHistoryGroupDelete(w, r, r.PathValue("modelId"), r.PathValue("seg4"))
		return
	}
	errJSON(w, http.StatusNotFound, "UNKNOWN_API", map[string]any{"path": r.URL.Path}, "unknown api: "+r.URL.Path)
}

// handleHistorySetGroup 设置记录分组：PUT .../<taskId>/group，body {"groupId"}（空为移回未分组）。
func (h *Hub) handleHistorySetGroup(w http.ResponseWriter, r *http.Request, modelID, taskID string) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	found, err := h.history.SetRecordGroup(modelID, taskID, optString(body, "groupId"))
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "GROUP_SET_FAILED", err)
		return
	}
	if !found {
		errJSON(w, http.StatusNotFound, "TASK_NOT_FOUND", nil, "历史记录不存在")
		return
	}
	okJSON(w, map[string]any{"taskId": taskID})
}

func (h *Hub) handleHistoryGroupList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.history.ListGroups(r.PathValue("modelId")))
}

func (h *Hub) handleHistoryGroupCreate(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	group, err := h.history.CreateGroup(r.PathValue("modelId"), optString(body, "name"))
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "GROUP_CREATE_FAILED", err)
		return
	}
	writeJSON(w, http.StatusOK, group)
}

func (h *Hub) handleHistoryGroupRename(w http.ResponseWriter, r *http.Request, modelID, gid string) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	found, err := h.history.RenameGroup(modelID, gid, optString(body, "name"))
	if err != nil {
		errFromErr(w, http.StatusBadRequest, "GROUP_RENAME_FAILED", err)
		return
	}
	if !found {
		errJSON(w, http.StatusNotFound, "GROUP_NOT_FOUND", nil, "分组不存在")
		return
	}
	okJSON(w, map[string]any{"id": gid})
}

func (h *Hub) handleHistoryGroupDelete(w http.ResponseWriter, r *http.Request, modelID, gid string) {
	if h.history.DeleteGroup(modelID, gid) {
		okJSON(w, map[string]any{"id": gid})
	} else {
		errJSON(w, http.StatusNotFound, "GROUP_NOT_FOUND", nil, "分组不存在")
	}
}

// ---------- 模型权重下载 ----------

// handleModelPackages 返回模型的下载包清单（model-packages.json 原始 JSON）。
func (h *Hub) handleModelPackages(w http.ResponseWriter, r *http.Request) {
	modelID := r.PathValue("modelId")
	family := findPackageFamily(modelID)
	if family == nil {
		errJSON(w, http.StatusBadRequest, "MODEL_UNKNOWN", map[string]any{"modelId": modelID},
			"模型无下载清单: "+modelID)
		return
	}
	writeRawJSON(w, http.StatusOK, family)
}

// handleDownloadCreate 创建下载任务，两种形式：
// 1) {"modelId","packageId"?,"token"?,"overwrite"?,"endpoint"?,"source"?} — 按清单生成文件列表
//    （packageId 缺省取 default 包；source 缺省/"hf" 用 hfEndpoint 或 body endpoint 覆盖，
//    "modelscope" 时改用 modelscope.cn 镜像，repo 映射 HereIsMark/<名字>、revision 固定 master）；
// 2) {"targetDir","files":[{"url","path"},...],"token"?,"overwrite"?} — 显式文件列表。
// 权重落盘 models/<targetDir>/，创建后自动开始，返回任务详情（含分段与进度）。
func (h *Hub) handleDownloadCreate(w http.ResponseWriter, r *http.Request) {
	body := readBodyMap(w, r)
	if body == nil {
		return
	}
	overwrite, _ := body["overwrite"].(bool)
	token := optString(body, "token")
	modelID := optString(body, "modelId")
	var packageID, source, targetDir string
	var files []dlFileRequest
	if modelID != "" {
		family := findPackageFamily(modelID)
		if family == nil {
			errJSON(w, http.StatusBadRequest, "MODEL_UNKNOWN", map[string]any{"modelId": modelID},
				"模型无下载清单: "+modelID)
			return
		}
		packageID = optString(body, "packageId")
		pkg := resolvePackage(family, packageID)
		if pkg == nil {
			errJSON(w, http.StatusBadRequest, "PACKAGE_UNKNOWN",
				map[string]any{"modelId": modelID, "packageId": packageID},
				"下载包不存在: "+packageID)
			return
		}
		// 记录解析后的包 id（body 未指定时即 default 包）
		packageID = pkg.ID
		targetDir = pkg.TargetDir
		repo := pkg.Repo
		revision := pkg.Revision
		var endpoint string
		if optString(body, "source") == "modelscope" {
			source = "modelscope"
			repo = modelscopeRepo(repo)
			revision = modelscopeRevision
			endpoint = modelscopeEndpoint
		} else {
			source = "hf"
			// 下载源：body 可显式指定 endpoint 覆盖配置（仅 hf 源生效；去掉末尾斜杠）
			endpoint = h.cfg.HfEndpoint
			if ep := optString(body, "endpoint"); ep != "" {
				validated, err := validateDlURL(ep)
				if err != nil {
					downloadErr(w, err)
					return
				}
				endpoint = strings.TrimRight(validated, "/")
			}
		}
		for _, f := range pkg.Files {
			files = append(files, dlFileRequest{
				URL:  buildResolveURL(endpoint, repo, revision, f.Remote),
				Path: f.Local,
			})
		}
	} else {
		targetDir = optString(body, "targetDir")
		arr, ok := body["files"].([]any)
		if !ok || len(arr) == 0 {
			errJSON(w, http.StatusBadRequest, "FILES_REQUIRED", nil, "files 不能为空")
			return
		}
		for _, el := range arr {
			obj, ok := el.(map[string]any)
			if !ok {
				continue
			}
			files = append(files, dlFileRequest{URL: optString(obj, "url"), Path: optString(obj, "path")})
		}
	}
	detail, err := h.downloads.Create(targetDir, files, token, overwrite, modelID, packageID, source)
	if err != nil {
		downloadErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, detail)
}

func (h *Hub) handleDownloadList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.downloads.List())
}

func (h *Hub) handleDownloadGet(w http.ResponseWriter, r *http.Request) {
	detail, err := h.downloads.Get(r.PathValue("id"))
	if err != nil {
		downloadErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, detail)
}

func (h *Hub) handleDownloadPause(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := h.downloads.Pause(id); err != nil {
		downloadErr(w, err)
		return
	}
	okJSON(w, map[string]any{"id": id})
}

func (h *Hub) handleDownloadResume(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := h.downloads.Resume(id); err != nil {
		downloadErr(w, err)
		return
	}
	okJSON(w, map[string]any{"id": id})
}

// handleDownloadDelete 取消并移除任务；?purge=true 清理 .part 残留。
func (h *Hub) handleDownloadDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := h.downloads.Delete(id, r.URL.Query().Get("purge") == "true"); err != nil {
		downloadErr(w, err)
		return
	}
	okJSON(w, map[string]any{"id": id})
}

// downloadErr 下载接口错误响应：DOWNLOAD_NOT_FOUND 返回 404，其它用户错误 400，内部错误 500。
func downloadErr(w http.ResponseWriter, err error) {
	if ue, ok := err.(*UserError); ok {
		status := http.StatusBadRequest
		if ue.Code == "DOWNLOAD_NOT_FOUND" {
			status = http.StatusNotFound
		}
		errJSON(w, status, ue.Code, ue.Params, ue.Msg)
		return
	}
	errJSON(w, http.StatusInternalServerError, "DOWNLOAD_FAILED",
		map[string]any{"msg": summarize(err.Error())}, summarize(err.Error()))
}
