//go:build windows

package main

import (
	"os/exec"
	"syscall"
)

// CREATE_NO_WINDOW：console 子进程不创建控制台窗口。
// windowsgui 父进程拉起 console 子进程时，不带此标志 Windows 会给子进程新开一个
// 黑色控制台窗口（输出已重定向到日志文件，窗口全黑且碍事）。
const createNoWindow = 0x08000000

// hideChildWindow 抑制子进程的控制台窗口（实例进程、--list-devices 探测等）。
func hideChildWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}
