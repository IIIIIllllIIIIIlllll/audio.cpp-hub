//go:build !windows

package main

import "os/exec"

// hideChildWindow 非 Windows 平台无控制台窗口概念，无需处理。
func hideChildWindow(cmd *exec.Cmd) {}
