package main

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// VoiceLibrary 音色库：data/voices/<vid>.wav + data/voices/index.json 登记
// （对应 Java 版 VoiceLibrary，条目形状一致）。
type VoiceLibrary struct {
	mu        sync.Mutex
	dir       string
	indexFile string
}

func NewVoiceLibrary() *VoiceLibrary {
	dir := filepath.Join("data", "voices")
	return &VoiceLibrary{dir: dir, indexFile: filepath.Join(dir, "index.json")}
}

func (v *VoiceLibrary) readIndex() []map[string]any {
	data, err := os.ReadFile(v.indexFile)
	if err != nil || len(strings.TrimSpace(string(data))) == 0 {
		return nil
	}
	var list []map[string]any
	if err := json.Unmarshal(data, &list); err != nil {
		return nil
	}
	return list
}

func (v *VoiceLibrary) writeIndex(list []map[string]any) error {
	if err := os.MkdirAll(v.dir, 0755); err != nil {
		return err
	}
	data, err := json.Marshal(list)
	if err != nil {
		return err
	}
	return os.WriteFile(v.indexFile, data, 0644)
}

// List 全部音色。
func (v *VoiceLibrary) List() []map[string]any {
	v.mu.Lock()
	defer v.mu.Unlock()
	list := v.readIndex()
	if list == nil {
		list = []map[string]any{}
	}
	return list
}

// checkNameUnique 名称库内唯一（trim 后比较，excludeVid 排除自身）。
func checkVoiceNameUnique(index []map[string]any, name, excludeVid string) error {
	trimmed := strings.TrimSpace(name)
	for _, entry := range index {
		if excludeVid != "" && optString(entry, "vid") == excludeVid {
			continue
		}
		if optString(entry, "name") == trimmed {
			return newUserError("VOICE_NAME_EXISTS", "音色名称已存在: "+trimmed)
		}
	}
	return nil
}

// Save 保存音色：uploadId（上传件 id）与 sourcePath（绝对路径）二选一。
// 复制文件为 data/voices/<vid>.wav 并登记；text 为音频文本内容（可空）。
func (v *VoiceLibrary) Save(name, text, uploadID, sourcePath string) (map[string]any, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if strings.TrimSpace(name) == "" {
		return nil, newUserError("VOICE_NAME_REQUIRED", "音色名称不能为空")
	}
	index := v.readIndex()
	if err := checkVoiceNameUnique(index, name, ""); err != nil {
		return nil, err
	}
	var source string
	if uploadID != "" {
		source = uploadPath(uploadID)
		if source == "" {
			return nil, newUserError("UPLOAD_NOT_FOUND", "上传件不存在: "+uploadID)
		}
	} else if sourcePath != "" {
		if !isRegularFile(sourcePath) {
			return nil, newUserError("FILE_NOT_FOUND", "文件不存在: "+sourcePath)
		}
		source = sourcePath
	} else {
		return nil, newUserError("VOICE_SOURCE_REQUIRED", "uploadId 与 path 必须提供一个")
	}

	vid := newID()
	if err := os.MkdirAll(v.dir, 0755); err != nil {
		return nil, err
	}
	target := filepath.Join(v.dir, vid+".wav")
	if err := copyFile(source, target); err != nil {
		return nil, err
	}
	// 解析副本获取音频信息
	info, err := parseWAVFile(target)
	if err != nil {
		os.Remove(target)
		return nil, err
	}
	st, _ := os.Stat(target)
	var size int64
	if st != nil {
		size = st.Size()
	}
	abs, _ := filepath.Abs(target)
	entry := map[string]any{
		"vid":           vid,
		"name":          strings.TrimSpace(name),
		"createdAt":     time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"path":          abs,
		"durationSec":   round3(info.durationSec),
		"sampleRate":    info.sampleRate,
		"channels":      info.channels,
		"bitsPerSample": info.bitsPerSample,
		"sizeBytes":     size,
	}
	if text != "" {
		entry["text"] = text
	}
	index = append(index, entry)
	if err := v.writeIndex(index); err != nil {
		return nil, err
	}
	return entry, nil
}

// Update 更新音色名称与文本内容：name/text 传 nil 表示不修改对应字段。
// vid 非法或条目不存在返回 (false, nil)；name 重名（排除自身）返回 UserError。
func (v *VoiceLibrary) Update(vid string, name, text *string) (bool, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if !uploadSafeID.MatchString(vid) {
		return false, nil
	}
	if name != nil && strings.TrimSpace(*name) == "" {
		return false, newUserError("VOICE_NAME_REQUIRED", "音色名称不能为空")
	}
	index := v.readIndex()
	var entry map[string]any
	for _, e := range index {
		if optString(e, "vid") == vid {
			entry = e
			break
		}
	}
	if entry == nil {
		return false, nil
	}
	if name != nil {
		if err := checkVoiceNameUnique(index, *name, vid); err != nil {
			return false, err
		}
		entry["name"] = strings.TrimSpace(*name)
	}
	if text != nil {
		if *text == "" {
			delete(entry, "text")
		} else {
			entry["text"] = *text
		}
	}
	if err := v.writeIndex(index); err != nil {
		return false, err
	}
	return true, nil
}

// Delete 删除音色（文件 + 登记）。
func (v *VoiceLibrary) Delete(vid string) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	if !uploadSafeID.MatchString(vid) {
		return false
	}
	index := v.readIndex()
	for i, e := range index {
		if optString(e, "vid") == vid {
			index = append(index[:i], index[i+1:]...)
			os.Remove(filepath.Join(v.dir, vid+".wav"))
			v.writeIndex(index)
			return true
		}
	}
	return false
}

// AudioPath 音色音频文件路径；vid 不在库中返回空串。
func (v *VoiceLibrary) AudioPath(vid string) string {
	v.mu.Lock()
	defer v.mu.Unlock()
	if !uploadSafeID.MatchString(vid) {
		return ""
	}
	for _, e := range v.readIndex() {
		if optString(e, "vid") == vid {
			path := filepath.Join(v.dir, vid+".wav")
			if isRegularFile(path) {
				return path
			}
			return ""
		}
	}
	return ""
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}
