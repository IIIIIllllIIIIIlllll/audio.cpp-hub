package org.mark.audiocpp.hub.netty;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.mark.audiocpp.hub.audio.AudioStore;
import org.mark.audiocpp.hub.audio.VoiceLibrary;
import org.mark.audiocpp.hub.config.ExecutableRegistry;
import org.mark.audiocpp.hub.config.ProfileRegistry;
import org.mark.audiocpp.hub.fs.FileSystemBrowser;
import org.mark.audiocpp.hub.instance.InstanceManager;
import org.mark.audiocpp.hub.instance.ModelInstance;
import org.mark.audiocpp.hub.proxy.SpeechForwarder;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.ModelRegistry;
import org.mark.audiocpp.hub.util.UserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 路由。处理不了的 GET 请求透传给 StaticFileHandler。
 */
public class ApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);

    private final InstanceManager instanceManager;
    private final ExecutableRegistry executableRegistry;
    private final ProfileRegistry profileRegistry;
    private final SpeechForwarder speechForwarder = new SpeechForwarder();
    private final VoiceLibrary voiceLibrary = new VoiceLibrary();
    private final FileSystemBrowser fileSystemBrowser = new FileSystemBrowser();

    public ApiHandler(InstanceManager instanceManager, ExecutableRegistry executableRegistry,
                      ProfileRegistry profileRegistry) {
        this.instanceManager = instanceManager;
        this.executableRegistry = executableRegistry;
        this.profileRegistry = profileRegistry;
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
        } else if (method.equals(HttpMethod.DELETE) && path.startsWith("/api/executables/")) {
            String id = path.substring("/api/executables/".length());
            if (executableRegistry.delete(id)) {
                sendJson(ctx, HttpResponseStatus.OK, Jsons.ok(Map.of("id", id)), request);
            } else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                        Jsons.error("EXEC_NOT_FOUND", Map.of("id", id), "可执行文件不存在: " + id), request);
            }
        } else if (method.equals(HttpMethod.GET) && path.equals("/api/profiles")) {
            sendJson(ctx, HttpResponseStatus.OK, Jsons.GSON.toJson(profileRegistry.list()), request);
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
        try {
            ModelInstance instance = instanceManager.start(modelId, weightsPath, backend, device, port,
                    threads, executablePath, executableName, serverTask);
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

    /** 添加可执行文件：{"name","path","note"?}。 */
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
                    optString(body, "path"), optString(body, "note"));
            sendJson(ctx, HttpResponseStatus.OK, entry.toString(), request);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorJson(e), request);
        }
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
            String result = speechForwarder.forward(instance.getPort(), body);
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
