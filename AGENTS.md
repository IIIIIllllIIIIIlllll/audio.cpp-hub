# audio.cpp-hub

## 项目概述

audio.cpp-hub 是 [audio.cpp](https://github.com/0xShug0/audio.cpp) 的 Web 管理面板：一个用 Go 写的轻量 HTTP 服务（原生单二进制，无需任何运行环境），负责拉起 / 停止 / 监控多个 `audiocpp_server` 模型实例子进程，并提供中文为主的 Web UI 进行 TTS / ASR / 音乐分离等音频任务。仓库地址：https://github.com/IIIIIllllIIIIIlllll/audio.cpp-hub

- 入口：仓库根目录 `main.go`（`func main`），Go module 为 `github.com/IIIIIllllIIIIIlllll/audio.cpp-hub`
- hub 本身默认监听 `httpPort`（`hub.config.json`，本仓库开发副本为 18080，代码内默认 8080）；各模型实例从 `instancePortBase`（本副本 18090）起自动分配端口，绑定 127.0.0.1
- 模型实例不在 hub 进程内运行：hub 为每个实例写 `run/<id>/server.json`，再用 `os/exec` 拉起外部 `audiocpp_server --config server.json`，stdout/stderr 重定向到 `run/<id>/server.log`，并后台轮询实例的 `/health`（最多 120s，见 `instance.go`）
- 推理任务走**异步队列**（前端主链路）：`POST /api/tasks`（body `{"instanceId","request":{...}}`）创建任务立即返回，`TaskManager`（`task.go`）为每个实例起一个单线程 executor **同实例串行排队执行**（与引擎 busy 锁语义一致），**无执行时长上限**；`GET /api/tasks[?active=1&modelId=]`（列表，活跃在前）、`GET /api/tasks/<id>`（详情含队列位置 position）、`GET /api/tasks/<id>/result`（非 TTS 结果 `data/tasks/<id>.result.json` 流式回写）、`DELETE /api/tasks/<id>`（QUEUED 直接取消 / RUNNING 中断 hub 侧等待——引擎会跑完，属已知限制 / 已结束则删除记录）。任务状态落盘 `data/tasks/<id>.task.json`（每次状态变迁原子写），hub 重启回放重建（上次中断时进行中的任务标记为 CANCELLED），已完成任务内存保留最近 100 条。TTS 任务复用历史链路：taskId 即历史记录 id，响应落盘后 `history.go` 中的音频提取器流式扫描提取 `"audio"` 写成 `data/history/<modelId>/<taskId>.wav`，前端结果音频直接用 `/api/history/.../audio` URL（不碰 base64）；非 TTS 结果统一 `forwardToFile` 落盘。前端 2s 轮询，**任务并入右侧操作历史侧栏**（任务创建即一条记录：进行中的在前，已结束的其次；TTS 终态与历史按 taskId 去重由历史行代表，非 TTS 完成任务行带「载入」可重新渲染结果、「详情」行内展开完整文本结果；进行中行内可取消，侧栏对所有类别开放），允许连续提交排队；页面加载与模型切换时经 `?modelId=` 重挂全部任务（进行中的恢复轮询），**刷新页面不再丢任务**。旧 `POST /api/run/<instanceId>` 同步接口保留兼容，TTS 流式链路：api.go → 实例 `http://127.0.0.1:<port>/v1/tasks/run`，TTS 响应落盘 → 提取进历史 → 临时文件分块回写
- 操作历史（TTS，`history.go`）：按 modelId 隔离到 `data/history/<modelId>/`（`index.jsonl` 一行一条记录只追加 + `<taskId>.wav` 结果音频 + `<taskId>.ref|emo|spkN.wav` 参考音频快照——记录时把请求里的 voice_ref/audio/voice_samples 源文件复制进历史目录，历史自包含，快照随记录一并删除），内存索引启动时回放重建。**无数量/容量淘汰**：历史是用户资产，只由用户手动删除（原 Java 版有 50 条/500MB 上限，Go 版按用户要求移除）。记录含 `refs`（快照名→原始文件名）、`refBytes`、`groupId` 等可选字段，旧记录向后兼容。API：`GET /api/history/<modelId>`（简要列表，新→旧，text 截断 100 字并带 `textTruncated` 标记）、`GET /api/history/<modelId>/<taskId>`（完整记录）、`GET /api/history/<modelId>/<taskId>/audio`（流式回 wav）、`GET /api/history/<modelId>/<taskId>/audio/<name>`（参考音频快照，name 为 ref|emo|spkN）、`DELETE /api/history/<modelId>[/<taskId>]`（清空 / 单删）。手动分组存 `groups.json`：`GET|POST /api/history/<modelId>/groups`、`PUT|DELETE .../groups/<gid>`、`PUT .../<taskId>/group`（移入/移出组；删组记录回未分组，清空历史保留分组）。前端为页头 🕘 按钮弹出的全屏面板（替换原右侧边栏），音频懒加载（点击播放才拉取 wav），历史行「详情」行内展开四要素（参考音频/参考文本/音色提示词/生成内容）、「移动」弹菜单换组，分组可折叠；界面记住上次选中的模型（localStorage `hub-model`），刷新后历史视图不丢
- 参考音频（音色库，`voices.go`）：全局资源，存 `data/voices/`（`<vid>.wav` + `index.json`），条目 = vid + 名称（全局唯一，重名拒绝 `VOICE_NAME_EXISTS`）+ 音频文本内容 `text`（部分模型要求参考音频配套文本）+ 音频文件。API：`GET /api/voices`（列表含 text）、`POST /api/voices`（`{name, text?, uploadId?|path?}`，上传件或服务器路径二选一）、`PUT /api/voices/<vid>`（改名称/文本，排除自身重名）、`GET /api/voices/<vid>/audio`（流式回 wav）、`DELETE /api/voices/<vid>`。前端：页头 🎙 按钮弹出全屏管理面板（voices-panel.js：列表/试听/行内编辑/删除/添加——添加走完整 AudioPicker 上传/录制/裁剪）；TTS 表单里所有参考音频入口（主 voice_ref、VibeVoice 多说话人、其它模型 voice_ref、index_tts2 情感参考）统一用 `VoiceSelect` 下拉组件（voice-select.js），选中即生效并把音色路径填入请求的 voice_ref，自动回填参考文本；ASR/分离等仍用 `AudioPicker`（已移除其「保存到音色库」入口，库管理只在大面板）
- OpenAI 兼容代理（`proxy.go`）：`GET /v1/models` 聚合全部 READY 实例的服务名；`POST|PUT /v1/*`（如 `/v1/audio/speech`）——请求体流式落盘到 `run/proxy-cache/`（上限 `hub.config.json` 的 `proxyMaxBodyBytes`，默认 1GB），逐字节扫描提取顶层 `"model"`（大 base64 字段不落内存）后按服务名路由（READY 才转发，启动中 409，不存在 404），落盘文件作为 body 转发到实例同名接口，响应状态码/Content-Type 透传、逐块 Flush（SSE 兼容）；上游无整体超时，客户端断开即取消。错误体为 OpenAI 风格 `{"error":{"message","type"}}`。已知限制：multipart/form-data 无法提取 model（extractor 只认 JSON），会 400
- 实例服务名（instanceName）：启动时可显式指定（默认 modelId），是 `/v1/*` 的路由键，也写进实例 server.json 的 model id（实例自校验一致）；全局唯一，重名拒绝启动
- 设备探测：`GET /api/executables/<id>/devices` 用该可执行文件运行 `--list-devices`（注入条目 env，60s 超时），解析输出为 `{devices:[{backend,index,name,type}],raw}`；前端启动弹窗打开/切换程序时自动探测，「设备」为下拉选单（选项显示设备名称，选中即联动后端，提交设备号）
- 高级参数（sessionOptions）：启动模型弹窗底部「高级参数」区按每行 key=value 填写，API 为启动/配置 body 的 `sessionOptions` 对象（值统一转字符串），经 `optStringMap` 校验后写入 server.json 模型条目的 `session_options`；随启动配置（Profile）持久化
- 模型权重下载（`download.go` + `packages.go`）：`POST /api/downloads` 创建任务（创建即开始），两种 body：按模型 `{"modelId","packageId"?,"token"?,"overwrite"?,"endpoint"?,"source"?}`（`packageId` 缺省取清单 default 包，URL 默认按 `hfEndpoint` 配置拼接，`endpoint` 可逐次覆盖下载源）或显式 `{"targetDir","files":[{url,path}],...}`。多线程 Range 分段下载到 `models/<targetDir>/`（先写 `<file>.part`，完成校验后改名；`os.File.WriteAt` 写偏移，共享信号量限制全局并发）；`GET /api/downloads`（列表 + percent/speedBps）、`GET /api/downloads/<id>`（详情含分段）、`POST /api/downloads/<id>/pause|resume`（暂停/续传，context cancel 快速中断 + runGeneration 代次）、`DELETE /api/downloads/<id>?purge=`（取消，purge 清理 .part）。任务状态落盘 `data/downloads/<id>/task.json`（原子写，~1s 节流），hub 重启后未完成任务自动从分段断点续传（按 .part 实际大小收敛各分段进度）；gated 仓库传 `token`（HF token，明文存 task.json，API 输出会剔除）；下载前并行 HEAD 探测大小/Range 能力并做磁盘空间预检（Windows 用 `golang.org/x/sys/windows`），不支持 Range 的文件退化为整流下载（中断后该文件重下）。下载包清单在根目录 `model-packages.json`（由 audio.cpp 的 model_specs 转换，覆盖全部 42 个模型），`go:embed` 内置，查询接口 `GET /api/models/<modelId>/packages`。**下载源扩展**：body 带 `source:"modelscope"` 时走 modelscope——repo 映射为 `HereIsMark/<repo名>`、revision 固定 `master`、URL `https://www.modelscope.cn/models/<repo>/resolve/master/<path>`；modelscope HEAD 不带 Content-Length，探测回退 GET `Range: bytes=0-0` 解析 Content-Range（注意它回 200 而非 206，两处都按头解析不挑状态码）；HereIsMark 下仅 audio.cpp-gguf 一个仓库，其它包走 modelscope 会 REMOTE_NOT_FOUND。相关配置：`modelsDir`（默认 `models`）、`downloadThreads`（默认 8）、`downloadSegmentsPerFile`（默认 4，分段最小粒度 32MB）、`hfEndpoint`（默认 `https://huggingface.co`，不可直连时改镜像如 `https://hf-mirror.com`）。前端：模型卡片有 ⬇ 按钮打开「下载权重」弹窗（下载源/包选择/token/覆盖），页头 ⬇️ 按钮（带进行中任务数角标）打开「下载管理」面板（进度条、暂停/续传/删除，2s 轮询），DONE 任务可一键把 `models/<targetDir>` 填入启动表单权重路径
- Windows 系统托盘（`tray_windows.go`，`getlantern/systray`；菜单：打开首页 / 开机自启 / 退出程序；开机自启在 Startup 目录创建 `audio.cpp-hub.lnk` 快捷方式；`-ldflags="-H windowsgui"` 编译无控制台窗口，日志 tee 到 `logs/hub.log`；非 Windows 走 `tray_other.go` 无托盘）；启动时 `ensureWorkDir` 自动定位工作目录（cwd 无 web/ 时尝试上级目录与 exe 目录，双击 exe 也能跑）
- 版本号：`main.go` 的 `var version = "dev"`，CI 用 `-ldflags "-X main.version=<tag>"` 注入，启动日志带版本号

## 技术栈

- Go 1.27，标准库为主；第三方依赖仅两个：`github.com/getlantern/systray`（Windows 托盘）、`golang.org/x/sys`（Windows 磁盘空间预检）
- 前端：`web/` 下纯原生 HTML/CSS/JS（`app.js`、`i18n.js` 中英双语、`wav.js` WAV 处理等），无构建工具，由 Go 的 `http.FileServer` 直接从工作目录的 `web/` 提供
- 模型清单 `models.json` / `model-packages.json` 在仓库根目录，`go:embed` 进二进制
- 原 Java 版（Netty）已归档到 `legacy/`（src/lib/launcher/pom.xml 等），**仅供查阅，不再维护**；其行为语义是 Go 版移植的参照

## 目录与模块划分

```
仓库根目录（Go 主工程，package main）：
├── main.go               # 入口：hub.config.json 加载、Hub 聚合各管理器、工作目录自动定位、
│                         # 退出信号处理（停实例 + 暂停下载落盘）、version 变量（CI 注入）
├── api.go                # 全部 /api/* 路由与 handler（模型/实例/executables/profiles/run/tasks/
│                         # history/voices/audio/fs/downloads），OpenAI 错误格式之外的 JSON 响应约定
├── instance.go           # 实例生命周期（server.json 生成、进程拉起、端口分配、健康轮询、
│                         # run/<id> 清理、事件日志）、FindByName/FindAnyByName（/v1 路由）、设备探测
├── task.go               # 推理任务队列（每实例串行 executor、状态落盘回放、TTS 复用历史链路、
│                         # 非 TTS forwardToFile 落盘结果）
├── history.go            # TTS 操作历史（index.jsonl 索引、参考音频快照、groups.json 分组、
│                         # 响应 JSON 流式提取 "audio" 写 wav；无容量淘汰，只手动删）
├── download.go           # 模型权重下载器（Range 分段 + WriteAt、断点续传、暂停/恢复/取消、
│                         # task.json 原子落盘、重启自动续传、速率采样）
├── download_disk_windows.go / download_disk_other.go  # 磁盘空间预检（Windows 用 x/sys，其它平台跳过）
├── packages.go           # 下载包清单（model-packages.json embed、default 包选择、
│                         # resolve URL 逐段编码、modelscope 源映射）
├── proxy.go              # /v1/* OpenAI 兼容代理（请求体落盘、逐字节提取 "model"、按服务名路由、
│                         # 响应逐块 Flush 透传、OpenAI 风格错误体、启动清扫 proxy-cache）
├── registry.go           # ExecutableRegistry（executables.json 可执行文件登记，条目可带 env，
│                         # ${VAR} 占位符按 hub 进程环境展开）与 ProfileRegistry（data/profiles.json）
├── audio.go              # data/uploads WAV 上传 + RIFF/WAV 头解析（无第三方依赖）
├── voices.go             # data/voices 全局音色库（名称唯一 + 文本内容 + 音频文件）
├── fs.go                 # /api/fs/* 服务器本地文件浏览
├── fsattr_windows.go / fsattr_other.go  # Windows 隐藏属性判断（build tag 分平台）
├── tray_windows.go       # Windows 系统托盘 + 开机自启（Startup 目录 .lnk）+ 日志 tee logs/hub.log
├── tray_other.go         # 非 Windows 平台托盘 stub（直接跑 HTTP 服务）
├── util.go               # JSON 响应约定（errJSON/okJSON/writeJSON）、UserError、readBodyMap、
│                         # optString/optIntPtr/optStringMap、newID（8 位随机 hex）、writeFileAtomic 等
├── models.go             # models.json embed 与模型查询
├── models.json           # 支持模型清单（id/category/serverTask/paramSchema 等），GET /api/models 直接返回
├── model-packages.json   # 模型下载包清单（repo/revision/targetDir/files/default/gated）
├── icon.ico              # 托盘图标（go:embed）
├── go.mod / go.sum
web/                      # 前端静态文件（无构建步骤）
legacy/                   # 原 Java 版归档（src/lib/launcher/pom.xml/.classpath/.project/.settings），仅供查阅
.github/workflows/        # 发布流水线（见「发布与部署」）
```

运行时（相对工作目录）产生的数据，均被 `.gitignore` 排除：`logs/`、`run/<instanceId>/`（server.json + server.log + proxy-cache/，停止后自动清理）、`data/`（uploads/、voices/、profiles.json、history/<modelId>/ 操作历史、downloads/<taskId>/ 下载任务状态、tasks/<id>.task.json 推理任务状态 + <id>.result.json 非 TTS 结果，重启回放）、`models/`（下载的模型权重）、`ssl/`（HTTPS 证书，Go 版尚未实现）、`executables.json`、`hub.config.json`、`audio.cpp-hub(.exe)` 构建产物。

## 构建与运行

本地开发（Go SDK 装在 `C:\Users\Mark\go-sdk\go1.27.1`，未加 PATH；GOPROXY 已 `go env -w` 设为 `https://goproxy.cn,direct`）：

```bash
export PATH=/c/Users/Mark/go-sdk/go1.27.1/bin:$PATH   # Windows Git Bash

go vet ./... && go build -o audio.cpp-hub.exe .                          # 控制台调试版
go build -ldflags="-H windowsgui" -o audio.cpp-hub.exe .                 # 托盘发布版（无控制台窗口）

./audio.cpp-hub.exe    # 运行无需固定目录（ensureWorkDir 自动定位 web/ 所在目录）
```

交叉编译（CGO_ENABLED=0 即可，systray 有平台 build tag）：

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o audio.cpp-hub .
```

首次运行后自行生成 `hub.config.json` / `data/` / `logs/` 等。代码改动后如需更新模型清单，直接编辑根目录 `models.json` / `model-packages.json`（go:embed 内置，无需复制步骤）。

## 测试

本项目目前**没有任何自动化测试**（无 `_test.go`、无测试框架依赖）。改动后靠手动验证：`go vet ./...` 与构建通过后启动服务，打开 Web UI 或用 `curl` 打 `/api/*`、`/v1/*` 接口确认行为。

## 发布与部署

发布由 `.github/workflows/build-and-release.yml` 完成，推送 `v*.*.*` tag 或手动触发：

- 在 ubuntu-latest 上用 actions/setup-go@v5（Go 1.27）交叉编译，`-ldflags "-X main.version=$VERSION"` 注入版本（无 tag 时 dev-<sha7>）
- 产出两个原生 zip（CGO_ENABLED=0，无 JRE/launcher）：`audio.cpp-hub-<VERSION>-windows.zip`（`-H windowsgui` 托盘版 exe）与 `audio.cpp-hub-<VERSION>-linux.zip`，包内含二进制 + web/ + README.md + 空 audiocpp/ 占位目录
- softprops/action-gh-release@v1 创建 GitHub Release（双语 notes：备份 data/ 提醒、安全警告、变更 commit 列表），zip 同时上传 artifact
- 发布包不含 audio.cpp 二进制，用户需自行下载放入 `audiocpp/` 目录并通过 UI 登记可执行文件

## 代码约定

- **语言**：代码注释、日志消息、用户可见错误消息均为中文（部分用户可见文本中英双语）；标识符用英文。前端文案走 `web/i18n.js` 中英双语言
- Go 代码风格：gofmt 标准格式；导出的管理器方法与类型多带中文注释简述职责
- ID 生成统一为 8 位随机 hex（`util.go` 的 `newID()`，对应原 Java 版 UUID 前 8 位）
- 持久化：各 Registry/管理器直接读写工作目录下的 JSON 文件（`encoding/json`），互斥锁保护，无数据库；文件不存在/为空即视为空列表；状态文件一律原子写（tmp + rename，见 `writeFileAtomic`）
- 错误处理：用户可预期错误返回 `UserError{Code, Params, Msg}`（`util.go`），API 层转成 `{"ok":false,"code","params","error"}` 结构的 JSON；`/v1/*` 代理用 OpenAI 风格 `{"error":{"message","type"}}`
- 外部进程交互统一约定：`audiocpp_server --config <server.json>`，健康检查 `GET /health`，任务接口 `POST /v1/tasks/run`
- Windows 兼容细节：可执行文件路径自动补 `.exe` 探测；删除运行目录带重试（Windows 文件句柄释放延迟）；托盘/自启仅在 Windows 生效（build tag 分平台）

## 安全注意事项

- 这是**局域网/本机工具，无鉴权、无 HTTPS，不具备公网防护能力**。不要把端口直接暴露到公网（发布说明中已明确警告）；远程访问请走反向代理 + HTTPS
- 防路径穿越的现有约定：文件名 id 一律用正则 `^[a-zA-Z0-9-]{1,32}$` 校验后再拼路径（见 `api.go` 的 `safeTaskID`、`audio.go` 上传件 id）；历史的 modelId/taskId 键用 `^[a-zA-Z0-9_-]{1,64}$`（多放行下划线，见 `history.go` 的 `historySafeKey`）；下载的 targetDir 用 `[a-zA-Z0-9._-]{1,64}` 且必须含字母数字、文件相对路径逐段拒绝 `..`/绝对路径/盘符（见 `download.go` 的 `validateDlTargetDir/validateDlFilePath`）。新增任何接收路径/文件名的接口必须沿用同样的校验
- `/api/fs/*` 接口有意暴露服务器本地文件系统浏览（用于选择模型权重路径），这是设计使然，但再次说明不能暴露公网
- 上传限制：WAV 上限 50MB；`/api/*` 请求体上限 64MB（`util.go` 的 `maxBodyBytes`）；`/v1/*` 代理请求体上限 `proxyMaxBodyBytes`（默认 1GB）
