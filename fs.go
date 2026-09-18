package main

import (
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
)

// 服务器端文件系统浏览：roots / list / stat / mkdir（对应 Java 版 FileSystemBrowser）。
// hub 是本地单用户工具，已有功能本就接受任意本机路径，因此不做目录白名单限制。

// fsRoots 根节点：Windows 为各盘符，其他系统为 /；始终附带主目录与程序根目录。
func fsRoots() []map[string]any {
	roots := []map[string]any{}
	if runtime.GOOS == "windows" {
		for c := 'A'; c <= 'Z'; c++ {
			drive := string(c) + `:\`
			if isDir(drive) {
				roots = append(roots, map[string]any{"name": string(c) + ":", "path": drive})
			}
		}
	} else {
		roots = append(roots, map[string]any{"name": "/", "path": "/"})
	}
	home, _ := os.UserHomeDir()
	if home != "" && isDir(home) {
		roots = append(roots, map[string]any{"name": "主目录", "path": home})
	}
	workDir, _ := os.Getwd()
	if workDir != "" && workDir != home {
		roots = append(roots, map[string]any{"name": "程序根目录", "path": workDir})
	}
	return roots
}

// fsList 列出目录内容：目录优先、按名称排序（忽略大小写）。
func fsList(rawPath string) (map[string]any, error) {
	if strings.TrimSpace(rawPath) == "" {
		return nil, newUserError("PATH_REQUIRED", "path 不能为空")
	}
	dir, err := filepath.Abs(strings.TrimSpace(rawPath))
	if err != nil {
		return nil, newUserError("PATH_NOT_FOUND", "路径不存在: "+rawPath)
	}
	if !pathExists(dir) {
		return nil, newUserError("PATH_NOT_FOUND", "路径不存在: "+dir)
	}
	if !isDir(dir) {
		return nil, newUserError("NOT_A_DIRECTORY", "不是目录: "+dir)
	}
	children, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	sort.SliceStable(children, func(i, j int) bool {
		if children[i].IsDir() != children[j].IsDir() {
			return children[i].IsDir()
		}
		return strings.ToLower(children[i].Name()) < strings.ToLower(children[j].Name())
	})
	entries := []map[string]any{}
	for _, child := range children {
		entries = append(entries, fsEntry(filepath.Join(dir, child.Name())))
	}
	parent := filepath.Dir(dir)
	if parent == dir {
		parent = ""
	}
	return map[string]any{
		"path":    dir,
		"parent":  parent,
		"entries": entries,
	}, nil
}

// fsEntry 单个路径条目：name/path/dir/hidden/size?/ext?/mtime。
func fsEntry(path string) map[string]any {
	name := filepath.Base(path)
	entry := map[string]any{
		"name":   name,
		"path":   path,
		"dir":    isDir(path),
		"hidden": isHiddenPath(path),
	}
	st, err := os.Stat(path)
	if err != nil {
		return entry
	}
	if !st.IsDir() {
		entry["size"] = st.Size()
		if dot := strings.LastIndex(name, "."); dot > 0 {
			entry["ext"] = strings.ToLower(name[dot:])
		}
	}
	entry["mtime"] = st.ModTime().Format("2006-01-02 15:04")
	return entry
}

// fsStat 探测单个路径：不存在的路径也返回 200（exists=false）。
func fsStat(rawPath string) map[string]any {
	if strings.TrimSpace(rawPath) == "" {
		return map[string]any{"exists": false}
	}
	path, err := filepath.Abs(strings.TrimSpace(rawPath))
	if err != nil {
		return map[string]any{"exists": false}
	}
	result := map[string]any{"path": path, "exists": pathExists(path)}
	if pathExists(path) {
		entry := fsEntry(path)
		for _, key := range []string{"name", "dir", "size", "ext", "mtime", "hidden"} {
			if v, ok := entry[key]; ok {
				result[key] = v
			}
		}
	}
	return result
}

var dirNameInvalid = regexp.MustCompile(`.*[\\/:*?"<>|].*`)

// fsMkdir 在 parent 下新建文件夹，返回新目录条目。
func fsMkdir(parentRaw, name string) (map[string]any, error) {
	if strings.TrimSpace(parentRaw) == "" {
		return nil, newUserError("PARENT_REQUIRED", "parent 不能为空")
	}
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return nil, newUserError("DIR_NAME_REQUIRED", "文件夹名称不能为空")
	}
	if dirNameInvalid.MatchString(trimmed) || trimmed == "." || trimmed == ".." {
		return nil, newUserError("DIR_NAME_INVALID", "文件夹名称含非法字符: "+trimmed)
	}
	parent, err := filepath.Abs(strings.TrimSpace(parentRaw))
	if err != nil || !isDir(parent) {
		return nil, newUserError("PARENT_NOT_FOUND", "父目录不存在: "+parentRaw)
	}
	dir := filepath.Join(parent, trimmed)
	if pathExists(dir) {
		return nil, newUserError("ALREADY_EXISTS", "已存在同名文件或文件夹: "+trimmed)
	}
	if err := os.Mkdir(dir, 0755); err != nil {
		return nil, err
	}
	return fsEntry(dir), nil
}
