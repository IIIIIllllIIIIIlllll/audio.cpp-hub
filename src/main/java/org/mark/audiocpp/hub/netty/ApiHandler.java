package org.mark.audiocpp.hub.netty;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.mark.audiocpp.hub.AudioHubServer;
import org.mark.audiocpp.hub.audio.AudioStore;
import org.mark.audiocpp.hub.audio.VoiceLibrary;
import org.mark.audiocpp.hub.cert.CertManager;
import org.mark.audiocpp.hub.config.ExecutableRegistry;
import org.mark.audiocpp.hub.config.ProfileRegistry;
import org.mark.audiocpp.hub.download.DownloadManager;
import org.mark.audiocpp.hub.fs.FileSystemBrowser;
import org.mark.audiocpp.hub.history.HistoryAudioExtractor;
import org.mark.audiocpp.hub.history.HistoryManager;
import org.mark.audiocpp.hub.instance.InstanceManager;
import org.mark.audiocpp.hub.instance.ModelInstance;
import org.mark.audiocpp.hub.proxy.SpeechForwarder;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.ModelPackageRegistry;
import org.mark.audiocpp.hub.util.ModelRegistry;
import org.mark.audiocpp.hub.util.UserException;
import org.mark.audiocpp.hub.util.WeightsPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API 路由。处理不了的 GET 请求透传给 StaticFileHandler。
 */
