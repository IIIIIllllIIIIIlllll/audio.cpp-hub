package main

import (
	_ "embed"
	"encoding/json"
	"net/url"
	"strings"
)

// model-packages.json 由 src/main/resources/model-packages.json 复制而来
// （audio.cpp 的 model_specs 转换生成），直接嵌入二进制，只读。
//
//go:embed model-packages.json
var modelPackagesRaw []byte

// modelPackages 模型 id（= spec family）→ 清单条目原始 JSON（displayName/category/status/packages）。
var modelPackages map[string]json.RawMessage

func init() {
	if err := json.Unmarshal(modelPackagesRaw, &modelPackages); err != nil {
		panic("model-packages.json 解析失败: " + err.Error())
	}
}

// findPackageFamily 按模型 id 查找下载清单原始 JSON，不存在返回 nil。
func findPackageFamily(id string) json.RawMessage {
	if id == "" {
		return nil
	}
	fam, ok := modelPackages[id]
	if !ok {
		return nil
	}
	return fam
}

// dlPackageFile 下载包内单个文件：remote 为仓库内相对路径，local 为落盘相对路径。
type dlPackageFile struct {
	Remote string `json:"remote"`
	Local  string `json:"local"`
}

// dlPackage 下载清单中的一个包（repo/revision/targetDir/files）。
type dlPackage struct {
	ID        string          `json:"id"`
	Repo      string          `json:"repo"`
	Revision  string          `json:"revision"`
	TargetDir string          `json:"targetDir"`
	Default   bool            `json:"default"`
	Files     []dlPackageFile `json:"files"`
}

// resolvePackage 选择下载包：packageID 为空时取 default 标记的包（无标记取第一个），
// 找不到返回 nil。语义同 Java ModelPackageRegistry.resolvePackage。
func resolvePackage(family json.RawMessage, packageID string) *dlPackage {
	var fam struct {
		Packages []*dlPackage `json:"packages"`
	}
	if err := json.Unmarshal(family, &fam); err != nil {
		return nil
	}
	var fallback *dlPackage
	for _, pkg := range fam.Packages {
		if pkg == nil {
			continue
		}
		if packageID != "" {
			if pkg.ID == packageID {
				return pkg
			}
		} else {
			if fallback == nil {
				fallback = pkg
			}
			if pkg.Default {
				return pkg
			}
		}
	}
	if packageID == "" {
		return fallback
	}
	return nil
}

// buildResolveURL 构造 resolve URL：逐段 percent 编码（空格、括号、中文等），
// 保留 / 分隔，空格用 %20 不用 +。语义同 Java ModelPackageRegistry.buildUrl。
func buildResolveURL(endpoint, repo, revision, remote string) string {
	var sb strings.Builder
	sb.WriteString(endpoint)
	sb.WriteByte('/')
	sb.WriteString(repo)
	sb.WriteString("/resolve/")
	sb.WriteString(revision)
	sb.WriteByte('/')
	for i, seg := range strings.Split(remote, "/") {
		if i > 0 {
			sb.WriteByte('/')
		}
		sb.WriteString(url.PathEscape(seg))
	}
	return sb.String()
}

// modelscope 下载源：固定域名与组织前缀（HereIsMark 下镜像了 audio.cpp-gguf 仓库）。
const (
	modelscopeEndpoint = "https://www.modelscope.cn/models"
	modelscopeOrg      = "HereIsMark"
	modelscopeRevision = "master"
)

// modelscopeRepo 把 HF repo 映射为 modelscope repo：取 / 后的名字部分拼 HereIsMark/
// （例：audio-cpp/audio.cpp-gguf → HereIsMark/audio.cpp-gguf）。
func modelscopeRepo(hfRepo string) string {
	name := hfRepo
	if i := strings.LastIndex(hfRepo, "/"); i >= 0 {
		name = hfRepo[i+1:]
	}
	return modelscopeOrg + "/" + name
}
