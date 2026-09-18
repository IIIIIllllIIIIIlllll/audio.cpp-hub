package org.mark.audiocpp.hub.download;

import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.UserException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 下载任务数据模型。
 * 由 Gson 直接序列化落盘到 data/downloads/&lt;id&gt;/task.json（token 明文存储，
 * 与 hub.config.json 存密钥库密码同一级别，仅供本机/局域网使用；API 输出会剔除 token）。
 * 进度字段用 volatile：同一分段同一时刻只有一个 worker 线程写入，管理线程读取。
 */
public class DownloadTask {

    /** 目标目录名（models/ 下的子目录），允许点号以兼容 "ACE-Step1.5-GGUF" 这类命名 */
    public static final Pattern TARGET_DIR = Pattern.compile("[a-zA-Z0-9._-]{1,64}");

    public enum Status { PENDING, RUNNING, PAUSED, DONE, FAILED }

    /** 下载分段：[start, end] 闭区间；end=-1 表示远端未给大小的整流下载。 */
    public static class Segment {
        public long start;
        public long end;
        public volatile long done;
    }

    /** 单个待下载文件。 */
    public static class FileEntry {
        /** 相对目标目录的路径（统一用 / 分隔） */
        public String path;
        public String url;
        /** 字节数，-1 表示未知 */
        public long size = -1;
        public boolean supportsRange;
        /** 改名落盘完成后置 true，续传时跳过 */
        public boolean completed;
        public List<Segment> segments = new ArrayList<>();
    }

    public String id;
    public String targetDir;
    /** 来源模型与下载包（显式 files 创建的任务为 null），供前端关联模型与回填权重路径 */
    public String modelId;
    public String packageId;
    public Status status = Status.PENDING;
    public String error;
    /** HuggingFace token（gated 仓库用），可空 */
    public String token;
    public long createdAt;
    public long updatedAt;
    public List<FileEntry> files = new ArrayList<>();

    // ---- 以下为运行态字段，不落盘 ----
    /** 请求暂停（含 JVM 关闭），worker 在块边界响应 */
    public transient volatile boolean pauseRequested;
    /** 请求取消（delete），worker 在块边界响应 */
    public transient volatile boolean cancelRequested;
    /** 运行代次：resume/删除时递增，旧 runner/worker 据此自杀，避免新旧两批线程并发写同一分段 */
    public transient volatile int runGeneration;
    public transient volatile long lastPersistAt;
    public transient volatile long speedBps;
    public transient long speedSampleAt;
    public transient long speedSampleBytes = -1;

    /** 校验目标目录名；非法抛 UserException。纯 "."/".." 这类无字母数字的名字一并拒绝。 */
    public static String validateTargetDir(String targetDir) {
        if (targetDir == null || !TARGET_DIR.matcher(targetDir).matches()
                || targetDir.chars().noneMatch(Character::isLetterOrDigit)) {
            throw new UserException("INVALID_TARGET_DIR", Map.of("targetDir", String.valueOf(targetDir)),
                    "非法目标目录名: " + targetDir);
        }
        return targetDir;
    }

    /** 校验并规范化文件相对路径：防路径穿越，统一为 / 分隔。非法抛 UserException。 */
    public static String validateFilePath(String raw) {
        String p = raw == null ? "" : raw.trim().replace('\\', '/');
        boolean ok = !p.isEmpty() && p.length() <= 256 && !p.startsWith("/") && p.indexOf(':') < 0;
        if (ok) {
            for (String seg : p.split("/")) {
                if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) {
            throw new UserException("INVALID_FILE_PATH", Map.of("path", String.valueOf(raw)),
                    "非法文件路径: " + raw);
        }
        return p;
    }

    /** 校验下载地址（仅 http/https）。非法抛 UserException。 */
    public static String validateUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new UserException("INVALID_URL", Map.of("url", String.valueOf(url)),
                    "非法下载地址: " + url);
        }
        return url;
    }

    /** 已知大小文件的总字节数（未知大小的文件不计）。 */
    public long totalBytes() {
        long total = 0;
        for (FileEntry f : files) {
            if (f.size > 0) {
                total += f.size;
            }
        }
        return total;
    }

    /** 已下载字节数（各分段 done 求和）。 */
    public long downloadedBytes() {
        long total = 0;
        for (FileEntry f : files) {
            for (Segment s : f.segments) {
                total += s.done;
            }
        }
        return total;
    }

    public int completedFiles() {
        int n = 0;
        for (FileEntry f : files) {
            if (f.completed) {
                n++;
            }
        }
        return n;
    }

    public String toJson() {
        return Jsons.GSON.toJson(this);
    }

    public static DownloadTask fromJson(String text) {
        return Jsons.GSON.fromJson(text, DownloadTask.class);
    }
}
