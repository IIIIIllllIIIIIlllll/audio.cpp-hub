package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// /v1/* OpenAI 兼容代理（移植自 Java 版 V1ProxyHandler + RequestModelExtractor）。
// Go 的 net/http 本身就流式给 r.Body，无需 Netty 的 aggregator 拆装：
// 请求体流式落盘 run/proxy-cache/<id>.req（大 base64 不进内存）→ 逐字节扫描提取顶层
// "model" → 按服务名路由到 READY 实例 → 落盘文件作为 body 转发到实例同名路径 →
// 响应状态码/Content-Type 透传，每读一块 Flush 一次（SSE/chunked 与整包都支持）。
// 错误体为 OpenAI 风格 {"error":{"message","type"}}。
// 已知限制（与 Java 一致）：multipart/form-data（如 /v1/audio/transcriptions）无法
// 提取 model——extractor 只认 JSON，会回 400。

const (
	v1CacheDir          = "run/proxy-cache"
	v1ModelCaptureLimit = 4096 // model 值捕获上限（服务名最长 64，给足余量）
	v1ResponseChunk     = 8192
)

// v1ProxyError 带 HTTP 状态码的代理错误，转成 OpenAI 风格错误体。
type v1ProxyError struct {
	status int
	msg    string
}

func (e *v1ProxyError) Error() string { return e.msg }

var errV1InvalidJSON = &v1ProxyError{http.StatusBadRequest, "Request body is not a valid JSON object"}

// v1UpstreamClient 上游实例通信：不设整体超时（TTS 可能跑很久），
// 中断靠请求 context（客户端断开时 r.Context() 取消）。
var v1UpstreamClient = &http.Client{
	Transport: &http.Transport{
		DialContext:         (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
		MaxIdleConns:        32,
		MaxIdleConnsPerHost: 8,
		IdleConnTimeout:     90 * time.Second,
	},
}

// cleanupV1ProxyCache 启动时清扫 run/proxy-cache/*.req 残留（main 启动时调一次）。
func cleanupV1ProxyCache() {
	if err := os.MkdirAll(v1CacheDir, 0755); err != nil {
		log.Printf("初始化代理缓存目录失败: %v", err)
		return
	}
	entries, err := os.ReadDir(v1CacheDir)
	if err != nil {
		log.Printf("初始化代理缓存目录失败: %v", err)
		return
	}
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".req") {
			os.Remove(filepath.Join(v1CacheDir, e.Name()))
		}
	}
}

// handleV1Proxy /v1/* 统一入口：GET /v1/models 本地聚合，POST/PUT 转发到实例。
func (h *Hub) handleV1Proxy(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/v1/models" && r.Method == http.MethodGet {
		h.handleV1Models(w, r)
		return
	}
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		v1Error(w, http.StatusMethodNotAllowed, "Method not allowed: "+r.Method)
		return
	}
	maxBody := h.cfg.ProxyMaxBodyBytes
	if maxBody <= 0 {
		maxBody = 1 << 30
	}
	// 1) 请求体流式落盘（超上限 413）
	if err := os.MkdirAll(v1CacheDir, 0755); err != nil {
		v1Error(w, http.StatusInternalServerError, "Proxy cache unavailable")
		return
	}
	tmp := filepath.Join(v1CacheDir, newID()+".req")
	f, err := os.Create(tmp)
	if err != nil {
		log.Printf("创建代理缓存文件失败: %v", err)
		v1Error(w, http.StatusInternalServerError, "Proxy cache unavailable")
		return
	}
	n, copyErr := io.Copy(f, io.LimitReader(r.Body, maxBody+1))
	if cerr := f.Close(); cerr != nil && copyErr == nil {
		copyErr = cerr
	}
	if copyErr != nil {
		os.Remove(tmp)
		log.Printf("代理缓存写入失败: %v", copyErr)
		v1Error(w, http.StatusInternalServerError, "Proxy cache unavailable")
		return
	}
	if n > maxBody {
		os.Remove(tmp)
		v1Error(w, http.StatusRequestEntityTooLarge,
			fmt.Sprintf("Request body exceeds proxy limit of %d bytes", maxBody))
		return
	}
	defer os.Remove(tmp) // 转发用的文件句柄在此之前已关闭（Windows 需先 close 再删）

	// 2) 逐字节扫描提取顶层 "model"
	model, err := extractV1Model(tmp, maxBody)
	if err != nil {
		if pe, ok := err.(*v1ProxyError); ok {
			v1Error(w, pe.status, pe.msg)
		} else {
			v1Error(w, http.StatusInternalServerError, "Failed to read cached request body")
		}
		return
	}
	if model == "" {
		v1Error(w, http.StatusBadRequest, "Missing required parameter: model")
		return
	}

	// 3) 按服务名路由（READY 才转发；非 READY 409；不存在 404）
	inst := h.instances.FindByName(model)
	if inst == nil {
		if h.instances.FindAnyByName(model) != nil {
			v1Error(w, http.StatusConflict, "Model is still starting: "+model)
		} else {
			v1Error(w, http.StatusNotFound, "Model not found: "+model)
		}
		return
	}

	// 4) 落盘文件作为 body 转发到实例同名路径，5) 响应流式回写
	h.forwardV1(w, r, inst, tmp, n)
}

