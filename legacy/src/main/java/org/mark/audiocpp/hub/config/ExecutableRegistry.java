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
 * 条目：{id, name, path, note?, env?, createdAt}；list 输出附带 exists 布尔。
 * env 为可选的环境变量表（{变量名: 值}），拉起子进程时注入 ProcessBuilder，
 * 值中可用 ${VAR} 引用 hub 进程已有的环境变量（如 PATH=C:\...\bin;${PATH} 表示前置追加）。
 * 文件不存在/为空即为空列表，不做任何种子。
 */
public class ExecutableRegistry {

    private static final java.util.regex.Pattern ENV_KEY = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
    public synchronized JsonObject add(String name, String path, String note, JsonObject env) throws IOException {
        JsonObject entry = newEntry(name, path, note, env);
        validateAndResolve(entry);
        JsonArray array = readFile();
        array.add(entry);
        writeFile(array);
        JsonObject copy = entry.deepCopy();
        copy.addProperty("exists", true);
        return copy;
    }

    /** 更新条目字段（id/createdAt 保留）；不存在返回 null。校验规则与 add 相同。 */
    public synchronized JsonObject update(String id, String name, String path, String note, JsonObject env) throws IOException {
        JsonArray array = readFile();
        for (int i = 0; i < array.size(); i++) {
            JsonObject entry = array.get(i).getAsJsonObject();
            if (!entry.get("id").getAsString().equals(id)) {
                continue;
            }
            JsonObject updated = newEntry(name, path, note, env);
            updated.addProperty("id", entry.get("id").getAsString());
            updated.addProperty("createdAt", entry.get("createdAt").getAsString());
            validateAndResolve(updated);
            array.set(i, updated);
            writeFile(array);
            JsonObject copy = updated.deepCopy();
            copy.addProperty("exists", true);
            return copy;
        }
        return null;
    }

    /** 校验 name/path/env 并把目录路径解析为目录内的可执行文件，非法时抛 UserException。 */
    private void validateAndResolve(JsonObject entry) {
        String name = entry.has("name") && entry.get("name").isJsonPrimitive()
                ? entry.get("name").getAsString() : null;
        if (name == null || name.isEmpty()) {
            throw new UserException("NAME_REQUIRED", "名称不能为空");
        }
        String rawPath = entry.has("path") && entry.get("path").isJsonPrimitive()
                ? entry.get("path").getAsString() : null;
        if (rawPath == null || rawPath.isEmpty()) {
            throw new UserException("PATH_REQUIRED", "路径不能为空");
        }
        if (entry.has("env")) {
            for (Map.Entry<String, JsonElement> e : entry.get("env").getAsJsonObject().entrySet()) {
                if (!ENV_KEY.matcher(e.getKey()).matches()) {
                    throw new UserException("ENV_KEY_INVALID", Map.of("key", e.getKey()),
                            "环境变量名不合法: " + e.getKey());
                }
            }
        }
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

    private JsonObject newEntry(String name, String path, String note, JsonObject env) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", UUID.randomUUID().toString().substring(0, 8));
        entry.addProperty("name", name == null ? null : name.trim());
        entry.addProperty("path", path == null ? null : path.trim());
        if (note != null && !note.trim().isEmpty()) {
            entry.addProperty("note", note.trim());
        }
        if (env != null && env.size() > 0) {
            JsonObject cleaned = new JsonObject();
            for (Map.Entry<String, JsonElement> e : env.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    cleaned.addProperty(e.getKey().trim(), e.getValue().getAsString());
                }
            }
            if (cleaned.size() > 0) {
                entry.add("env", cleaned);
            }
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
