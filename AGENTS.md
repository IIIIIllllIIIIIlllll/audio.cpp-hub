# audio.cpp-hub

## 项目概述

audio.cpp-hub 是 [audio.cpp](https://github.com/0xShug0/audio.cpp) 的 Web 管理面板：一个用 Java（Netty）写的轻量 HTTP 服务，负责拉起 / 停止 / 监控多个 `audiocpp_server` 模型实例子进程，并提供中文为主的 Web UI 进行 TTS / ASR / 音乐分离等音频任务。仓库地址：https://github.com/IIIIIllllIIIIIlllll/audio.cpp-hub

- 入口类：`org.mark.audiocpp.hub.AudioHubServer`
- hub 本身默认监听 `httpPort`（`hub.config.json`，本仓库开发副本为 18080，代码内默认 8080）；各模型实例从 `instancePortBase`（本副本 18090）起自动分配端口，绑定 127.0.0.1
- 模型实例不在 JVM 内运行：hub 为每个实例写 `run/<id>/server.json`，再用 `ProcessBuilder` 拉起外部 `audiocpp_server --config server.json`，stdout/stderr 重定向到 `run/<id>/server.log`，并后台轮询实例的 `/health`（最多 120s）
- 推理请求转发路径：前端 `POST /api/run/<instanceId>` → `SpeechForwarder` → 实例 `http://127.0.0.1:<port>/v1/tasks/run`，响应 JSON 原样透传。**TTS 任务走流式链路**：响应落盘到 `data/history/<modelId>/<taskId>.resp.tmp` → `HistoryAudioExtractor` 流式提取顶层 `"audio"` 字段解码写成 `<taskId>.wav` → 记录操作历史 → 临时文件分块流式回写前端（写完删除），大 base64 全程不进堆；非 TTS 任务维持整串透传
- 操作历史（TTS）：按 modelId 隔离到 `data/history/<modelId>/`（`index.jsonl` 一行一条记录只追加 + `<taskId>.wav` 结果音频），内存索引启动时回放重建，每模型上限 50 条 / 500MB 超出淘汰最旧。API：`GET /api/history/<modelId>`（简要列表，新→旧，text 截断 100 字并带 `textTruncated` 标记）、`GET /api/history/<modelId>/<taskId>`（完整记录）、`GET /api/history/<modelId>/<taskId>/audio`（流式回 wav）、`DELETE /api/history/<modelId>[/<taskId>]`（清空 / 单删）。前端为右侧边栏（窄屏抽屉式弹出），音频懒加载（点击播放才拉取 wav）；界面记住上次选中的模型（localStorage `hub-model`），刷新后历史视图不丢
- OpenAI 兼容代理：`GET /v1/models` 列出全部 READY 实例的服务名；`POST /v1/*`（如 `/v1/audio/speech`）由 `V1ProxyHandler` 接收——请求体分块落盘到 `run/proxy-cache/`（上限 `hub.config.json` 的 `proxyMaxBodyBytes`，默认 1GB），流式扫描提取顶层 `"model"` 后按服务名路由，路径与 body 原样 `ofFile` 转发到实例同名接口，响应分块流式回写；大 base64 全程不进 JVM 堆。错误体为 OpenAI 风格 `{"error":{"message","type"}}`
- 实例服务名（instanceName）：启动时可显式指定（默认 modelId），是 `/v1/*` 的路由键，也写进实例 server.json 的 model id（实例自校验一致）；全局唯一，重名拒绝启动
- 模型权重下载：`POST /api/downloads` 创建任务（创建即开始），两种 body：按模型 `{"modelId","packageId"?,"token"?,"overwrite"?,"endpoint"?}`（`packageId` 缺省取清单 default 包，URL 默认按 `hfEndpoint` 配置拼接，`endpoint` 可逐次覆盖下载源）或显式 `{"targetDir","files":[{url,path}],...}`。`DownloadManager` 用 JDK `java.net.http.HttpClient` 多线程 Range 分段下载到 `models/<targetDir>/`（先写 `<file>.part`，完成校验后改名）；`GET /api/downloads`（列表 + percent/speedBps）、`GET /api/downloads/<id>`（详情含分段）、`POST /api/downloads/<id>/pause|resume`（暂停/续传）、`DELETE /api/downloads/<id>?purge=`（取消，purge 清理 .part）。任务状态落盘 `data/downloads/<id>/task.json`（原子写，~1s 节流），hub 重启后未完成任务自动从分段断点续传；gated 仓库传 `token`（HF token，明文存 task.json，API 输出会剔除）；下载前并行 HEAD 探测大小/Range 能力并做磁盘空间预检，不支持 Range 的文件退化为整流下载（中断后该文件重下）。下载包清单在 `resources/model-packages.json`（由 audio.cpp 的 model_specs 转换，覆盖全部 42 个模型，含未收录进 models.json 的），查询接口 `GET /api/models/<modelId>/packages`。相关配置：`modelsDir`（默认 `models`）、`downloadThreads`（默认 8）、`downloadSegmentsPerFile`（默认 4，分段最小粒度 32MB）、`hfEndpoint`（默认 `https://huggingface.co`，不可直连时改镜像如 `https://hf-mirror.com`）。前端：模型卡片有 ⬇ 按钮打开「下载权重」弹窗（下载源/包选择/token/覆盖），页头 ⬇️ 按钮（带进行中任务数角标）打开「下载管理」面板（进度条、暂停/续传/删除，2s 轮询），DONE 任务可一键把 `models/<targetDir>` 填入启动表单权重路径

## 技术栈

- Java 21（`pom.xml` 编译目标；CI 实际用 JDK 26 编译、`-source/-target 21`）
- 依赖全部以 `system` scope 指向 `lib/*.jar`（不联网下载）：
  - Netty 4.1.35（HTTP 服务）、Gson 2.8.9（JSON）、SLF4J 2.0.9 + Log4j2 2.22.1（日志）
- 前端：`web/` 下纯原生 HTML/CSS/JS（`app.js`、`i18n.js` 中英双语、`wav.js` WAV 处理等），无构建工具，由 `StaticFileHandler` 直接从工作目录的 `web/` 提供
- 启动器：`launcher/` 下 C 语言 JNI Invocation API 启动器（CMake 构建），内嵌 JRE 即可运行
- 构建方式：直接用 `javac` 编译（本地开发与 CI 一致），**不通过 Maven 构建**——`pom.xml` 只是 Eclipse 工程引子（见 `.classpath`/`.project`），`mvn` 产出的 jar 不含 system-scope 依赖，不是正式的构建/打包途径

## 目录与模块划分

```
src/main/java/org/mark/audiocpp/hub/
├── AudioHubServer.java   # 入口：Netty 启动、Windows 系统托盘、程序自重启、hub.config.json 加载
├── BuildInfo.java        # 版本占位符（{tag}/{version}/{createdTime}），CI 打包时注入，勿手动改值
├── netty/                # HTTP pipeline：HttpServerCodec → V1ProxyHandler（/v1/* 流式代理，aggregator 之前）→
│                         # HttpObjectAggregator（/api/* 用，64MB）→ ApiHandler（/api/* 路由）→ StaticFileHandler（web/ 静态文件）；
│                         # 启用 HTTPS 时改用 HttpHttpsUnificationHandler 统一端口探测（TLS→HTTPS，纯 HTTP→308 跳转，
│                         # 见 HttpToHttpsRedirectHandler），SslContext 由 HttpsSupport 从密钥库构建
├── cert/                 # CertManager：HTTPS 证书状态查询与 keytool 自签 CA + 服务器证书生成（/api/cert/*），
│                         # 证书放 ssl/（keystore.p12 + ca-cert.cer），生成后需重启生效
├── instance/             # InstanceManager（子进程生命周期、端口分配、健康轮询、run/<id> 清理）、ModelInstance、
│                         # ServerConfigWriter（写实例 server.json）、EventLog（事件，GET /api/events）
├── proxy/                # SpeechForwarder（/api/run 任务转发：普通任务整串透传，TTS 用 forwardToFile 流式落盘）、
│                         # V1ProxyHandler（/v1/* 模型路由代理：落盘 + ofFile 转发 + 响应流式回写）、
│                         # RequestModelExtractor（流式扫描顶层 JSON 提取 "model"，大字段不落内存）
├── history/              # 操作历史（TTS）：HistoryManager（按 modelId 隔离的记录索引、JSONL 追加落盘、
│                         # 数量/容量双上限淘汰、data/history/<modelId>/ 布局）、
│                         # HistoryAudioExtractor（流式扫描响应 JSON 提取 "audio"，base64 边扫边解码写 wav）
├── download/             # 模型权重下载器：DownloadManager（JDK HttpClient 多线程 Range 分段、断点续传、
│                         # 进度/速率统计、任务状态原子落盘 data/downloads/<id>/task.json、启动自动续传、
│                         # HF token；单例，由 AudioHubServer 创建并注入 ApiHandler）、
│                         # DownloadTask（任务/文件/分段数据模型，Gson 直接序列化，含路径/URL 校验）
├── config/               # ExecutableRegistry（executables.json：audiocpp_server 可执行文件登记，条目可带 env 环境变量表，
│                         # 拉起子进程时注入，值支持 ${VAR} 占位符按 hub 进程环境展开；支持 PUT /api/executables/<id> 更新）、
│                         # ProfileRegistry（data/profiles.json：模型启动配置档案）
├── audio/                # AudioStore（data/uploads WAV 上传 + RIFF/WAV 头解析，无第三方依赖）、
│                         # VoiceLibrary（data/voices 音色库）
├── fs/                   # FileSystemBrowser：服务器本地文件浏览 API（/api/fs/*）
├── util/                 # Jsons（Gson 封装与错误 JSON）、UserException（带 code/params 的用户可读异常）、
│                         # ModelRegistry（加载 resources/models.json 模型清单）、
│                         # ModelPackageRegistry（加载 resources/model-packages.json 下载包清单、
│                         # 默认包选择、HF resolve URL 逐段编码构造）
└── win/                  # WindowsTray（系统托盘）、AutoStartManager（注册表开机自启）
src/main/resources/
├── models.json           # 支持模型清单（id/category/serverTask/paramSchema 等），GET /api/models 直接返回
├── model-packages.json   # 模型下载包清单（由 audio.cpp model_specs 转换：repo/revision/targetDir/files/default/gated），
│                         # GET /api/models/<id>/packages 返回，POST /api/downloads 按 modelId 消费
├── log4j2.xml            # 日志：按 MDC modelId 路由到 logs/<id>.log，否则 logs/app.log；按天滚动、保留 7 天
└── icon/icon.png
web/                      # 前端静态文件（无构建步骤）
launcher/                 # C 启动器（CMakeLists.txt + launcher.c + launcher.conf 模板）
lib/                      # 本地依赖 jar（pom 与 launcher.conf 都直接引用）
```

运行时（相对工作目录）产生的数据，均被 `.gitignore` 排除：`logs/`、`run/<instanceId>/`（server.json + server.log，停止后自动清理）、`data/`（uploads/、voices/、profiles.json、history/<modelId>/ 操作历史、downloads/<taskId>/ 下载任务状态）、`models/`（下载的模型权重）、`ssl/`（HTTPS 证书）、`build/`（javac 输出）、`executables.json`、`hub.config.json`。

## HTTPS

`hub.config.json` 增加 `https` 节即可启用（与 hub 同一端口，协议自动识别）：

```json
"https": { "enabled": true, "keystorePath": "ssl/keystore.p12", "keystorePassword": "..." }
```

- 启用后同一端口上 TLS 请求走 HTTPS，纯 HTTP 请求一律 308 跳转到 HTTPS；证书缺失/加载失败自动回退纯 HTTP
- 证书相关 API：`GET /api/cert/status`、`POST /api/cert/generate`（body 可带 `ips`/`hostnames`/`validity`/`password`/`keysize`/`cn`，基于 JDK 自带 keytool 生成自签 CA + 服务器证书并写回 https 配置）、`GET /api/cert/download?type=ca|keystore`
- SslContext 仅在启动时构建，生成证书或改配置后需重启程序；托盘"打开首页"按 `https.enabled` 选择协议

## 构建与运行

本地开发（需要 JDK 21+，直接用 `javac`，与 CI 的编译方式一致；**不要用 `mvn package`**）：

```bash
# 编译到 build/classes（Windows 下 classpath 分隔符用 ;）
javac -encoding UTF-8 -d build/classes -cp "lib/*" $(find src/main/java -name "*.java")
# Linux: javac -encoding UTF-8 -d build/classes -cp "lib/*" $(find src/main/java -name "*.java")

cp -r src/main/resources/* build/classes/   # 复制 resources（models.json、log4j2.xml、icon）

java -cp "build/classes;lib/*" org.mark.audiocpp.hub.AudioHubServer    # Windows
java -cp "build/classes:lib/*" org.mark.audiocpp.hub.AudioHubServer    # Linux
```

`pom.xml` 仅作为 Eclipse 工程引子存在（system-scope 依赖也不会打进 jar），`target/classes` 下的旧产物是 IDE 生成的，不是正式构建输出。

推荐 JVM 参数：`-Xms128m -Xmx128m -XX:MaxDirectMemorySize=128m`（模型由外部进程加载，hub 本身很轻量）。

工作目录需包含 `web/`（静态文件）、`lib/`（依赖）；首次运行后自行生成 `hub.config.json` / `data/` / `logs/` 等。

启动器（可选，需 CMake 3.16+ 与编译器）：

```bash
cmake -S launcher -B launcher/build
cmake --build launcher/build --config Release
# 产物 launcher/build/bin/audio.cpp-hub(.exe)，需与 launcher.conf（##MAINCLASS= + JVM 参数按行书写）同目录
```

## 测试

本项目目前**没有任何自动化测试**（无 `src/test`、无测试框架依赖）。改动后靠手动验证：编译通过后启动服务，打开 Web UI 或用 `curl` 打 `/api/*` 接口确认行为。

## 发布与部署

发布由 `.github/workflows/build-and-release.yml` 完成，推送 `v*.*.*` tag 或手动触发：

- 在 ubuntu-latest 上用 JDK 26 + `javac`（不经 Maven）编译，注入 `BuildInfo.java` 的版本占位符
- 下载 Mini-JRE（26.0.1，Linux/Windows），用 MinGW-w64 交叉编译 launcher
- 产出三种 zip：平台无关基础包（需系统 Java 21+）、`-linux` 完整包、`-windows` 完整包（后两者内置 JRE + launcher + run 脚本），并创建 GitHub Release
- 发布包不含 audio.cpp 二进制，用户需自行下载放入 `audiocpp/` 目录并通过 UI 登记可执行文件

## 代码约定

- **语言**：代码注释、日志消息、用户可见错误消息均为中文（部分用户可见文本中英双语）；标识符用英文。前端文案走 `web/i18n.js` 中英双语言
- Java 代码风格：4 空格缩进，类级 Javadoc 用中文简述职责；公开方法多带中文 Javadoc
- ID 生成统一为 `UUID.randomUUID().toString().substring(0, 8)`
- 持久化：各 Registry 直接读写作目录下的 JSON 文件（Gson `JsonObject`/`JsonArray`），方法级 `synchronized`，无数据库；文件不存在/为空即视为空列表
- 错误处理：用户可预期错误抛 `UserException(code, params, message)`，API 层转成 `{error: {code, params, message}}` 结构的 JSON；实例/注册表相关日志用 SLF4J MDC `modelId` 路由到独立日志文件
- 外部进程交互统一约定：`audiocpp_server --config <server.json>`，健康检查 `GET /health`，任务接口 `POST /v1/tasks/run`
- Windows 兼容细节：可执行文件路径自动补 `.exe` 探测；删除运行目录带重试（Windows 文件句柄释放延迟）；托盘/自启仅在 Windows 生效

## 安全注意事项

- 这是**局域网/本机工具，无鉴权、无 HTTPS，不具备公网防护能力**。不要把端口直接暴露到公网（发布说明中已明确警告）；远程访问请走反向代理 + HTTPS
- 防路径穿越的现有约定：文件名 id 一律用正则 `[a-zA-Z0-9-]{1,32}` 校验后再拼路径（见 `AudioStore.SAFE_ID`）；历史的 modelId/taskId 键用 `[a-zA-Z0-9_-]{1,64}`（多放行下划线，见 `HistoryManager.SAFE_KEY`）；下载的 targetDir 用 `[a-zA-Z0-9._-]{1,64}` 且必须含字母数字、文件相对路径逐段拒绝 `..`/绝对路径/盘符（见 `DownloadTask.validateTargetDir/validateFilePath`）；静态文件解析后强制校验 `resolved.startsWith(WEB_ROOT)`。新增任何接收路径/文件名的接口必须沿用同样的校验
- `/api/fs/*` 接口有意暴露服务器本地文件系统浏览（用于选择模型权重路径），这是设计使然，但再次说明不能暴露公网
- 上传限制：WAV 上限 50MB；Netty 聚合器上限 64MB