// handleV1Models GET /v1/models：聚合 READY 实例，OpenAI 格式。
func (h *Hub) handleV1Models(w http.ResponseWriter, r *http.Request) {
	io.Copy(io.Discard, r.Body) // drain body 保 keep-alive
	data := []map[string]any{}
	for _, inst := range h.instances.List() {
		if inst.Status != "READY" {
			continue
		}
		data = append(data, map[string]any{
			"id":       inst.Name,
			"object":   "model",
			"created":  v1CreatedEpoch(inst.CreatedAt),
			"owned_by": "audiocpp",
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"object": "list", "data": data})
}

// v1CreatedEpoch 解析实例 CreatedAt（ISO）为 epoch 秒，失败归 0。
func v1CreatedEpoch(iso string) int64 {
	if t, err := time.Parse("2006-01-02T15:04:05.000Z", iso); err == nil {
		return t.Unix()
	}
	return 0
}

// forwardV1 把落盘的请求体转发到实例同名 URI，响应分块流式回写（每块 Flush）。
func (h *Hub) forwardV1(w http.ResponseWriter, r *http.Request, inst *Instance, bodyPath string, bodyLen int64) {
	body, err := os.Open(bodyPath)
	if err != nil {
		v1Error(w, http.StatusInternalServerError, "Failed to read cached request body")
		return
	}
	upstreamURL := fmt.Sprintf("http://127.0.0.1:%d%s", inst.Port, r.URL.RequestURI())
	req, err := http.NewRequestWithContext(r.Context(), r.Method, upstreamURL, body)
	if err != nil {
		body.Close()
		v1Error(w, http.StatusInternalServerError, "Proxy internal error")
		return
	}
	req.ContentLength = bodyLen
	contentType := r.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/json"
	}
	req.Header.Set("Content-Type", contentType)
	resp, err := v1UpstreamClient.Do(req)
	body.Close() // 请求体已完整发出（或失败），缓存文件使命结束
	if err != nil {
		log.Printf("转发到实例 #%s 失败: %v", inst.ID, err)
		if r.Context().Err() == nil {
			v1Error(w, http.StatusBadGateway, "Failed to reach model instance: "+summarize(err.Error()))
		}
		return
	}
	defer resp.Body.Close()

	ct := resp.Header.Get("Content-Type")
	if ct == "" {
		ct = "application/json"
	}
	w.Header().Set("Content-Type", ct)
	if resp.ContentLength >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(resp.ContentLength, 10))
	}
	w.WriteHeader(resp.StatusCode)
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, v1ResponseChunk)
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return // 客户端断开，context 取消会让上游读中断
			}
			if flusher != nil {
				flusher.Flush() // SSE 必须逐块下发
			}
		}
		if readErr != nil {
			return
		}
	}
}

// v1Error OpenAI 风格错误体：{"error":{"message","type"}}。
func v1Error(w http.ResponseWriter, status int, message string) {
	typ := "invalid_request_error"
	if status >= 500 {
		typ = "server_error"
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{
		"error": map[string]any{"message": message, "type": typ},
	})
}

// ------------------------------------------------------------------ model 提取
// 移植 Java RequestModelExtractor：逐字节扫描顶层 JSON 对象，大字段原样跳过不落内存。

// extractV1Model 扫描请求体文件，返回顶层 "model" 字符串值；缺失/非字符串/为空返回 ""。
// 文件超过 maxBytes 报 413，顶层结构非法报 400。
func extractV1Model(path string, maxBytes int64) (string, error) {
	st, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	if st.Size() > maxBytes {
		return "", &v1ProxyError{http.StatusRequestEntityTooLarge,
			fmt.Sprintf("Request body exceeds proxy limit of %d bytes", maxBytes)}
	}
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	in := bufio.NewReaderSize(f, 64*1024)

	b, err := v1NextNonWS(in)
	if err != nil || b != '{' {
		return "", errV1InvalidJSON
	}
	model := ""
	firstField := true
	for {
		token, err := v1NextNonWS(in)
		if token == '}' {
			break
		}
		if err != nil {
			return "", errV1InvalidJSON
		}
		if !firstField {
			if token != ',' {
				return "", errV1InvalidJSON
			}
			if token, err = v1NextNonWS(in); err != nil {
				return "", errV1InvalidJSON
			}
		}
		firstField = false
		if token != '"' {
			return "", errV1InvalidJSON
		}
		fieldName, err := v1ReadJSONString(in)
		if err != nil {
			return "", err
		}
		colon, err := v1NextNonWS(in)
		if err != nil || colon != ':' {
			return "", errV1InvalidJSON
		}
		valueStart, err := v1NextNonWS(in)
		if err != nil {
			return "", errV1InvalidJSON
		}
		if fieldName == "model" {
			var capture bytes.Buffer
			lim := &v1LimitedWriter{w: &capture, limit: v1ModelCaptureLimit}
			if err := v1CopyValue(in, valueStart, lim); err != nil {
				if errors.Is(err, errV1CaptureLimit) {
					return "", &v1ProxyError{http.StatusBadRequest, "model value too large"}
				}
				return "", err
			}
			model = v1ParseModelValue(capture.Bytes())
		} else {
			// 大字段热点：原样跳过，不落内存
			if err := v1CopyValue(in, valueStart, io.Discard); err != nil {
				return "", err
			}
		}
	}
	return model, nil
}

