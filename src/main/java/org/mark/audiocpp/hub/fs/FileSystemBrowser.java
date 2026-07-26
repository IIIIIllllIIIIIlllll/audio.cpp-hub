package org.mark.audiocpp.hub.fs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.util.UserException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 服务器端文件系统浏览：roots / list / stat / mkdir。
 * hub 是本地单用户工具，已有功能（音频本地路径、可执行文件路径）本就接受任意本机路径，
 * 因此这里不做目录白名单限制。
 */
public class FileSystemBrowser {

    private static final DateTimeFormatter MTIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 根节点：Windows 为各盘符；其他系统为 /。始终附带用户主目录。 */
    public JsonArray roots() {
        JsonArray roots = new JsonArray();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            for (char c = 'A'; c <= 'Z'; c++) {
                Path drive = Path.of(c + ":\\");
                if (Files.isDirectory(drive)) {
                    roots.add(rootEntry(c + ":", drive.toString()));
                }
            }
        } else {
            roots.add(rootEntry("/", "/"));
        }
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty() && Files.isDirectory(Path.of(home))) {
            roots.add(rootEntry("主目录", home));
        }
        return roots;
    }

    private JsonObject rootEntry(String name, String path) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("path", path);
        return obj;
    }

    /** 列出目录内容：目录优先、按名称排序（忽略大小写）。 */
    public JsonObject list(String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new UserException("PATH_REQUIRED", "path 不能为空");
        }
        Path dir = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            throw new UserException("PATH_NOT_FOUND", Map.of("path", dir.toString()), "路径不存在: " + dir);
        }
        if (!Files.isDirectory(dir)) {
            throw new UserException("NOT_A_DIRECTORY", Map.of("path", dir.toString()), "不是目录: " + dir);
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", dir.toString());
        Path parent = dir.getParent();
        result.addProperty("parent", parent == null ? "" : parent.toString());

        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        children.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        JsonArray entries = new JsonArray();
        for (Path child : children) {
            entries.add(entry(child));
        }
        result.add("entries", entries);
        return result;
    }

    private JsonObject entry(Path path) {
        JsonObject entry = new JsonObject();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        entry.addProperty("name", name);
        entry.addProperty("path", path.toString());
        boolean isDir = Files.isDirectory(path);
        entry.addProperty("dir", isDir);
        entry.addProperty("hidden", isHidden(path));
        if (!isDir) {
            try {
                entry.addProperty("size", Files.size(path));
            } catch (IOException ignored) {
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                entry.addProperty("ext", name.substring(dot).toLowerCase());
            }
        }
        try {
            entry.addProperty("mtime", MTIME_FMT.format(Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException ignored) {
        }
        return entry;
    }

    /** 探测单个路径：exists/dir/name/size/mtime，不存在的路径也返回 200（exists=false）。 */
    public JsonObject stat(String rawPath) {
        JsonObject result = new JsonObject();
        if (rawPath == null || rawPath.trim().isEmpty()) {
            result.addProperty("exists", false);
            return result;
        }
        Path path = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        result.addProperty("path", path.toString());
        boolean exists = Files.exists(path);
        result.addProperty("exists", exists);
        if (exists) {
            JsonObject entry = entry(path);
            for (String key : new String[]{"name", "dir", "size", "ext", "mtime", "hidden"}) {
                if (entry.has(key)) {
                    result.add(key, entry.get(key));
                }
            }
        }
        return result;
    }

    /** 在 parent 下新建文件夹，返回新目录条目。 */
    public JsonObject mkdir(String parentRaw, String name) throws IOException {
        if (parentRaw == null || parentRaw.trim().isEmpty()) {
            throw new UserException("PARENT_REQUIRED", "parent 不能为空");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new UserException("DIR_NAME_REQUIRED", "文件夹名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.matches(".*[\\\\/:*?\"<>|].*") || trimmed.equals(".") || trimmed.equals("..")) {
            throw new UserException("DIR_NAME_INVALID", Map.of("name", trimmed), "文件夹名称含非法字符: " + trimmed);
        }
        Path parent = Path.of(parentRaw.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(parent)) {
            throw new UserException("PARENT_NOT_FOUND", Map.of("path", parent.toString()), "父目录不存在: " + parent);
        }
        Path dir = parent.resolve(trimmed);
        if (Files.exists(dir)) {
            throw new UserException("ALREADY_EXISTS", Map.of("name", trimmed), "已存在同名文件或文件夹: " + trimmed);
        }
        Files.createDirectory(dir);
        return entry(dir);
    }

    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            return name.startsWith(".");
        }
    }
}
