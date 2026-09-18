//go:build !windows

package main

import "log"

// setupPlatform 非 Windows 平台无托盘，日志保持控制台输出。
func setupPlatform() {}

// runPlatform 非 Windows 平台直接阻塞跑 HTTP 服务。
func runPlatform(h *Hub, homeURL string, serve func() error) {
	if err := serve(); err != nil {
		log.Fatal(err)
	}
}
