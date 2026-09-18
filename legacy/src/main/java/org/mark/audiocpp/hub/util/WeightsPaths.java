package org.mark.audiocpp.hub.util;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** 模型权重路径解析：相对路径相对 hub 工作目录，存在性判断兼容目录与单个 .gguf 文件。 */
public final class WeightsPaths {

    private WeightsPaths() {}

    /** 解析为绝对路径：相对路径相对 hub 工作目录，并 normalize。 */
    public static Path resolve(String raw) {
        Path path = Path.of(raw.trim());
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(path).normalize();
        }
        return path;
    }

    /** 权重是否存在：目录（safetensors / 含 model.gguf）或单个 .gguf 文件；非法路径字符视为不存在。 */
    public static boolean exists(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        final Path path;
        try {
            path = resolve(raw);
        } catch (InvalidPathException e) {
            return false;
        }
        Path fileName = path.getFileName();
        boolean isGguf = fileName != null && fileName.toString().toLowerCase().endsWith(".gguf");
        return Files.isDirectory(path) || (Files.isRegularFile(path) && isGguf);
    }
}
