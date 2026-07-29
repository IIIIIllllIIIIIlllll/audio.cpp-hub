package org.mark.audiocpp.hub.instance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 用 gson 生成 audiocpp_server 的 server.json。model id 即实例服务名（/v1/* 路由键）。 */
public final class ServerConfigWriter {

    private ServerConfigWriter() {}

    public static void write(Path path, String host, int port, String backend, Integer device, Integer threads,
                             String instanceName, String modelId, String weightsPath, String task) throws IOException {
        JsonObject model = new JsonObject();
        model.addProperty("id", instanceName);
        model.addProperty("family", modelId);
        model.addProperty("path", weightsPath);
        model.addProperty("task", task);
        model.addProperty("mode", "offline");

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
