//go:build windows

package main

import (
	_ "embed"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/getlantern/systray"
)

// 托盘图标（由 launcher/favicon.ico 复制而来）
//
//go:embed icon.ico
var trayIcon []byte

// setupPlatform Windows GUI 模式（-H windowsgui）没有控制台，日志同时写 logs/hub.log。
func setupPlatform() {
	if err := os.MkdirAll("logs", 0755); err != nil {
		return
	}
	f, err := os.OpenFile(filepath.Join("logs", "hub.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(os.Stderr, f))
}

// runPlatform 后台 goroutine 跑 HTTP 服务，主 goroutine 进入托盘消息循环
// （systray.Run 要求在主 goroutine 调用，返回后即退出进程）。
func runPlatform(h *Hub, homeURL string, serve func() error) {
	go func() {
		if err := serve(); err != nil {
			log.Printf("HTTP 服务退出: %v", err)
			systray.Quit()
		}
	}()
	systray.Run(func() { trayReady(h, homeURL) }, func() {})
}

// trayReady 托盘菜单：打开首页 / 开机自启（勾选）/ 退出程序（与 Java 版一致）。
func trayReady(h *Hub, homeURL string) {
	systray.SetIcon(trayIcon)
	systray.SetTooltip("audio.cpp-hub")
	mOpen := systray.AddMenuItem("打开首页", "在浏览器中打开管理面板")
	mAuto := systray.AddMenuItemCheckbox("开机自启", "登录 Windows 后自动启动", autoStartEnabled())
	systray.AddSeparator()
	mQuit := systray.AddMenuItem("退出程序", "停止全部实例并退出")
	go func() {
		for {
			select {
			case <-mOpen.ClickedCh:
				openBrowser(homeURL)
			case <-mAuto.ClickedCh:
				if autoStartEnabled() {
					disableAutoStart()
				} else if !enableAutoStart() {
					log.Printf("设置开机自启失败")
				}
				if autoStartEnabled() {
					mAuto.Check()
				} else {
					mAuto.Uncheck()
				}
			case <-mQuit.ClickedCh:
				log.Printf("托盘退出，停止全部实例…")
				h.instances.stopAll()
				h.downloads.Shutdown()
				systray.Quit()
				return
			}
		}
	}()
}

func openBrowser(url string) {
	if err := exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start(); err != nil {
		log.Printf("打开浏览器失败: %v", err)
	}
}

// ---------- 开机自启（Startup 目录 .lnk 快捷方式，与 Java 版同思路） ----------

const autoStartLinkName = "audio.cpp-hub.lnk"

func autoStartLinkPath() string {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		home, _ := os.UserHomeDir()
		appData = filepath.Join(home, "AppData", "Roaming")
	}
	return filepath.Join(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", autoStartLinkName)
}

func autoStartEnabled() bool {
	return isRegularFile(autoStartLinkPath())
}

// enableAutoStart 在 Startup 目录创建指向当前 exe 的快捷方式（工作目录 = hub 工作目录）。
func enableAutoStart() bool {
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	exe, _ = filepath.Abs(exe)
	workDir, _ := os.Getwd()
	link := autoStartLinkPath()
	os.MkdirAll(filepath.Dir(link), 0755)
	script := fmt.Sprintf(
		"$shell = New-Object -ComObject WScript.Shell; "+
			"$sc = $shell.CreateShortcut('%s'); "+
			"$sc.TargetPath = '%s'; "+
			"$sc.WorkingDirectory = '%s'; "+
			"$sc.WindowStyle = 7; "+
			"$sc.Description = 'audio.cpp-hub (Go)'; "+
			"$sc.Save();",
		psEscape(link), psEscape(exe), psEscape(workDir))
	return runPowerShell(script) && isRegularFile(link)
}

func disableAutoStart() bool {
	return os.Remove(autoStartLinkPath()) == nil || !pathExists(autoStartLinkPath())
}

func psEscape(s string) string {
	return strings.ReplaceAll(s, "'", "''")
}

// runPowerShell 执行一段 PS 脚本（隐藏窗口，避免 GUI 进程闪控制台）。
func runPowerShell(script string) bool {
	cmd := exec.Command("powershell.exe", "-ExecutionPolicy", "Bypass", "-NoProfile",
		"-WindowStyle", "Hidden", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return cmd.Run() == nil
}
