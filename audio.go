package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
)

// 上传音频存储与 WAV 头解析。只认标准 PCM WAV（audioFormat 1 或 3），
// 行为与 Java 版 AudioStore 一致。

const maxUploadBytes = 50 * 1024 * 1024

var uploadDir = filepath.Join("data", "uploads")

var uploadSafeID = regexp.MustCompile(`^[a-zA-Z0-9-]{1,32}$`)

// wavFullInfo WAV 头解析结果。
type wavFullInfo struct {
	sampleRate    int
	channels      int
	bitsPerSample int
	durationSec   float64
}

// saveUpload 保存上传的 WAV 到 data/uploads/<id>.wav，返回音频信息。
func saveUpload(wav []byte) (map[string]any, error) {
	info, err := parseWAVReader(bytes.NewReader(wav), int64(len(wav)))
	if err != nil {
		return nil, err
	}
	id := newID()
	if err := os.MkdirAll(uploadDir, 0755); err != nil {
		return nil, err
	}
	path := filepath.Join(uploadDir, id+".wav")
	if err := os.WriteFile(path, wav, 0644); err != nil {
		return nil, err
	}
	abs, _ := filepath.Abs(path)
	return wavInfoMap(id, abs, info, int64(len(wav))), nil
}

// probeWAV 解析本地路径的 WAV 信息（"本地路径"Tab 用）。
func probeWAV(pathStr string) (map[string]any, error) {
	if !isRegularFile(pathStr) {
		return nil, newUserError("FILE_NOT_FOUND", "文件不存在: "+pathStr)
	}
	st, _ := os.Stat(pathStr)
	if st.Size() > maxUploadBytes {
		return nil, newUserError("FILE_TOO_LARGE", "文件超过 50MB 上限: "+pathStr)
	}
	info, err := parseWAVFile(pathStr)
	if err != nil {
		return nil, err
	}
	abs, _ := filepath.Abs(pathStr)
	return wavInfoMap("", abs, info, st.Size()), nil
}

// uploadPath 定位上传文件；id 不合法或文件不存在返回空串（防路径穿越）。
func uploadPath(id string) string {
	if !uploadSafeID.MatchString(id) {
		return ""
	}
	path := filepath.Join(uploadDir, id+".wav")
	if isRegularFile(path) {
		return path
	}
	return ""
}

// wavInfoMap 输出形状与 Java 版 AudioStore.toMap 一致（id 为空则不输出）。
func wavInfoMap(id, absPath string, info wavFullInfo, sizeBytes int64) map[string]any {
	m := map[string]any{
		"path":          absPath,
		"durationSec":   round3(info.durationSec),
		"sampleRate":    info.sampleRate,
		"channels":      info.channels,
		"bitsPerSample": info.bitsPerSample,
		"sizeBytes":     sizeBytes,
	}
	if id != "" {
		m["id"] = id
	}
	return m
}

func round3(d float64) float64 {
	return float64(int64(d*1000+0.5)) / 1000
}

// parseWAVFile 流式解析 WAV 头（不整文件读入内存）。
func parseWAVFile(path string) (wavFullInfo, error) {
	f, err := os.Open(path)
	if err != nil {
		return wavFullInfo{}, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return wavFullInfo{}, err
	}
	return parseWAVReader(f, st.Size())
}

// parseWAVReader 逐 chunk 读头，命中 data 记大小即止。
// 错误码与 Java 版一致：NOT_WAV / WAV_CHUNKS_MISSING / WAV_NOT_PCM / WAV_FMT_INVALID。
func parseWAVReader(r io.ReadSeeker, fileSize int64) (wavFullInfo, error) {
	var info wavFullInfo
	magic := make([]byte, 12)
	if _, err := io.ReadFull(r, magic); err != nil ||
		string(magic[0:4]) != "RIFF" || string(magic[8:12]) != "WAVE" {
		return info, newUserError("NOT_WAV", "不是标准 RIFF/WAVE 文件")
	}
	audioFormat := -1
	dataSize := int64(-1)
	position := int64(12)
	for {
		head := make([]byte, 8)
		if _, err := io.ReadFull(r, head); err != nil {
			break
		}
		chunkID := string(head[0:4])
		chunkSize := int64(binary.LittleEndian.Uint32(head[4:8]))
		position += 8
		switch chunkID {
		case "fmt ":
			fmtBuf := make([]byte, 16)
			if _, err := io.ReadFull(r, fmtBuf); err != nil {
				break
			}
			position += 16
			audioFormat = int(binary.LittleEndian.Uint16(fmtBuf[0:2]))
			info.channels = int(binary.LittleEndian.Uint16(fmtBuf[2:4]))
			info.sampleRate = int(binary.LittleEndian.Uint32(fmtBuf[4:8]))
			info.bitsPerSample = int(binary.LittleEndian.Uint16(fmtBuf[14:16]))
			rest := chunkSize - 16 + chunkSize%2
			if rest > 0 {
				r.Seek(rest, io.SeekCurrent)
				position += rest
			}
		case "data": // 内容无需读取
			dataSize = chunkSize
			if remain := fileSize - position; dataSize > remain && remain >= 0 {
				dataSize = remain
			}
		default:
			r.Seek(chunkSize+chunkSize%2, io.SeekCurrent)
			position += chunkSize + chunkSize%2
		}
		if chunkID == "data" {
			break
		}
	}
	if audioFormat == -1 || dataSize < 0 {
		return info, newUserError("WAV_CHUNKS_MISSING", "WAV 中缺少 fmt 或 data chunk")
	}
	if audioFormat != 1 && audioFormat != 3 {
		return info, &UserError{Code: "WAV_NOT_PCM",
			Params: map[string]any{"format": audioFormat},
			Msg:    fmt.Sprintf("非 PCM WAV（audioFormat=%d），仅支持 1(PCM int) 或 3(float)", audioFormat)}
	}
	if info.channels <= 0 || info.sampleRate <= 0 || info.bitsPerSample <= 0 {
		return info, newUserError("WAV_FMT_INVALID", "WAV fmt 参数非法")
	}
	bytesPerSec := float64(info.sampleRate) * float64(info.channels) * float64(info.bitsPerSample) / 8
	if bytesPerSec > 0 {
		info.durationSec = float64(dataSize) / bytesPerSec
	}
	return info, nil
}
