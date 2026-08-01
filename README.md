# audio.cpp-hub

**简体中文 | [English](README_EN.md)**

[audio.cpp](https://github.com/0xShug0/audio.cpp) 的 Web 管理面板：一个用 Java（Netty）写的轻量 HTTP 服务，负责拉起 / 停止 / 监控多个 `audiocpp_server` 模型实例子进程，并提供中文为主的 Web UI 进行 TTS / ASR / 音乐分离等音频任务。

仓库地址：<https://github.com/IIIIIllllIIIIIlllll/audio.cpp-hub>

## 功能特性

- **多实例管理**：为每个模型实例生成配置并以子进程拉起 `audiocpp_server`，自动分配端口（绑定 127.0.0.1）、轮询健康状态、查看日志、一键停止
- **Web UI**：纯原生 HTML/JS 界面（无构建步骤），中英双语，支持模型选择、参数表单、任务提交
- **TTS 操作历史**：按模型隔离保存最近 50 条 / 500MB 的合成记录与结果音频，右侧边栏随时回听，音频懒加载
- **OpenAI 兼容代理**：`GET /v1/models` 列出全部就绪实例；`POST /v1/audio/speech` 等接口按服务名路由转发，大 base64 全程流式落盘转发，不进 JVM 堆，可直接对接各类 OpenAI 客户端
- **HTTPS 支持（可选）**：同端口自动识别 TLS / 纯 HTTP（后者 308 跳转），内置一键生成自签 CA + 服务器证书
- **Windows 友好**：系统托盘、注册表开机自启、C 语言启动器（内嵌 JRE 双击即用）
- **轻量**：hub 本身不加载模型，推荐 JVM 参数 `-Xms128m -Xmx128m`

## 快速开始

### 使用发布包（推荐）

从 [Releases](https://github.com/IIIIIllllIIIIIlllll/audio.cpp-hub/releases) 下载：

- 平台无关基础包：需要系统已安装 **Java 21+**
- `-windows` / `-linux` 完整包：内置 JRE 与启动器，解压即用

发布包**不包含 audio.cpp 二进制**，请自行下载 `audiocpp_server`（放入任意目录，如 `audiocpp/`），然后在 Web UI 中登记为可执行文件即可。模型权重下载地址可参考仓库中的 `model_download_urls.md`。

### 从源码构建

需要 JDK 21+。本项目直接用 `javac` 编译，**不使用 Maven 构建**（`pom.xml` 仅为 Eclipse 工程引子）：

```bash
# 编译（Windows 下 classpath 分隔符用 ;）
javac -encoding UTF-8 -d build/classes -cp "lib/*" $(find src/main/java -name "*.java")
cp -r src/main/resources/* build/classes/

# 运行
java -cp "build/classes;lib/*" org.mark.audiocpp.hub.AudioHubServer   # Windows
java -cp "build/classes:lib/*" org.mark.audiocpp.hub.AudioHubServer   # Linux
```

工作目录需包含 `web/` 与 `lib/`。启动后访问 `http://127.0.0.1:18080`（端口见 `hub.config.json`）。

可选：构建 C 启动器（需 CMake 3.16+ 与编译器），详见 [launcher/README.md](launcher/README.md)。

## 基本用法

1. **登记可执行文件**：在 UI 中添加 `audiocpp_server` 的路径（可为不同条目配置 `env` 环境变量，值支持 `${VAR}` 占位符）
2. **创建启动档案**：选择模型与参数（模型权重路径可通过内置文件浏览器选择）
3. **启动实例**：hub 写入 `run/<id>/server.json` 并拉起子进程，健康检查通过后即可使用
4. **提交任务**：在 Web UI 直接推理，或通过 OpenAI 兼容接口调用（`model` 填实例服务名）

## 配置

`hub.config.json`（首次运行自动生成）：

```json
{
  "httpPort": 18080,
  "instancePortBase": 18090,
  "proxyMaxBodyBytes": 1073741824,
  "https": {
    "enabled": true,
    "keystorePath": "ssl/keystore.p12",
    "keystorePassword": "..."
  }
}
```

- `httpPort`：hub 监听端口（代码默认 8080）
- `instancePortBase`：模型实例端口分配起点
- `proxyMaxBodyBytes`：`/v1/*` 代理请求体落盘上限（默认 1GB）
- `https`：可选，启用后同端口自动识别 TLS，纯 HTTP 一律 308 跳转；证书可通过 UI / `POST /api/cert/generate` 生成，生成或改配置后需重启生效

## API 概览

| 接口 | 说明 |
| --- | --- |
| `GET /api/models` | 支持的模型清单（`resources/models.json`） |
| `POST /api/run/<instanceId>` | 任务转发（TTS 走流式链路，结果自动入库历史） |
| `GET /api/history/<modelId>` 等 | TTS 操作历史查询 / 音频回取 / 删除 |
| `GET /v1/models`、`POST /v1/*` | OpenAI 兼容代理 |
| `GET /api/events` | 实例事件日志 |
| `/api/fs/*` | 服务器本地文件浏览（用于选择模型权重路径） |
| `/api/cert/*` | HTTPS 证书状态 / 生成 / 下载 |

## 目录说明

```
src/main/java/org/mark/audiocpp/hub/   # Java 源码（入口 AudioHubServer）
web/                                   # 前端静态文件（无构建步骤）
launcher/                              # C 语言启动器（CMake 构建）
lib/                                   # 本地依赖 jar（Netty / Gson / SLF4J + Log4j2）
run/                                   # 运行时：实例 server.json / 日志 / 代理缓存
data/                                  # 运行时：上传、音色库、启动档案、TTS 历史
logs/                                  # 运行时：按实例路由的日志（保留 7 天）
```

## 安全说明

> **本项目是局域网 / 本机工具，无鉴权，不具备公网防护能力。**
>
> 不要把端口直接暴露到公网。`/api/fs/*` 接口有意暴露服务器本地文件系统浏览，这是设计使然。需要远程访问时，请自行在前面加反向代理 + HTTPS + 鉴权。

## 技术栈

- Java 21，依赖以本地 jar 形式提供（`lib/`）：Netty 4.1、Gson、SLF4J + Log4j2
- 前端：原生 HTML/CSS/JS，中英双语（`web/i18n.js`）
- 发布：GitHub Actions 自动编译并打包三种 zip（基础包 / Linux 完整包 / Windows 完整包）
