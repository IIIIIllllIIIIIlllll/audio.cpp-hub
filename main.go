// audio.cpp-hub：audio.cpp 的 Web 管理面板（Go 实现，原 Java 版已归档 legacy/）。
// HTTP 服务 + audiocpp_server 实例进程管理 + 异步推理任务队列 + TTS 历史 +
// 模型权重下载 + /v1/* OpenAI 兼容代理。
// 工作目录约定：hub.config.json / executables.json / data/ / run/ / web/。
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
)

// version 构建版本，CI 用 -ldflags "-X main.version=<tag>" 注入。
var version = "dev"

// HubConfig 对应 hub.config.json，缺省值与 Java 版一致。
type HubConfig struct {
	HttpPort                int    `json:"httpPort"`
	InstancePortBase        int    `json:"instancePortBase"`
	ModelsDir               string `json:"modelsDir"`
	HfEndpoint              string `json:"hfEndpoint"`
	DownloadThreads         int    `json:"downloadThreads"`
	DownloadSegmentsPerFile int    `json:"downloadSegmentsPerFile"`
	ProxyMaxBodyBytes       int64  `json:"proxyMaxBodyBytes"`
}

func loadConfig() HubConfig {
	cfg := HubConfig{
		HttpPort:                8080,
		InstancePortBase:        18090,
		ModelsDir:               "models",
		HfEndpoint:              "https://huggingface.co",
		DownloadThreads:         8,
		DownloadSegmentsPerFile: 4,
		ProxyMaxBodyBytes:       1 << 30,
	}
	if data, err := os.ReadFile("hub.config.json"); err == nil {
		if err := json.Unmarshal(data, &cfg); err != nil {
			log.Printf("hub.config.json 解析失败，使用默认配置: %v", err)
		}
	}
	return cfg
}

// Hub 聚合各管理器，供 API 层使用。
type Hub struct {
	cfg       HubConfig
	instances *InstanceManager
	execs     *ExecutableRegistry
	profiles  *ProfileRegistry
	tasks     *TaskManager
	history   *HistoryManager
	voices    *VoiceLibrary
	downloads *DownloadManager
}

// ensureWorkDir 定位工作目录（需要 web/ 等运行时目录）：当前目录没有 web/ 时，
// 依次尝试上级目录与 exe 所在目录及其上级（双击 exe 时 cwd 可能不在程序目录）。
func ensureWorkDir() {
	if isDir("web") {
		return
	}
	candidates := []string{".."}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		candidates = append(candidates, dir, filepath.Join(dir, ".."))
	}
	for _, c := range candidates {
		if isDir(filepath.Join(c, "web")) {
			if err := os.Chdir(c); err == nil {
				log.Printf("工作目录切换到 %s", c)
			}
			return
		}
	}
}

func main() {
	log.SetFlags(log.LstdFlags)
	ensureWorkDir()
	setupPlatform()
	cfg := loadConfig()
	cleanupV1ProxyCache()
	hub := &Hub{
		cfg:       cfg,
		instances: NewInstanceManager(cfg.InstancePortBase),
		execs:     NewExecutableRegistry("executables.json"),
		profiles:  NewProfileRegistry("data/profiles.json"),
		history:   NewHistoryManager(),
		voices:    NewVoiceLibrary(),
		downloads: NewDownloadManager(cfg),
	}
	hub.tasks = NewTaskManager(hub.history)

	if _, err := os.Stat("web"); err != nil {
		log.Printf("警告: 工作目录下没有 web/ 目录，静态页面不可用（请从项目根目录启动）")
	}

	mux := http.NewServeMux()
	hub.registerRoutes(mux)

	// 退出前停止全部实例子进程并暂停下载任务（进度落盘，下次启动自动续传）
	go func() {
		ch := make(chan os.Signal, 1)
		signal.Notify(ch, os.Interrupt, syscall.SIGTERM)
		<-ch
		log.Printf("收到退出信号，停止全部实例…")
		hub.instances.stopAll()
		hub.downloads.Shutdown()
		os.Exit(0)
	}()

	// 先绑定端口再进托盘：端口占用等启动失败能立即暴露
	addr := fmt.Sprintf(":%d", cfg.HttpPort)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("监听 %s 失败: %v", addr, err)
	}
	log.Printf("audio.cpp-hub %s 监听 http://localhost:%d", version, cfg.HttpPort)
	runPlatform(hub, fmt.Sprintf("http://127.0.0.1:%d", cfg.HttpPort), func() error {
		return http.Serve(ln, mux)
	})
}
