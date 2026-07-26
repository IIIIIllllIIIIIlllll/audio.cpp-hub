package org.mark.audiocpp.hub.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.audiocpp.hub.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实例启动配置（Profile）注册表：持久化到工作目录下 data/profiles.json。
 * 条目：{id, name, modelId, weightsPath, backend, device?, port?, threads?, executableId?, createdAt, updatedAt}；
 * 可选字段缺省不写入。文件不存在/为空即为空列表。
 */
public class ProfileRegistry {

    private final Path file = Path.of("data", "profiles.json");

    /** 全部条目。 */
    public synchronized List<JsonObject> list() throws IOException {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement el : readFile()) {
            result.add(el.getAsJsonObject());
        }
        return result;
    }

    /** 新增配置。字段校验在调用方（ApiHandler）完成，这里只负责落盘。 */
    public synchronized JsonObject add(JsonObject fields) throws IOException {
        JsonObject entry = fields.deepCopy();
        entry.addProperty("id", UUID.randomUUID().toString().substring(0, 8));
        String now = Instant.now().toString();
        entry.addProperty("createdAt", now);
        entry.addProperty("updatedAt", now);
        JsonArray array = readFile();
        array.add(entry);
        writeFile(array);
        return entry;
    }

    /** 按 id 更新配置：覆盖字段并刷新 updatedAt（id/createdAt 保留）；找不到返回 null。 */
    public synchronized JsonObject update(String id, JsonObject fields) throws IOException {
        JsonArray array = readFile();
        for (int i = 0; i < array.size(); i++) {
            JsonObject entry = array.get(i).getAsJsonObject();
            if (id != null && id.equals(entry.get("id").getAsString())) {
                String createdAt = entry.has("createdAt") ? entry.get("createdAt").getAsString() : Instant.now().toString();
                JsonObject updated = fields.deepCopy();
                updated.addProperty("id", id);
                updated.addProperty("createdAt", createdAt);
                updated.addProperty("updatedAt", Instant.now().toString());
                array.set(i, updated);
                writeFile(array);
                return updated;
            }
        }
        return null;
    }

    /** 删除配置。 */
    public synchronized boolean delete(String id) throws IOException {
        JsonArray array = readFile();
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).getAsJsonObject().get("id").getAsString().equals(id)) {
                array.remove(i);
                writeFile(array);
                return true;
            }
        }
        return false;
    }

    private JsonArray readFile() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new JsonArray();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) {
            return new JsonArray();
        }
        return JsonParser.parseString(text).getAsJsonArray();
    }

    private void writeFile(JsonArray array) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Jsons.GSON.toJson(array), StandardCharsets.UTF_8);
    }
}
