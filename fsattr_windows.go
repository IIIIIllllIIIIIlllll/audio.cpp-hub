//go:build windows

package main

import (
	"path/filepath"
	"strings"
	"syscall"
)

// isHiddenPath Windows 下读文件属性判断隐藏位，失败回退按 . 开头判断。
func isHiddenPath(path string) bool {
	p, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return strings.HasPrefix(filepath.Base(path), ".")
	}
	attrs, err := syscall.GetFileAttributes(p)
	if err != nil {
		return strings.HasPrefix(filepath.Base(path), ".")
	}
	return attrs&syscall.FILE_ATTRIBUTE_HIDDEN != 0
}
