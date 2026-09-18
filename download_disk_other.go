//go:build !windows

package main

// diskUsableSpace 非 Windows 平台暂不做磁盘空间预检（ok=false 跳过）。
func diskUsableSpace(dir string) (usable uint64, ok bool) {
	return 0, false
}
