package org.mark.audiocpp.hub.config;

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

/**
 * 可执行文件注册表：持久化到工作目录下 executables.json。
 * 条目：{id, name, path, note?, createdAt}；list 输出附带 exists 布尔。
 * 文件不存在/为空即为空列表，不做任何种子。
 */
public class ExecutableRegistry {

    private final Path file = Path.of("executables.json");

    /** 全部条目（附带实时探测的 exists）。 */
    public synchronized List<JsonObject> list() throws IOException {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement el : readFile()) {
            JsonObject entry = el.getAsJsonObject().deepCopy();
            entry.addProperty("exists", exists(entry));
            result.add(entry);
        }
        return result;
    }

    /** 添加条目：name/path 必填；文件不存在则拒绝。Windows 下自动补 .exe 探测；
     *  输入目录路径时，在目录内（含 bin/ 子目录）自动定位 audiocpp_server 可执行文件。 */
    public synchronized JsonObject add(String name, String path, String note) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new UserException("NAME_REQUIRED", "名称不能为空");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new UserException("PATH_REQUIRED", "路径不能为空");
        }
        JsonObject entry = newEntry(name.trim(), path.trim(), note);
        Path resolved = resolvePath(entry);
        if (Files.isDirectory(resolved)) {
            Path found = findExecutableInDir(resolved);
            if (found == null) {
                throw new UserException("EXEC_NOT_FOUND_IN_DIR", Map.of("path", resolved.toString()),
                        "目录下未找到 audiocpp_server 可执行文件: " + resolved);
            }
            entry.addProperty("path", found.toString());
        } else if (!exists(entry)) {
            throw new UserException("FILE_NOT_FOUND", Map.of("path", resolved.toString()), "文件不存在: " + resolved);
        }
        JsonArray array = readFile();
        array.add(entry);
        writeFile(array);
        JsonObject copy = entry.deepCopy();
        copy.addProperty("exists", true);
        return copy;
    }

    /** 在目录内（含 bin/ 子目录）查找 audiocpp_server 可执行文件，找不到返回 null。 */
    private static Path findExecutableInDir(Path dir) {
        for (String candidate : new String[]{"audiocpp_server.exe", "audiocpp_server",
                "bin/audiocpp_server.exe", "bin/audiocpp_server"}) {
            Path path = dir.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path.normalize();
            }
        }
        return null;
    }

    /** 删除条目。 */
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

    /** 按 id 查找，不存在返回 null。 */
    public synchronized JsonObject findById(String id) throws IOException {
        if (id == null) return null;
        for (JsonElement el : readFile()) {
            JsonObject entry = el.getAsJsonObject();
            if (id.equals(entry.get("id").getAsString())) {
                return entry;
            }
        }
        return null;
    }

    /** 第一个条目（启动实例的默认值），空表返回 null。 */
    public synchronized JsonObject first() throws IOException {
        JsonArray array = readFile();
        return array.size() > 0 ? array.get(0).getAsJsonObject() : null;
    }

    /** 条目路径解析为绝对路径：相对路径相对 hub 工作目录；Windows 下补 .exe 探测。 */
    public Path resolvePath(JsonObject entry) {
        String raw = entry.get("path").getAsString();
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(path).normalize();
        }
        if (isWindows() && !raw.toLowerCase().endsWith(".exe") && !Files.exists(path)) {
            Path exe = Path.of(path + ".exe");
            if (Files.exists(exe)) {
                return exe;
            }
        }
        return path;
    }

    private boolean exists(JsonObject entry) {
        return Files.isRegularFile(resolvePath(entry));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private JsonObject newEntry(String name, String path, String note) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", UUID.randomUUID().toString().substring(0, 8));
        entry.addProperty("name", name);
        entry.addProperty("path", path);
        if (note != null && !note.trim().isEmpty()) {
            entry.addProperty("note", note.trim());
        }
        entry.addProperty("createdAt", Instant.now().toString());
        return entry;
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
        Files.writeString(file, Jsons.GSON.toJson(array), StandardCharsets.UTF_8);
    }
}
