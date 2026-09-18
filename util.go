package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"regexp"
)

// 请求体上限，与 Java 版 Netty 聚合器一致
const maxBodyBytes = 64 << 20

// UserError 带 code/params 的用户可读错误，API 层转成 {"ok":false,"code",...,"error"}。
type UserError struct {
	Code   string
	Params map[string]any
	Msg    string
}

func (e *UserError) Error() string { return e.Msg }

func newUserError(code, msg string) *UserError {
	return &UserError{Code: code, Params: map[string]any{}, Msg: msg}
}

// writeJSON 序列化任意值为 JSON 响应。
func writeJSON(w http.ResponseWriter, status int, v any) {
	data, err := json.Marshal(v)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeRawJSON(w, status, data)
}

func writeRawJSON(w http.ResponseWriter, status int, data []byte) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	w.Write(data)
}

// okJSON 对应 Java 的 Jsons.ok(data)：{"ok":true,"data":...}
func okJSON(w http.ResponseWriter, data any) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "data": data})
}

// errJSON 对应 Java 的 Jsons.error(code, params, msg)。
func errJSON(w http.ResponseWriter, status int, code string, params map[string]any, msg string) {
	if params == nil {
		params = map[string]any{}
	}
	writeJSON(w, status, map[string]any{"ok": false, "code": code, "params": params, "error": msg})
}

// errFromErr 把 error 转成响应：UserError 保留 code，否则用 fallbackCode。
func errFromErr(w http.ResponseWriter, status int, fallbackCode string, err error) {
	if ue, ok := err.(*UserError); ok {
		errJSON(w, status, ue.Code, ue.Params, ue.Msg)
		return
	}
	errJSON(w, status, fallbackCode, map[string]any{"msg": summarize(err.Error())}, fallbackCode+": "+summarize(err.Error()))
}

// readBodyMap 读取请求体并解析为 JSON 对象；失败时已写响应，返回 nil。
func readBodyMap(w http.ResponseWriter, r *http.Request) map[string]any {
	var body map[string]any
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err := dec.Decode(&body); err != nil {
		errJSON(w, http.StatusBadRequest, "INVALID_JSON", nil, "请求体不是合法 JSON")
		return nil
	}
	return body
}

// newID 与 Java 版一致：UUID 前 8 位（这里用随机 4 字节 hex）。
func newID() string {
	b := make([]byte, 4)
	rand.Read(b)
	return hex.EncodeToString(b)
}

var envPlaceholder = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}`)

// expandEnv 展开值中的 ${VAR} 占位符（未定义展开为空串），语义与 Java 版一致。
func expandEnv(value string, lookup func(string) string) string {
	return envPlaceholder.ReplaceAllStringFunc(value, func(m string) string {
		return lookup(envPlaceholder.FindStringSubmatch(m)[1])
	})
}

func optString(m map[string]any, key string) string {
	if v, ok := m[key]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

// optIntPtr 取可选整数字段（JSON 数字解码为 float64）。
func optIntPtr(m map[string]any, key string) *int {
	if v, ok := m[key]; ok {
		if f, ok := v.(float64); ok {
			n := int(f)
			return &n
		}
	}
	return nil
}

// optStringMap 解析 {键: 标量值} 字段，值统一转字符串（对应 Java 的 optStringMap）。
func optStringMap(m map[string]any, key string) (map[string]string, error) {
	result := map[string]string{}
	v, ok := m[key]
	if !ok || v == nil {
		return result, nil
	}
	obj, ok := v.(map[string]any)
	if !ok {
		return nil, newUserError("OPTIONS_NOT_OBJECT", key+" 必须为 {键: 值} 对象")
	}
	for k, val := range obj {
		switch t := val.(type) {
		case string:
			result[k] = t
		case float64, bool:
			result[k] = fmt.Sprintf("%v", t)
		default:
			return nil, newUserError("OPTIONS_INVALID", key+" 的键不能为空、值必须为标量")
		}
	}
	return result, nil
}

// summarize 截断超长错误信息（与 Java 版 300 字一致）。
func summarize(text string) string {
	r := []rune(text)
	if len(r) <= 300 {
		return text
	}
	return string(r[:300]) + "..."
}

// truncateRunes 截断文本预览（Java 版 100 字 + …）。
func truncateRunes(text string, limit int) string {
	r := []rune(text)
	if len(r) <= limit {
		return text
	}
	return string(r[:limit]) + "…"
}

func pathExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func isRegularFile(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.Mode().IsRegular()
}

func isDir(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

// writeFileAtomic 先写临时文件再改名（对应 Java 版的原子落盘）。
func writeFileAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