public class ApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);

    private final InstanceManager instanceManager;
    private final ExecutableRegistry executableRegistry;
    private final ProfileRegistry profileRegistry;
    private final DownloadManager downloadManager;
    private final AudioHubServer.HubConfig config;
    private final SpeechForwarder speechForwarder = new SpeechForwarder();
    private final VoiceLibrary voiceLibrary = new VoiceLibrary();
    private final FileSystemBrowser fileSystemBrowser = new FileSystemBrowser();
    private final HistoryManager historyManager = new HistoryManager();

    public ApiHandler(InstanceManager instanceManager, ExecutableRegistry executableRegistry,
                      ProfileRegistry profileRegistry, DownloadManager downloadManager,
                      AudioHubServer.HubConfig config) {
        this.instanceManager = instanceManager;
        this.executableRegistry = executableRegistry;
        this.profileRegistry = profileRegistry;
        this.downloadManager = downloadManager;
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();
        HttpMethod method = request.method();

        if (path.startsWith("/api/")) {
            handleApi(ctx, request, method, decoder);
        } else if (method.equals(HttpMethod.GET)) {
            // 非 API 的 GET 交给静态文件处理器
            ctx.fireChannelRead(request.retain());
        } else {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND, Jsons.error("NOT_FOUND", null, "not found"), request);
        }
    }

    private void handleApi(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method,
                           QueryStringDecoder decoder) throws Exception {
        String path = decoder.path();
        if (method.equals(HttpMethod.GET) && path.equals("/api/models")) {
            sendJson(ctx, HttpResponseStatus.OK, ModelRegistry.rawJson(), request);
        } else if (method.equals(HttpMethod.GET) && path.startsWith("/api/models/")
                && path.endsWith("/packages")) {
            // 模型下载包清单：GET /api/models/<modelId>/packages
            String modelId = path.substring("/api/models/".length(), path.length() - "/packages".length());
            JsonObject family = ModelPackageRegistry.findByModel(modelId);
            if (family == null) {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("MODEL_UNKNOWN", Map.of("modelId", modelId),
                                "模型无下载清单: " + modelId), request);
            } else {
                sendJson(ctx, HttpResponseStatus.OK, family.toString(), request);
            }
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/instances")) {
            JsonArray array = new JsonArray();
            for (ModelInstance instance : instanceManager.list()) {
                array.add(JsonParser.parseString(Jsons.GSON.toJson(toJson(instance))));
            }
            sendJson(ctx, HttpResponseStatus.OK, array.toString(), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/instances")) {
            handleStartInstance(ctx, request);
        } else if (method.equals(HttpMethod.DELETE) && path.startsWith("/api/instances/")) {
            String id = path.substring("/api/instances/".length());
            if (instanceManager.stop(id)) {
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("INSTANCE_NOT_FOUND", Map.of("id", id), "实例不存在: " + id), request);
            }
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/events")) {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(instanceManager.events().list()), request);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/executables")) {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(executableRegistry.list()), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/executables")) {
            handleExecutableAdd(ctx, request);
        } else if (method.equals(HttpMethod.PUT) && path.startsWith("/api/executables/")) {
            handleExecutableUpdate(ctx, request, path.substring("/api/executables/".length()));
        } else if (method.equals(HttpMethod.DELETE) && path.startsWith("/api/executables/")) {
            String id = path.substring("/api/executables/".length());
            if (executableRegistry.delete(id)) {
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("EXEC_NOT_FOUND", Map.of("id", id), "可执行文件不存在: " + id), request);
            }
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/profiles")) {
            // 附带 weightsExists：前端据此判断权重是否仍然有效（决定模型卡片是否黯淡）。
            // 权重可以是目录（safetensors / 含 model.gguf），也可以是单个 .gguf 文件。
            List<JsonObject> profiles = profileRegistry.list();
            for (JsonObject p : profiles) {
                String weightsPath = p.has("weightsPath") ? p.get("weightsPath").getAsString() : "";
                p.addProperty("weightsExists", WeightsPaths.exists(weightsPath));
            }
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(profiles), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/profiles")) {
            handleProfileSave(ctx, request, null);
        } else if (method.equals(HttpMethod.PUT) && path.startsWith("/api/profiles/")) {
            String id = path.substring("/api/profiles/".length());
            handleProfileSave(ctx, request, id);
        } else if (method.equals(HttpMethod.DELETE) && path.startsWith("/api/profiles/")) {
            String id = path.substring("/api/profiles/".length());
            if (profileRegistry.delete(id)) {
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("PROFILE_NOT_FOUND", Map.of("id", id), "配置不存在: " + id), request);
            }
        } else if (method.equals(HttpMethod.POST) && path.startsWith("/api/run/")) {
            String instanceId = path.substring("/api/run/".length());
            handleRun(ctx, request, instanceId);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/downloads")) {
            sendJson(ctx, HttpResponseStatus.OK, downloadManager.list().toString(), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/downloads")) {
            handleDownloadCreate(ctx, request);
        } else if (path.startsWith("/api/downloads/")) {
            handleDownload(ctx, request, method, decoder, path.substring("/api/downloads/".length()));
        } else if (path.startsWith("/api/history/")) {
            handleHistory(ctx, request, method, path.substring("/api/history/".length()));
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/audio/upload")) {
            handleAudioUpload(ctx, request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/audio/info")) {
            handleAudioInfo(ctx, request);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/audio/file")) {
            handleAudioFile(ctx, request, decoder);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/voices")) {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(voiceLibrary.list()), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/voices")) {
            handleVoiceSave(ctx, request);
        } else if (method.equals(HttpMethod.DELETE) && path.startsWith("/api/voices/")) {
            String vid = path.substring("/api/voices/".length());
            if (voiceLibrary.delete(vid)) {
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("vid", vid)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("VOICE_NOT_FOUND", Map.of("id", vid), "音色不存在: " + vid), request);
            }
        } else if (method.equals(HttpMethod.GET) && path.startsWith("/api/voices/") && path.endsWith("/audio")) {
            String vid = path.substring("/api/voices/".length(), path.length() - "/audio".length());
            handleVoiceAudio(ctx, request, vid);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/fs/roots")) {
            sendJson(ctx, HttpResponseStatus.OK, fileSystemBrowser.roots().toString(), request);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/fs/list")) {
            handleFsList(ctx, request, decoder);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/fs/stat")) {
            String pathParam = firstParam(decoder, "path");
            sendJson(ctx, HttpResponseStatus.OK, fileSystemBrowser.stat(pathParam).toString(), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/fs/mkdir")) {
            handleFsMkdir(ctx, request);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/cert/status")) {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(CertManager.status()), request);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/cert/generate")) {
            handleCertGenerate(ctx, request);
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/cert/download")) {
            handleCertDownload(ctx, request, decoder);
        } else if (method.equals(HttpMethod.POST) && path.equals("/api/https/config")) {
            handleHttpsConfig(ctx, request);
        } else {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Jsons.error("UNKNOWN_API", Map.of("path", path), "unknown api: " + path), request);
        }
    }

    /** 启动实例：校验 modelId 在注册表内、weightsPath 非空。 */
    private void handleStartInstance(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        String modelId = optString(body, "modelId");
        String weightsPath = optString(body, "weightsPath");
        JsonObject modelEntry = ModelRegistry.findById(modelId);
        if (modelEntry == null) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("MODEL_UNKNOWN", Map.of("modelId", String.valueOf(modelId)),
                            "modelId 不在注册表中: " + modelId), request);
            return;
        }
        if (weightsPath == null || weightsPath.isEmpty()) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("WEIGHTS_REQUIRED", null, "weightsPath 不能为空"), request);
            return;
        }
        if (!WeightsPaths.exists(weightsPath)) {
            // 错误消息用原始输入：非法路径字符会让 resolve() 抛 InvalidPathException，不能再解析一次
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("WEIGHTS_NOT_FOUND", Map.of("path", weightsPath),
                            "权重路径不存在: " + weightsPath), request);
            return;
        }
        // 相对路径在此绝对化，确保写入 server.json 的是绝对路径（audiocpp_server 不按 hub 工作目录解析）
        String resolvedWeights = WeightsPaths.resolve(weightsPath).toString();
        String backend = optString(body, "backend");
        if (backend == null || backend.isEmpty()) {
            backend = "cpu";
        }
        Integer device = body.has("device") && body.get("device").isJsonPrimitive() ? body.get("device").getAsInt() : null;
        Integer port = body.has("port") && body.get("port").isJsonPrimitive() ? body.get("port").getAsInt() : null;
        Integer threads = body.has("threads") && body.get("threads").isJsonPrimitive() ? body.get("threads").getAsInt() : null;
        if (threads != null && threads <= 0) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("THREADS_POSITIVE", null, "threads 必须为正整数"), request);
            return;
        }
        // 解析可执行文件：缺省用注册表第一个条目
        String executableId = optString(body, "executableId");
        JsonObject executable;
        if (executableId != null && !executableId.isEmpty()) {
            executable = executableRegistry.findById(executableId);
            if (executable == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                        Jsons.error("EXEC_NOT_FOUND", Map.of("id", executableId),
                                "可执行文件不存在: " + executableId), request);
                return;
            }
        } else {
            executable = executableRegistry.first();
            if (executable == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                        Jsons.error("NO_EXECUTABLE", null, "尚未配置可执行文件，请点击右上角设置添加"), request);
                return;
            }
        }
        String executablePath = executableRegistry.resolvePath(executable).toString();
        String executableName = executable.get("name").getAsString();
        String serverTask = modelEntry.has("serverTask") ? modelEntry.get("serverTask").getAsString() : "tts";
        // 条目可携带 env 环境变量表，拉起子进程时注入
        Map<String, String> env = new LinkedHashMap<>();
        if (executable.has("env") && executable.get("env").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : executable.getAsJsonObject("env").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    env.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        try {
            ModelInstance instance = instanceManager.start(modelId, resolvedWeights, backend, device, port,
                    threads, executablePath, executableName, serverTask, env, optString(body, "name"));
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(toJson(instance)), request);
        } catch (Exception e) {
            log.error("启动实例失败", e);
            String msg = Jsons.summarize(e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    e instanceof UserException ue
                            ? Jsons.error(ue.getCode(), ue.getParams(), ue.getMessage())
                            : Jsons.error("LAUNCH_FAILED", Map.of("msg", msg), "启动实例失败: " + msg), request);
        }
    }

    /** 添加可执行文件：{"name","path","note"?,"env"?}，env 为 {变量名: 值} 对象。 */
    private void handleExecutableAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        try {
            JsonObject entry = executableRegistry.add(optString(body, "name"),
                    optString(body, "path"), optString(body, "note"), optEnv(body));
            sendJson(ctx, HttpResponseStatus.OK, entry.toString(), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
    }

    /** 更新可执行文件：{"name","path","note"?,"env"?}，id/createdAt 保留；不存在返回 404。 */
    private void handleExecutableUpdate(ChannelHandlerContext ctx, FullHttpRequest request, String id) throws Exception {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        try {
            JsonObject entry = executableRegistry.update(id, optString(body, "name"),
                    optString(body, "path"), optString(body, "note"), optEnv(body));
            if (entry == null) {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("EXEC_NOT_FOUND", Map.of("id", id), "可执行文件不存在: " + id), request);
                return;
            }
            sendJson(ctx, HttpResponseStatus.OK, entry.toString(), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
    }

    /** 提取请求体中的 env 对象（{变量名: 值}），缺失或非对象返回 null。 */
    private JsonObject optEnv(JsonObject body) {
        return body.has("env") && body.get("env").isJsonObject() ? body.getAsJsonObject("env") : null;
    }

    /**
     * 新增/更新启动配置：{"name","modelId","weightsPath","backend"?,"device"?,"port"?,"threads"?,"executableId"?}。
     * existingId 为 null 时新增，否则按 id 更新（不存在返回 404）。
     */
    private void handleProfileSave(ChannelHandlerContext ctx, FullHttpRequest request, String existingId) throws Exception {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        String name = optString(body, "name");
        String modelId = optString(body, "modelId");
        String weightsPath = optString(body, "weightsPath");
        if (name == null || name.trim().isEmpty()) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("NAME_REQUIRED", null, "name 不能为空"), request);
            return;
        }
        if (ModelRegistry.findById(modelId) == null) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("MODEL_UNKNOWN", Map.of("modelId", String.valueOf(modelId)),
                            "modelId 不在注册表中: " + modelId), request);
            return;
        }
        if (weightsPath == null || weightsPath.isEmpty()) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("WEIGHTS_REQUIRED", null, "weightsPath 不能为空"), request);
            return;
        }
        String backend = optString(body, "backend");
        if (backend == null || backend.isEmpty()) {
            backend = "cpu";
        }
        JsonObject fields = new JsonObject();
        fields.addProperty("name", name.trim());
        fields.addProperty("modelId", modelId);
        fields.addProperty("weightsPath", weightsPath);
        fields.addProperty("backend", backend);
        String executableId = optString(body, "executableId");
        if (executableId != null && !executableId.isEmpty()) {
            fields.addProperty("executableId", executableId);
        }
        String instanceName = optString(body, "instanceName");
        if (instanceName != null && !instanceName.trim().isEmpty()) {
            fields.addProperty("instanceName", instanceName.trim());
        }
        for (String key : new String[]{"device", "port", "threads"}) {
            if (body.has(key) && body.get(key).isJsonPrimitive()) {
                int value;
                try {
                    value = body.get(key).getAsInt();
                } catch (Exception e) {
                    sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                            Jsons.error("KEY_INT", Map.of("key", key), key + " 必须为整数"), request);
                    return;
                }
                if (value < 0 || ("threads".equals(key) && value == 0)) {
                    sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                            Jsons.error("KEY_POSITIVE", Map.of("key", key), key + " 必须为正整数"), request);
                    return;
                }
                fields.addProperty(key, value);
            }
        }
        try {
            if (existingId == null) {
                sendJson(ctx, HttpResponseStatus.OK, profileRegistry.add(fields).toString(), request);
            } else {
                JsonObject updated = profileRegistry.update(existingId, fields);
                if (updated == null) {
                    sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                            Jsons.error("PROFILE_NOT_FOUND", Map.of("id", existingId),
                                    "配置不存在: " + existingId), request);
                } else {
                    sendJson(ctx, HttpResponseStatus.OK, updated.toString(), request);
                }
            }
        } catch (Exception e) {
            String msg = Jsons.summarize(e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    e instanceof UserException ue
                            ? Jsons.error(ue.getCode(), ue.getParams(), ue.getMessage())
                            : Jsons.error("PROFILE_SAVE_FAILED", Map.of("msg", msg), "保存配置失败: " + msg), request);
        }
    }

    /** 通用任务转发：body {"request":{...}} → 实例 /v1/tasks/run，响应 JSON 原样透传。 */
    private void handleRun(ChannelHandlerContext ctx, FullHttpRequest request, String instanceId) {
        ModelInstance instance = instanceManager.get(instanceId);
        if (instance == null) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Jsons.error("INSTANCE_NOT_FOUND", Map.of("id", instanceId), "实例不存在: " + instanceId), request);
            return;
        }
        if (instance.getStatus() != ModelInstance.Status.READY) {
            sendJson(ctx, HttpResponseStatus.CONFLICT,
                    Jsons.error("INSTANCE_NOT_READY", Map.of("status", instance.getStatus().name()),
                            "实例未就绪，当前状态: " + instance.getStatus()), request);
            return;
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        // 同步阻塞转发：Netty worker 线程会被占住，但并发量极小（本地单用户工具），可接受
        try {
            if (isTts(instance)) {
                // TTS 走流式链路：响应落盘 → 提取音频进历史 → 文件流式回写，大 base64 不进堆
                handleRunTts(ctx, request, instance, body);
                return;
            }
            String result = speechForwarder.forward(instance, body);
            sendJson(ctx, HttpResponseStatus.OK, result, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Jsons.error("FORWARD_INTERRUPTED", null, "转发被中断"), request);
        } catch (Exception e) {
            log.warn("任务转发失败: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.BAD_GATEWAY, errorJson(e), request);
        }
    }

    /** 实例是否 TTS 模型（models.json 的 category）。 */
    private boolean isTts(ModelInstance instance) {
        JsonObject model = ModelRegistry.findById(instance.getModelId());
        return model != null && model.has("category") && "tts".equals(model.get("category").getAsString());
    }

    /**
     * TTS 任务：响应落盘临时文件 → 流式提取 audio 写成 data/history/<modelId>/<taskId>.wav →
     * 记录历史 → 临时文件流式回写给前端（完成后删除）。大 base64 全程不进堆。
     */
    private void handleRunTts(ChannelHandlerContext ctx, FullHttpRequest request, ModelInstance instance,
                              JsonObject body) {
        String modelId = instance.getModelId();
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Path tmp;
        try {
            tmp = historyManager.tempResponsePath(modelId, taskId);
        } catch (IOException e) {
            log.warn("历史目录不可用: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Jsons.error("HISTORY_IO", null, "历史目录不可用: " + e.getMessage()), request);
            return;
        }
        try {
            speechForwarder.forwardToFile(instance, body, tmp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(tmp);
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Jsons.error("FORWARD_INTERRUPTED", null, "转发被中断"), request);
            return;
        } catch (Exception e) {
            log.warn("TTS 任务转发失败: {}", e.getMessage());
            historyManager.recordTts(instance, body, taskId, null, e.getMessage());
            deleteQuietly(tmp);
            sendJson(ctx, HttpResponseStatus.BAD_GATEWAY, errorJson(e), request);
            return;
        }
        // 提取结果音频并解析 WAV 头（时长/采样率进历史元数据）
        JsonObject result = null;
        String error = null;
        Path wav = historyManager.wavPath(modelId, taskId);
        try {
            HistoryAudioExtractor.Result extracted = HistoryAudioExtractor.extract(tmp, wav);
            if (extracted.audioFound()) {
                AudioStore.WavInfo info = AudioStore.parseWav(wav);
                result = new JsonObject();
                result.addProperty("file", taskId + ".wav");
                result.addProperty("size", Files.size(wav));
                result.addProperty("durationSec", Math.round(info.durationSec * 1000.0) / 1000.0);
                result.addProperty("sampleRate", info.sampleRate);
                result.addProperty("channels", info.channels);
            } else {
                error = "响应中未找到音频数据";
            }
        } catch (Exception e) {
            log.warn("TTS 结果音频提取失败: {}", e.getMessage());
            error = "结果音频提取失败: " + Jsons.summarize(e.getMessage());
            deleteQuietly(wav);
        }
        historyManager.recordTts(instance, body, taskId, result, error);
        // 响应 JSON 原样回写前端（分块流式，写完删临时文件）
        sendFileChunked(ctx, HttpResponseStatus.OK, "application/json; charset=utf-8", tmp, request, true);
    }

    /**
     * 创建下载任务，两种形式：
     * 1) {"modelId","packageId"?,"token"?,"overwrite"?} — 按 model-packages.json 清单生成文件列表
     *    （packageId 缺省取 default 包；URL 用 hub.config.json 的 hfEndpoint 拼接）；
     * 2) {"targetDir","files":[{"url","path"},...],"token"?,"overwrite"?} — 显式文件列表。
     * 权重落盘 models/<targetDir>/，创建后自动开始，返回任务详情（含分段与进度）。
     */
    private void handleDownloadCreate(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        boolean overwrite = body.has("overwrite") && body.get("overwrite").isJsonPrimitive()
                && body.get("overwrite").getAsBoolean();
        String token = optString(body, "token");
        String modelId = optString(body, "modelId");
        String packageId = null;
        String targetDir;
        List<DownloadManager.FileRequest> files = new ArrayList<>();
        if (modelId != null && !modelId.isEmpty()) {
            JsonObject family = ModelPackageRegistry.findByModel(modelId);
            if (family == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                        Jsons.error("MODEL_UNKNOWN", Map.of("modelId", modelId),
                                "模型无下载清单: " + modelId), request);
                return;
            }
            packageId = optString(body, "packageId");
            JsonObject pkg = ModelPackageRegistry.resolvePackage(family, packageId);
            if (pkg == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                        Jsons.error("PACKAGE_UNKNOWN", Map.of("modelId", modelId,
                                        "packageId", String.valueOf(packageId)),
                                "下载包不存在: " + packageId), request);
                return;
            }
            // 记录解析后的包 id（body 未指定时即 default 包）
            packageId = pkg.get("id").getAsString();
            targetDir = pkg.get("targetDir").getAsString();
            String repo = pkg.get("repo").getAsString();
            String revision = pkg.get("revision").getAsString();
            for (JsonElement el : pkg.getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                files.add(new DownloadManager.FileRequest(
                        ModelPackageRegistry.buildUrl(config.hfEndpoint, repo, revision,
                                f.get("remote").getAsString()),
                        f.get("local").getAsString()));
            }
        } else {
            targetDir = optString(body, "targetDir");
            if (!body.has("files") || !body.get("files").isJsonArray()
                    || body.getAsJsonArray("files").size() == 0) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                        Jsons.error("FILES_REQUIRED", null, "files 不能为空"), request);
                return;
            }
            for (JsonElement el : body.getAsJsonArray("files")) {
                if (el.isJsonObject()) {
                    JsonObject f = el.getAsJsonObject();
                    files.add(new DownloadManager.FileRequest(optString(f, "url"), optString(f, "path")));
                }
            }
        }
        try {
            JsonObject task = downloadManager.create(targetDir, files, token, overwrite, modelId, packageId);
            sendJson(ctx, HttpResponseStatus.OK, task.toString(), request);
        } catch (Exception e) {
            if (e instanceof UserException) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
            } else {
                log.error("创建下载任务失败", e);
                sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, errorJson(e), request);
            }
        }
    }

    /** 下载任务路由：/api/downloads/<id>[/pause|/resume]，GET 详情，DELETE 取消（?purge=true 清理 .part 残留）。 */
    private void handleDownload(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method,
                                QueryStringDecoder decoder, String rest) {
        String[] parts = rest.split("/");
        String id = parts[0];
        String action = parts.length > 1 ? parts[1] : null;
        try {
            if (method.equals(HttpMethod.GET) && action == null) {
                sendJson(ctx, HttpResponseStatus.OK, downloadManager.get(id).toString(), request);
            } else if (method.equals(HttpMethod.POST) && "pause".equals(action)) {
                downloadManager.pause(id);
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else if (method.equals(HttpMethod.POST) && "resume".equals(action)) {
                downloadManager.resume(id);
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else if (method.equals(HttpMethod.DELETE) && action == null) {
                downloadManager.delete(id, "true".equalsIgnoreCase(firstParam(decoder, "purge")));
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("UNKNOWN_API", Map.of("path", request.uri()), "unknown api: " + request.uri()),
                        request);
            }
        } catch (UserException e) {
            HttpResponseStatus status = "DOWNLOAD_NOT_FOUND".equals(e.getCode())
                    ? HttpResponseStatus.NOT_FOUND : HttpResponseStatus.BAD_REQUEST;
            sendJson(ctx, status, errorJson(e), request);
        } catch (Exception e) {
            log.error("下载任务操作失败", e);
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, errorJson(e), request);
        }
    }

    /** 操作历史路由：路径段 <modelId>[/<taskId>[/audio]]，GET 列表/详情/音频，DELETE 单删/清空。 */
    private void handleHistory(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method,
                               String rest) throws Exception {
        String[] parts = rest.split("/");
        String modelId = parts[0];
        String taskId = parts.length > 1 ? parts[1] : null;
        boolean audio = parts.length > 2 && "audio".equals(parts[2]);
        if (method.equals(HttpMethod.GET) && taskId == null) {
            sendJson(ctx, HttpResponseStatus.OK, historyManager.list(modelId).toString(), request);
        } else if (method.equals(HttpMethod.GET) && audio) {
            Path wav = historyManager.audioPath(modelId, taskId);
            if (wav == null) {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("HISTORY_NOT_FOUND", null, "历史音频不存在"), request);
                return;
            }
            sendFileChunked(ctx, HttpResponseStatus.OK, "audio/wav", wav, request, false);
        } else if (method.equals(HttpMethod.GET)) {
            JsonObject rec = historyManager.get(modelId, taskId);
            if (rec == null) {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("HISTORY_NOT_FOUND", null, "历史记录不存在"), request);
                return;
            }
            sendJson(ctx, HttpResponseStatus.OK, rec.toString(), request);
        } else if (method.equals(HttpMethod.DELETE) && taskId != null) {
            if (historyManager.delete(modelId, taskId)) {
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("taskId", taskId)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("HISTORY_NOT_FOUND", null, "历史记录不存在"), request);
            }
        } else if (method.equals(HttpMethod.DELETE)) {
            historyManager.clear(modelId);
            sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("modelId", modelId)), request);
        } else {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Jsons.error("UNKNOWN_API", Map.of("path", request.uri()), "unknown api: " + request.uri()),
                    request);
        }
    }

    /**
     * 文件分块回写（chunked，避免整文件进堆）；deleteAfter 在写出完成后删除文件。
     * 在 eventLoop 上同步读文件，与 handleRun 的阻塞转发同款取舍（本地单用户）。
     */
    private void sendFileChunked(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType,
                                 Path file, FullHttpRequest request, boolean deleteAfter) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        head.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        head.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        if (!keepAlive) {
            head.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        ctx.write(head);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(Arrays.copyOf(buf, n))));
            }
        } catch (IOException e) {
            log.warn("文件回写中断: {}", e.getMessage());
            if (deleteAfter) {
                deleteQuietly(file);
            }
            ctx.close();
            return;
        }
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(f -> {
            if (deleteAfter) {
                deleteQuietly(file);
            }
            if (!keepAlive) {
                ctx.close();
            }
        });
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    /** 上传 WAV 原始二进制。 */
    private void handleAudioUpload(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        if (request.content().readableBytes() > AudioStore.MAX_UPLOAD_BYTES) {
            sendJson(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    Jsons.error("FILE_TOO_LARGE", null, "文件超过 50MB 上限"), request);
            return;
        }
        byte[] data = new byte[request.content().readableBytes()];
        request.content().readBytes(data);
        try {
            Map<String, Object> info = AudioStore.saveUpload(data);
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(info), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
    }

    /** 探测本地路径的 WAV 信息。 */
    private void handleAudioInfo(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        String path = optString(body, "path");
        if (path == null || path.isEmpty()) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("PATH_REQUIRED", null, "path 不能为空"), request);
            return;
        }
        try {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(AudioStore.probe(path)), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
    }

    /** 读取上传件音频。 */
    private void handleAudioFile(ChannelHandlerContext ctx, FullHttpRequest request, QueryStringDecoder decoder) throws Exception {
        List<String> ids = decoder.parameters().get("id");
        String id = (ids == null || ids.isEmpty()) ? null : ids.get(0);
        Path path = AudioStore.uploadPath(id);
        if (path == null) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Jsons.error("UPLOAD_NOT_FOUND", null, "上传件不存在或 id 非法"), request);
            return;
        }
        StaticFileHandler.sendBytes(ctx, HttpResponseStatus.OK, "audio/wav",
                Files.readAllBytes(path), HttpVersion.HTTP_1_1, request);
    }

    /** 保存音色：{"name","uploadId"} 或 {"name","path"}。 */
    private void handleVoiceSave(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        try {
            JsonObject entry = voiceLibrary.save(optString(body, "name"),
                    optString(body, "uploadId"), optString(body, "path"));
            sendJson(ctx, HttpResponseStatus.OK, entry.toString(), request);
        } catch (UserException e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error(e.getCode(), e.getParams(), e.getMessage()), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Jsons.error(e.getMessage()), request);
        }
    }

    /** 读取音色音频。 */
    private void handleVoiceAudio(ChannelHandlerContext ctx, FullHttpRequest request, String vid) throws Exception {
        Path path = voiceLibrary.voiceAudioPath(vid);
        if (path == null) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Jsons.error("VOICE_NOT_FOUND", Map.of("id", vid), "音色不存在: " + vid), request);
            return;
        }
        StaticFileHandler.sendBytes(ctx, HttpResponseStatus.OK, "audio/wav",
                Files.readAllBytes(path), HttpVersion.HTTP_1_1, request);
    }

    /** 列出目录内容：GET /api/fs/list?path=...。 */
    private void handleFsList(ChannelHandlerContext ctx, FullHttpRequest request, QueryStringDecoder decoder) {
        try {
            sendJson(ctx, HttpResponseStatus.OK,
                    fileSystemBrowser.list(firstParam(decoder, "path")).toString(), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
    }

    /** 新建文件夹：{"parent","name"}。 */
    private void handleFsMkdir(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        try {
            JsonObject entry = fileSystemBrowser.mkdir(optString(body, "parent"), optString(body, "name"));
            sendJson(ctx, HttpResponseStatus.OK, entry.toString(), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
    }

    /** 生成自签 CA + 服务器证书：{"ips"?,"hostnames"?,"validity"?,"password"?,"keysize"?,"cn"?}，重启后生效。 */
    private void handleCertGenerate(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        try {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(CertManager.generate(body)), request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Jsons.error("CERT_GENERATE_FAILED", null, "证书生成被中断"), request);
        } catch (Exception e) {
            log.error("证书生成失败", e);
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, errorJson(e), request);
        }
    }

    /** 下载证书文件：?type=ca 下载 CA 根证书，否则下载服务器密钥库。 */
    private void handleCertDownload(ChannelHandlerContext ctx, FullHttpRequest request,
                                    QueryStringDecoder decoder) throws Exception {
        String type = firstParam(decoder, "type");
        Path filePath;
        String contentType;
        if ("ca".equalsIgnoreCase(type)) {
            filePath = CertManager.caCertPath();
            contentType = "application/x-x509-ca-cert";
        } else {
            filePath = Path.of(AudioHubServer.getHttpsKeystorePath()).toAbsolutePath().normalize();
            contentType = "application/x-pkcs12";
        }
        if (!Files.isRegularFile(filePath)) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                    Jsons.error("CERT_FILE_NOT_FOUND", null, "证书文件不存在"), request);
            return;
        }
        String fileName = filePath.getFileName() == null ? "keystore.p12" : filePath.getFileName().toString();
        StaticFileHandler.sendBytes(ctx, HttpResponseStatus.OK, contentType, Files.readAllBytes(filePath),
                HttpVersion.HTTP_1_1, request, "attachment; filename=\"" + fileName + "\"");
    }

    /** 更新 HTTPS 配置：{"enabled"?,"keystorePath"?,"keystorePassword"?}，重启后生效；返回最新状态。 */
    private void handleHttpsConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Jsons.error("INVALID_JSON", null, "请求体不是合法 JSON"), request);
            return;
        }
        Boolean enabled = body.has("enabled") && body.get("enabled").isJsonPrimitive()
                ? body.get("enabled").getAsBoolean() : null;
        AudioHubServer.updateHttpsConfig(enabled, optString(body, "keystorePath"),
                optString(body, "keystorePassword"));
        sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(CertManager.status()), request);
    }

    /** 异常转错误 JSON：UserException 带 code/params，其余用 message 兜底（无 code）。 */
    private String errorJson(Exception e) {
        if (e instanceof UserException ue) {
            return Jsons.error(ue.getCode(), ue.getParams(), ue.getMessage());
        }
        return Jsons.error(e.getMessage());
    }

    private String firstParam(QueryStringDecoder decoder, String key) {
        List<String> values = decoder.parameters().get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private Map<String, Object> toJson(ModelInstance instance) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", instance.getId());
        map.put("instanceName", instance.getInstanceName());
        map.put("modelId", instance.getModelId());
        map.put("weightsPath", instance.getWeightsPath());
        map.put("port", instance.getPort());
        map.put("backend", instance.getBackend());
        map.put("device", instance.getDevice());
        map.put("executableName", instance.getExecutableName());
        map.put("status", instance.getStatus().name());
        map.put("createdAt", instance.getCreatedAt().toString());
        return map;
    }

    private String optString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String body, FullHttpRequest request) {
        StaticFileHandler.sendText(ctx, status, "application/json; charset=utf-8", body, HttpVersion.HTTP_1_1, request);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("API 处理异常", cause);
        ctx.close();
    }
}
