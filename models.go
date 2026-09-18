package main

import (
	_ "embed"
	"encoding/json"
)

// models.json 由 src/main/resources/models.json 复制而来，直接嵌入二进制。
//
//go:embed models.json
var modelsRaw []byte

var models []map[string]any

func init() {
	if err := json.Unmarshal(modelsRaw, &models); err != nil {
		panic("models.json 解析失败: " + err.Error())
	}
}

// findModel 按 id 查找模型条目，不存在返回 nil。
func findModel(id string) map[string]any {
	for _, m := range models {
		if s, _ := m["id"].(string); s == id {
			return m
		}
	}
	return nil
}

// modelCategory 取模型类别（tts/asr/...），缺省 other。
func modelCategory(id string) string {
	if m := findModel(id); m != nil {
		if s, ok := m["category"].(string); ok {
			return s
		}
	}
	return "other"
}
