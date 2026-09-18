//go:build windows

package main

import (
	"log"

	"golang.org/x/sys/windows"
)

// diskUsableSpace 返回目录所在卷的可用字节数；失败时 ok=false（跳过空间预检）。
func diskUsableSpace(dir string) (usable uint64, ok bool) {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		log.Printf("磁盘空间检查失败: %v", err)
		return 0, false
	}
	var freeAvailable, total, totalFree uint64
	if err := windows.GetDiskFreeSpaceEx(p, &freeAvailable, &total, &totalFree); err != nil {
		log.Printf("磁盘空间检查失败: %v", err)
		return 0, false
	}
	return freeAvailable, true
}
