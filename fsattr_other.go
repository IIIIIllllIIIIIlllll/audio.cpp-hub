//go:build !windows

package main

import (
	"path/filepath"
	"strings"
)

// isHiddenPath 非 Windows 平台按 . 开头判断隐藏文件。
func isHiddenPath(path string) bool {
	return strings.HasPrefix(filepath.Base(path), ".")
}