// v1ParseModelValue 解析捕获到的 model 原始值，非 JSON 字符串/空白返回 ""。
func v1ParseModelValue(raw []byte) string {
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return ""
	}
	if s, ok := v.(string); ok && strings.TrimSpace(s) != "" {
		return s
	}
	return ""
}

// v1CopyValue 按 JSON 值类型逐字节拷贝（跳过）到 out。
func v1CopyValue(in *bufio.Reader, firstByte byte, out io.Writer) error {
	switch {
	case firstByte == '"':
		return v1CopyString(in, out)
	case firstByte == '{' || firstByte == '[':
		return v1CopyComposite(in, firstByte, out)
	case v1IsPrimitiveStart(firstByte):
		return v1CopyPrimitive(in, firstByte, out)
	}
	return errV1InvalidJSON
}

func v1CopyString(in *bufio.Reader, out io.Writer) error {
	out.Write([]byte{'"'})
	escaped := false
	for {
		b, err := in.ReadByte()
		if err != nil {
			return errV1InvalidJSON
		}
		out.Write([]byte{b})
		if escaped {
			escaped = false
			continue
		}
		if b == '\\' {
			escaped = true
			continue
		}
		if b == '"' {
			return nil
		}
	}
}

func v1CopyComposite(in *bufio.Reader, firstByte byte, out io.Writer) error {
	objectDepth, arrayDepth := 0, 0
	if firstByte == '{' {
		objectDepth = 1
	} else {
		arrayDepth = 1
	}
	inString, escaped := false, false
	out.Write([]byte{firstByte})
	for objectDepth > 0 || arrayDepth > 0 {
		b, err := in.ReadByte()
		if err != nil {
			return errV1InvalidJSON
		}
		out.Write([]byte{b})
		if inString {
			if escaped {
				escaped = false
			} else if b == '\\' {
				escaped = true
			} else if b == '"' {
				inString = false
			}
			continue
		}
		switch b {
		case '"':
			inString = true
		case '{':
			objectDepth++
		case '}':
			objectDepth--
		case '[':
			arrayDepth++
		case ']':
			arrayDepth--
		}
	}
	return nil
}

func v1CopyPrimitive(in *bufio.Reader, firstByte byte, out io.Writer) error {
	out.Write([]byte{firstByte})
	for {
		b, err := in.ReadByte()
		if err != nil {
			return nil // EOF：原始值在文件尾结束
		}
		if v1IsValueTerminator(b) {
			in.UnreadByte()
			return nil
		}
		out.Write([]byte{b})
	}
}

func v1IsPrimitiveStart(b byte) bool {
	return b == 't' || b == 'f' || b == 'n' || b == '-' || (b >= '0' && b <= '9')
}

func v1IsValueTerminator(b byte) bool {
	return b == ',' || b == '}' || b == ']' || b == ' ' || b == '\t' || b == '\r' || b == '\n'
}

// v1NextNonWS 读下一个非空白字节；EOF 返回 (0, io.EOF)。
func v1NextNonWS(in *bufio.Reader) (byte, error) {
	for {
		b, err := in.ReadByte()
		if err != nil {
			return 0, err
		}
		// JSON 空白均为 ASCII，逐字节判断即可
		if b != ' ' && b != '\t' && b != '\r' && b != '\n' {
			return b, nil
		}
	}
}

// v1ReadJSONString 读取一个 JSON 字符串（开头引号已消费）并解码。
func v1ReadJSONString(in *bufio.Reader) (string, error) {
	var raw bytes.Buffer
	raw.WriteByte('"')
	escaped := false
	for {
		b, err := in.ReadByte()
		if err != nil {
			return "", errV1InvalidJSON
		}
		raw.WriteByte(b)
		if escaped {
			escaped = false
			continue
		}
		if b == '\\' {
			escaped = true
			continue
		}
		if b == '"' {
			break
		}
	}
	var s string
	if err := json.Unmarshal(raw.Bytes(), &s); err != nil {
		return "", errV1InvalidJSON
	}
	return s, nil
}

var errV1CaptureLimit = errors.New("capture limit exceeded")

// v1LimitedWriter 带容量上限的包装 Writer，超限报错。
type v1LimitedWriter struct {
	w     io.Writer
	limit int
	count int
}

func (l *v1LimitedWriter) Write(p []byte) (int, error) {
	l.count += len(p)
	if l.count > l.limit {
		return 0, errV1CaptureLimit
	}
	return l.w.Write(p)
}
