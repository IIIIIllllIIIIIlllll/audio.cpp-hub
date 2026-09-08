package org.mark.audiocpp.hub.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.UserException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 音色库：data/voices/<vid>.wav + data/voices/index.json 登记。
 */
public class VoiceLibrary {

    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9-]{1,32}");

    private final Path dir = Path.of("data", "voices");
    private final Path indexFile = dir.resolve("index.json");

    /** 列出全部音色。 */
    public synchronized List<JsonObject> list() throws IOException {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement el : readIndex()) {
            result.add(el.getAsJsonObject());
        }
        return result;
    }

    /**
     * 保存音色：uploadId（上传件 id）与 sourcePath（绝对路径）二选一。
     * 复制文件为 data/voices/<vid>.wav 并登记；text 为音频文本内容（可空），名称库内唯一。
     */
    public synchronized JsonObject save(String name, String text, String uploadId, String sourcePath) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new UserException("VOICE_NAME_REQUIRED", "音色名称不能为空");
        }
        JsonArray index = readIndex();
        checkNameUnique(index, name, null);
        Path source;
        if (uploadId != null && !uploadId.isEmpty()) {
            source = AudioStore.uploadPath(uploadId);
            if (source == null) {
                throw new UserException("UPLOAD_NOT_FOUND", Map.of("id", uploadId), "上传件不存在: " + uploadId);
            }
        } else if (sourcePath != null && !sourcePath.isEmpty()) {
            source = Path.of(sourcePath);
            if (!Files.isRegularFile(source)) {
                throw new UserException("FILE_NOT_FOUND", Map.of("path", sourcePath), "文件不存在: " + sourcePath);
            }
        } else {
            throw new UserException("VOICE_SOURCE_REQUIRED", "uploadId 与 path 必须提供一个");
        }

        String vid = UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(dir);
        Path target = dir.resolve(vid + ".wav");
        Files.copy(source, target);

        // 解析副本获取音频信息
        Map<String, Object> info = AudioStore.probe(target.toAbsolutePath().toString());

        JsonObject entry = new JsonObject();
        entry.addProperty("vid", vid);
        entry.addProperty("name", name.trim());
        if (text != null && !text.isEmpty()) {
            entry.addProperty("text", text);
        }
        entry.addProperty("createdAt", Instant.now().toString());
        entry.addProperty("path", target.toAbsolutePath().toString());
        entry.addProperty("durationSec", (Double) info.get("durationSec"));
        entry.addProperty("sampleRate", (Integer) info.get("sampleRate"));
        entry.addProperty("channels", (Integer) info.get("channels"));
        entry.addProperty("bitsPerSample", (Integer) info.get("bitsPerSample"));
        entry.addProperty("sizeBytes", (Long) info.get("sizeBytes"));

        index.add(entry);
        writeIndex(index);
        return entry;
    }

    /**
     * 更新音色名称与文本内容：name/text 为 null 表示不修改对应字段。
     * vid 非法或条目不存在返回 false；name 重名（排除自身）抛 UserException。
     */
    public synchronized boolean update(String vid, String name, String text) throws IOException {
        if (vid == null || !SAFE_ID.matcher(vid).matches()) {
            return false;
        }
        if (name != null && name.trim().isEmpty()) {
            throw new UserException("VOICE_NAME_REQUIRED", "音色名称不能为空");
        }
        JsonArray index = readIndex();
        JsonObject entry = null;
        for (JsonElement el : index) {
            JsonObject obj = el.getAsJsonObject();
            if (vid.equals(obj.get("vid").getAsString())) {
                entry = obj;
                break;
            }
        }
        if (entry == null) {
            return false;
        }
        if (name != null) {
            checkNameUnique(index, name, vid);
            entry.addProperty("name", name.trim());
        }
        if (text != null) {
            if (text.isEmpty()) {
                entry.remove("text");
            } else {
                entry.addProperty("text", text);
            }
        }
        writeIndex(index);
        return true;
    }

    /** 检查名称库内唯一（trim 后比较，大小写敏感；excludeVid 为排除自身的条目 id，可为 null）。 */
    private void checkNameUnique(JsonArray index, String name, String excludeVid) {
        String trimmed = name.trim();
        for (JsonElement el : index) {
            JsonObject obj = el.getAsJsonObject();
            if (excludeVid != null && excludeVid.equals(obj.get("vid").getAsString())) {
                continue;
            }
            if (trimmed.equals(obj.get("name").getAsString())) {
                throw new UserException("VOICE_NAME_EXISTS", "音色名称已存在: " + trimmed);
            }
        }
    }

    /** 删除音色（文件 + 登记）。 */
    public synchronized boolean delete(String vid) throws IOException {
        if (vid == null || !SAFE_ID.matcher(vid).matches()) {
            return false;
        }
        JsonArray index = readIndex();
        boolean found = false;
        for (int i = 0; i < index.size(); i++) {
            if (vid.equals(index.get(i).getAsJsonObject().get("vid").getAsString())) {
                index.remove(i);
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }
        Files.deleteIfExists(dir.resolve(vid + ".wav"));
        writeIndex(index);
        return true;
    }

    /** 音色音频文件路径；vid 不在库中返回 null。 */
    public synchronized Path voiceAudioPath(String vid) throws IOException {
        if (vid == null || !SAFE_ID.matcher(vid).matches()) {
            return null;
        }
        for (JsonElement el : readIndex()) {
            if (vid.equals(el.getAsJsonObject().get("vid").getAsString())) {
                Path path = dir.resolve(vid + ".wav");
                return Files.isRegularFile(path) ? path : null;
            }
        }
        return null;
    }

    private JsonArray readIndex() throws IOException {
        if (!Files.isRegularFile(indexFile)) {
            return new JsonArray();
        }
        String text = Files.readString(indexFile, StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) {
            return new JsonArray();
        }
        return JsonParser.parseString(text).getAsJsonArray();
    }

    private void writeIndex(JsonArray index) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(indexFile, Jsons.GSON.toJson(index), StandardCharsets.UTF_8);
    }
}
