package org.mark.audiocpp.hub.instance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 用 gson 生成 audiocpp_server 的 server.json。model id 即实例服务名（/v1/* 路由键）。
 *  sessionOptions 为高级参数（引擎 session_options，字符串表），非空时写入模型条目。 */
public final class ServerConfigWriter {

    private ServerConfigWriter() {}

    public static void write(Path path, String host, int port, String backend, Integer device, Integer threads,
                             String instanceName, String engineFamily, String weightsPath, String task,
                             Map<String, String> sessionOptions) throws IOException {
        write(path, host, port, backend, device, threads, instanceName, engineFamily, weightsPath, task,
                "offline", sessionOptions);
    }

    public static void write(Path path, String host, int port, String backend, Integer device, Integer threads,
                             String instanceName, String engineFamily, String weightsPath, String task,
                             String mode, Map<String, String> sessionOptions) throws IOException {
        JsonObject model = new JsonObject();
        model.addProperty("id", instanceName);
        // engineFamily 是引擎侧 family（models.json 的 family 字段），与 hub 内部 modelId 解耦：
        // 同一 family 的多个 variant（如 index_tts2 的 v2 / v2.5）共享一个 engine family，
        // 由权重 config 的 version 字段区分。
        model.addProperty("family", engineFamily);
        model.addProperty("path", weightsPath);
        model.addProperty("task", task);
        model.addProperty("mode", mode != null ? mode : "offline");
        if (sessionOptions != null && !sessionOptions.isEmpty()) {
            JsonObject options = new JsonObject();
            for (Map.Entry<String, String> e : sessionOptions.entrySet()) {
                options.addProperty(e.getKey(), e.getValue());
            }
            model.add("session_options", options);
        }

        JsonArray models = new JsonArray();
        models.add(model);

        JsonObject root = new JsonObject();
        root.addProperty("host", host);
        root.addProperty("port", port);
        root.addProperty("backend", backend);
        if (device != null) {
            root.addProperty("device", device);
        }
        // CPU 后端下 threads 即 CPU 核心数，缺省用满全部核心；其他后端保持 1
        int effectiveThreads = threads != null ? threads
                : ("cpu".equals(backend) ? Runtime.getRuntime().availableProcessors() : 1);
        root.addProperty("threads", effectiveThreads);
        root.addProperty("lazy_load", true);
        root.add("models", models);

        Files.createDirectories(path.getParent());
        Files.writeString(path, Jsons.GSON.toJson(root), StandardCharsets.UTF_8);
    }
}
