package org.mark.audiocpp.hub.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.audiocpp.hub.AudioHubServer;
import org.mark.audiocpp.hub.download.DownloadTask.FileEntry;
import org.mark.audiocpp.hub.download.DownloadTask.Segment;
import org.mark.audiocpp.hub.download.DownloadTask.Status;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.UserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 下载管理器：多线程分段下载 + 断点续传 + 进度统计。
 * <p>
 * 单例，由 AudioHubServer 启动时创建并注入 ApiHandler（不能随 ApiHandler 每连接 new）。
 * 任务状态落盘 data/downloads/&lt;id&gt;/task.json（原子写，约 1s 节流 + 状态迁移时）；
 * 权重落盘 &lt;modelsDir&gt;/&lt;targetDir&gt;/，下载中的文件带 .part 后缀，完成校验后改名。
 * 分段用 HTTP Range 请求写入 FileChannel 指定偏移；崩溃恢复时按 .part 实际大小收敛各分段
 * 进度（persisted done 一定对应已写入的字节， clamp 只损失少量进度，不会写坏文件）。
 * 远端不支持 Range 或大小编号的文件退化为整流下载，中断后该文件从头重下。
 * <p>
 * 注意：body 读取依赖 TCP 层超时（JDK HttpClient 对 ofInputStream 无读超时参数），
 * 分段失败按 1s~15s 指数退避重试 6 次。
 */
public class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);

    private static final int CHUNK = 64 * 1024;
    /** 单分段最小字节数：小于该值不分段 */
    private static final long SEGMENT_MIN = 32L * 1024 * 1024;
    /** 分段失败重试次数：镜像/网络不稳定时短退避快速耗尽重试，给到 6 次、退避封顶 15s */
    private static final int MAX_RETRY = 6;
    private static final long PERSIST_INTERVAL_MS = 1000;

    /** 创建任务的文件请求项。 */
    public static class FileRequest {
        public final String url;
        public final String path;

        public FileRequest(String url, String path) {
            this.url = url;
            this.path = path;
        }
    }

    /** 控制信号：任务被暂停（含 JVM 关闭），进度已落盘，可续传。 */
    private static class PausedSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 控制信号：任务被删除或被新一轮 runner 接替，安静退出。 */
    private static class CancelledSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Path modelsDir;
    private final Path stateDir = Path.of("data", "downloads");
    private final int segmentsPerFile;
    private final Map<String, DownloadTask> tasks = new LinkedHashMap<>();
    private final HttpClient httpClient;
    private final ExecutorService pool;
    private volatile boolean shutdown;

    public DownloadManager(AudioHubServer.HubConfig config) {
        this.modelsDir = Path.of(config.modelsDir).toAbsolutePath().normalize();
        this.segmentsPerFile = Math.max(1, config.downloadSegmentsPerFile);
        this.pool = Executors.newFixedThreadPool(Math.max(1, config.downloadThreads), r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("download-segment-" + t.getName());
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        try {
            Files.createDirectories(modelsDir);
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            log.warn("下载目录创建失败: {}", Jsons.summarize(e.getMessage()));
        }
        loadAll();
    }

    // ------------------------------------------------------------------ 任务生命周期

    public synchronized JsonObject create(String targetDir, List<FileRequest> files, String token,
                                          boolean overwrite) {
        return create(targetDir, files, token, overwrite, null, null);
    }

    /**
     * 创建下载任务并立即开始：校验 → 并行 HEAD 探测大小/Range → 分段 → 磁盘空间检查 → 落盘 → 启动。
     * 用户可预期错误抛 UserException。modelId/packageId 仅作来源记录（可空）。
     */
    public synchronized JsonObject create(String targetDir, List<FileRequest> files, String token,
                                          boolean overwrite, String modelId, String packageId) {
        DownloadTask.validateTargetDir(targetDir);
        if (files == null || files.isEmpty()) {
            throw new UserException("FILES_REQUIRED", "files 不能为空");
        }
        for (DownloadTask t : tasks.values()) {
            boolean active = t.status == Status.RUNNING || t.status == Status.PAUSED
                    || t.status == Status.PENDING;
            if (t.targetDir.equals(targetDir) && active) {
                throw new UserException("DOWNLOAD_EXISTS", Map.of("targetDir", targetDir, "id", t.id),
                        "该目录已有进行中的下载任务: " + targetDir);
            }
        }
        Set<String> seen = new HashSet<>();
        List<FileEntry> entries = new ArrayList<>();
        for (FileRequest fr : files) {
            FileEntry e = new FileEntry();
            e.url = DownloadTask.validateUrl(fr.url);
            e.path = DownloadTask.validateFilePath(fr.path);
            if (!seen.add(e.path)) {
                throw new UserException("DUPLICATE_FILE", Map.of("path", e.path), "重复文件: " + e.path);
            }
            entries.add(e);
        }
        probe(entries, token);
        for (FileEntry e : entries) {
            e.segments = buildSegments(e.size, e.supportsRange);
        }
        long need = 0;
        for (FileEntry e : entries) {
            if (e.size > 0) {
                need += e.size;
            }
        }
        try {
            long usable = Files.getFileStore(modelsDir).getUsableSpace();
            if (need > 0 && (long) (need * 1.05) > usable) {
                throw new UserException("DISK_SPACE", Map.of("need", need, "usable", usable),
                        "磁盘空间不足：需要约 " + need + " 字节，可用 " + usable + " 字节");
            }
        } catch (IOException e) {
            log.warn("磁盘空间检查失败: {}", Jsons.summarize(e.getMessage()));
        }
        for (FileEntry e : entries) {
            Path finalPath = finalPath(targetDir, e.path);
            if (Files.exists(finalPath)) {
                if (!overwrite) {
                    throw new UserException("FILE_EXISTS", Map.of("path", e.path),
                            "目标文件已存在（如需重下请设置 overwrite）: " + e.path);
                }
                deleteQuietly(finalPath);
            }
            deleteQuietly(partPath(finalPath));
        }
        DownloadTask t = new DownloadTask();
        t.id = UUID.randomUUID().toString().substring(0, 8);
        t.targetDir = targetDir;
        t.modelId = modelId;
        t.packageId = packageId;
        t.token = (token == null || token.isEmpty()) ? null : token;
        t.createdAt = System.currentTimeMillis();
        t.updatedAt = t.createdAt;
        t.status = Status.RUNNING;
        t.files = entries;
        tasks.put(t.id, t);
        persistLocked(t);
        startRunner(t);
        log.info("创建下载任务: {} -> {} ({} 个文件)", t.id, targetDir, entries.size());
        return detailLocked(t);
    }

    /** 暂停任务（进行中的分段在块边界退出，进度落盘）。 */
    public synchronized void pause(String id) {
        DownloadTask t = requireTask(id);
        if (t.status != Status.RUNNING && t.status != Status.PENDING) {
            throw new UserException("DOWNLOAD_STATE", Map.of("status", t.status.name()),
                    "任务不在进行中，无法暂停: " + t.status);
        }
        t.pauseRequested = true;
        t.status = Status.PAUSED;
        persistLocked(t);
    }

    /** 继续任务（PAUSED/FAILED 均可重新排队，从各分段断点继续）。 */
    public synchronized void resume(String id) {
        DownloadTask t = requireTask(id);
        if (t.status != Status.PAUSED && t.status != Status.FAILED) {
            throw new UserException("DOWNLOAD_STATE", Map.of("status", t.status.name()),
                    "任务当前状态无法继续: " + t.status);
        }
        t.pauseRequested = false;
        t.error = null;
        t.status = Status.RUNNING;
        persistLocked(t);
        startRunner(t);
    }

    /** 取消并移除任务；purge=true 时删除残留的 .part（不动已完成改名的权重文件）。 */
    public synchronized void delete(String id, boolean purge) {
        DownloadTask t = requireTask(id);
        t.cancelRequested = true;
        t.runGeneration++;
        tasks.remove(id);
        if (purge && t.status != Status.DONE) {
            for (FileEntry f : t.files) {
                deleteWithRetry(partPath(finalPath(t.targetDir, f.path)));
            }
        }
        deleteWithRetry(stateDir.resolve(id).resolve("task.json"));
        deleteWithRetry(stateDir.resolve(id).resolve("task.json.tmp"));
        deleteWithRetry(stateDir.resolve(id));
        log.info("删除下载任务: {} (purge={})", id, purge);
    }

    /** 全部任务简要列表（新→旧），附带进度与速率。 */
    public synchronized JsonArray list() {
        JsonArray array = new JsonArray();
        List<DownloadTask> all = new ArrayList<>(tasks.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            DownloadTask t = all.get(i);
            sampleSpeed(t);
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id);
            o.addProperty("targetDir", t.targetDir);
            if (t.modelId != null) {
                o.addProperty("modelId", t.modelId);
            }
            o.addProperty("status", t.status.name());
            if (t.error != null) {
                o.addProperty("error", t.error);
            }
            o.addProperty("createdAt", t.createdAt);
            o.addProperty("updatedAt", t.updatedAt);
            o.addProperty("fileCount", t.files.size());
            o.addProperty("completedFiles", t.completedFiles());
            o.addProperty("totalBytes", t.totalBytes());
            o.addProperty("downloadedBytes", t.downloadedBytes());
            o.addProperty("percent", percent(t));
            o.addProperty("speedBps", t.speedBps);
            array.add(o);
        }
        return array;
    }

    /** 单任务详情（含 files/segments；剔除 token）。 */
    public synchronized JsonObject get(String id) {
        return detailLocked(requireTask(id));
    }

    /** JVM 退出：进行中的任务转暂停（进度落盘，下次启动自动续传），并关闭线程池。 */
    public void shutdown() {
        shutdown = true;
        synchronized (this) {
            for (DownloadTask t : tasks.values()) {
                if (t.status == Status.RUNNING || t.status == Status.PENDING) {
                    t.pauseRequested = true;
                }
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 运行循环

    /** 启动该任务的运行线程（每任务一个，分段任务进共享线程池）。 */
    private void startRunner(DownloadTask t) {
        int gen = ++t.runGeneration;
        Thread thread = new Thread(() -> runTask(t, gen), "download-task-" + t.id);
        thread.setDaemon(true);
        thread.start();
    }

    private void runTask(DownloadTask t, int gen) {
        try {
            reconcile(t);
            for (FileEntry f : t.files) {
                checkFlags(t, gen);
                if (f.completed) {
                    continue;
                }
                downloadFile(t, f, gen);
            }
            synchronized (this) {
                if (gen != t.runGeneration) {
                    return;
                }
                t.status = Status.DONE;
                t.error = null;
                persistLocked(t);
            }
            log.info("下载完成: {} -> {}", t.id, t.targetDir);
        } catch (PausedSignal e) {
            markPaused(t);
        } catch (CancelledSignal e) {
            // 任务已被删除或被新一轮 runner 接替，安静退出
        } catch (Exception e) {
            synchronized (this) {
                // 递增代次让残余分段线程尽快退出
                t.runGeneration++;
                t.status = Status.FAILED;
                t.error = Jsons.summarize(e.getMessage());
                persistLocked(t);
            }
            log.warn("下载任务失败 {}: {}", t.id, e.getMessage());
        } finally {
            t.speedBps = 0;
            t.speedSampleBytes = -1;
        }
    }

    /**
     * 崩溃恢复：按 .part 实际大小收敛各分段 done。
     * persisted done 一定对应已写入字节；clamp 只损失未落盘的少量进度。
     */
    private void reconcile(DownloadTask t) {
        for (FileEntry f : t.files) {
            if (f.completed || f.segments.isEmpty()) {
                continue;
            }
            long partSize;
            try {
                Path part = partPath(finalPath(t.targetDir, f.path));
                partSize = Files.isRegularFile(part) ? Files.size(part) : 0;
            } catch (IOException e) {
                partSize = 0;
            }
            for (Segment seg : f.segments) {
                if (seg.end < 0) {
                    seg.done = 0;
                    continue;
                }
                seg.done = Math.max(0, Math.min(seg.done, partSize - seg.start));
            }
        }
    }

    private void downloadFile(DownloadTask t, FileEntry f, int gen) throws Exception {
        Path finalPath = finalPath(t.targetDir, f.path);
        Path parent = finalPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = partPath(finalPath);
        if (f.size > 0 && Files.isRegularFile(finalPath) && Files.size(finalPath) == f.size) {
            // 上次在改名后、落盘前崩溃：直接判定完成
            f.completed = true;
            deleteQuietly(part);
            synchronized (this) {
                persistLocked(t);
            }
            return;
        }
        if (f.segments.size() == 1 && f.segments.get(0).end < 0) {
            streamFile(t, f, part, finalPath, gen);
            return;
        }
        try (FileChannel ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Segment seg : f.segments) {
                if (seg.done >= seg.end - seg.start + 1) {
                    continue;
                }
                futures.add(pool.submit(() -> {
                    downloadSegment(t, f, seg, ch, gen);
                    return null;
                }));
            }
            for (Future<?> fu : futures) {
                try {
                    fu.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledSignal();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw new IOException(String.valueOf(cause));
                }
            }
        }
        long total = 0;
        for (Segment s : f.segments) {
            total += s.done;
        }
        if (f.size > 0 && total != f.size) {
            throw new IOException("分段字节总量与预期不符: " + total + " != " + f.size);
        }
        movePart(part, finalPath);
        f.completed = true;
        synchronized (this) {
            persistLocked(t);
        }
    }

    /** 分段 worker：Range 请求 + 64KB 块写入 FileChannel 指定偏移，失败退避重试。 */
    private void downloadSegment(DownloadTask t, FileEntry f, Segment seg, FileChannel ch, int gen)
            throws Exception {
        for (int attempt = 1; ; attempt++) {
            checkFlags(t, gen);
            long pos = seg.start + seg.done;
            if (pos > seg.end) {
                return;
            }
            HttpRequest request = buildRequest(f.url, t.token, pos, seg.end);
            try {
                HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code >= 400) {
                    throw httpError(code, f.path);
                }
                // 服务器忽略 Range 返回 200：仅全文件起点可接受，否则写入位置会错
                if (code == 200 && pos != 0) {
                    throw new IOException("服务器忽略了 Range 请求: " + f.path);
                }
                try (InputStream in = resp.body()) {
                    byte[] buf = new byte[CHUNK];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        checkFlags(t, gen);
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        while (bb.hasRemaining()) {
                            pos += ch.write(bb, pos);
                        }
                        seg.done = pos - seg.start;
                        maybePersist(t);
                    }
                }
                if (pos <= seg.end) {
                    throw new IOException("响应体提前结束: " + f.path);
                }
                return;
            } catch (PausedSignal | CancelledSignal e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= MAX_RETRY) {
                    throw new IOException("分段下载失败（重试 " + MAX_RETRY + " 次）: " + f.path
                            + ": " + e.getMessage(), e);
                }
                log.debug("分段重试 {} ({}/{}): {}", f.path, attempt, MAX_RETRY, e.getMessage());
                sleepBackoff(attempt);
            }
        }
    }

    /** 整流下载（远端无大小/不支持 Range）：中断后该文件从头重下。 */
    private void streamFile(DownloadTask t, FileEntry f, Path part, Path finalPath, int gen) throws Exception {
        Segment seg = f.segments.get(0);
        for (int attempt = 1; ; attempt++) {
            checkFlags(t, gen);
            seg.done = 0;
            HttpRequest request = buildRequest(f.url, t.token, -1, -1);
            try {
                HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code >= 400) {
                    throw httpError(code, f.path);
                }
                long pos = 0;
                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buf = new byte[CHUNK];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        checkFlags(t, gen);
                        out.write(buf, 0, n);
                        pos += n;
                        seg.done = pos;
                        maybePersist(t);
                    }
                }
                if (f.size > 0 && pos != f.size) {
                    throw new IOException("文件大小不符: " + pos + " != " + f.size);
                }
                movePart(part, finalPath);
                f.completed = true;
                synchronized (this) {
                    persistLocked(t);
                }
                return;
            } catch (PausedSignal | CancelledSignal e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= MAX_RETRY) {
                    throw new IOException("整流下载失败（重试 " + MAX_RETRY + " 次）: " + f.path
                            + ": " + e.getMessage(), e);
                }
                log.debug("整流重试 {} ({}/{}): {}", f.path, attempt, MAX_RETRY, e.getMessage());
                sleepBackoff(attempt);
            }
        }
    }

    // ------------------------------------------------------------------ 内部工具

    /** 启动时回放 data/downloads/&lt;id&gt;/task.json；RUNNING/PENDING 任务自动续传。 */
    private void loadAll() {
        List<Path> dirs;
        try (var stream = Files.list(stateDir)) {
            dirs = stream.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("下载状态目录不可读: {}", Jsons.summarize(e.getMessage()));
            return;
        }
        synchronized (this) {
            for (Path dir : dirs) {
                Path taskFile = dir.resolve("task.json");
                if (!Files.isRegularFile(taskFile)) {
                    continue;
                }
                try {
                    DownloadTask t = DownloadTask.fromJson(Files.readString(taskFile, StandardCharsets.UTF_8));
                    if (t != null && t.id != null && t.targetDir != null) {
                        tasks.put(t.id, t);
                    }
                } catch (Exception e) {
                    log.warn("下载任务状态损坏，忽略 {}: {}", dir, Jsons.summarize(e.getMessage()));
                }
            }
            for (DownloadTask t : new ArrayList<>(tasks.values())) {
                if (t.status == Status.RUNNING || t.status == Status.PENDING) {
                    t.status = Status.RUNNING;
                    t.error = null;
                    log.info("恢复下载任务: {} -> {}", t.id, t.targetDir);
                    startRunner(t);
                }
            }
        }
    }

    /** 并行 HEAD 探测每个文件的大小与 Range 支持；失败抛 UserException。 */
    private void probe(List<FileEntry> entries, String token) {
        List<CompletableFuture<HttpResponse<Void>>> futures = new ArrayList<>();
        for (FileEntry e : entries) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(e.url))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (token != null && !token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
            futures.add(httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding()));
        }
        for (int i = 0; i < entries.size(); i++) {
            FileEntry e = entries.get(i);
            final HttpResponse<Void> resp;
            try {
                resp = futures.get(i).join();
            } catch (Exception ex) {
                throw new UserException("HEAD_FAILED",
                        Map.of("path", e.path, "msg", Jsons.summarize(ex.getMessage())),
                        "无法访问下载地址: " + e.path);
            }
            int code = resp.statusCode();
            if (code >= 400) {
                throw httpError(code, e.path);
            }
            e.size = resp.headers().firstValueAsLong("content-length").orElse(-1);
            e.supportsRange = resp.headers().firstValue("accept-ranges")
                    .map(v -> v.toLowerCase().contains("bytes")).orElse(false);
        }
    }

    /** 分段：n = clamp(ceil(size / 32MB), 1, segmentsPerFile)；未知大小/不支持 Range 退化为单段整流（end=-1）。 */
    private List<Segment> buildSegments(long size, boolean supportsRange) {
        List<Segment> list = new ArrayList<>();
        if (size <= 0 || !supportsRange) {
            Segment s = new Segment();
            s.start = 0;
            s.end = -1;
            list.add(s);
            return list;
        }
        int n = (int) Math.min(segmentsPerFile, Math.max(1, (size + SEGMENT_MIN - 1) / SEGMENT_MIN));
        long step = (size + n - 1) / n;
        for (int i = 0; i < n; i++) {
            Segment s = new Segment();
            s.start = i * step;
            s.end = Math.min(size - 1, (i + 1) * step - 1);
            list.add(s);
        }
        return list;
    }

    private HttpRequest buildRequest(String url, String token, long pos, long end) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (end >= 0) {
            builder.header("Range", "bytes=" + pos + "-" + end);
        }
        return builder.build();
    }

    /** 块边界检查控制标志：代次不符/取消 → CancelledSignal；暂停/关停 → PausedSignal。 */
    private void checkFlags(DownloadTask t, int gen) {
        if (gen != t.runGeneration || t.cancelRequested) {
            throw new CancelledSignal();
        }
        if (t.pauseRequested || shutdown) {
            throw new PausedSignal();
        }
    }

    private void markPaused(DownloadTask t) {
        synchronized (this) {
            if (t.status == Status.RUNNING || t.status == Status.PENDING) {
                t.status = Status.PAUSED;
                persistLocked(t);
            }
        }
    }

    private void maybePersist(DownloadTask t) {
        long now = System.currentTimeMillis();
        if (now - t.lastPersistAt < PERSIST_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - t.lastPersistAt >= PERSIST_INTERVAL_MS) {
                persistLocked(t);
            }
        }
    }

    /** 原子写 task.json（tmp + move）；调用方需持有 this 锁。 */
    private void persistLocked(DownloadTask t) {
        t.updatedAt = System.currentTimeMillis();
        t.lastPersistAt = t.updatedAt;
        Path dir = stateDir.resolve(t.id);
        Path tmp = dir.resolve("task.json.tmp");
        Path dst = dir.resolve("task.json");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, t.toJson(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            movePart(tmp, dst);
        } catch (IOException e) {
            log.warn("下载进度落盘失败 {}: {}", t.id, Jsons.summarize(e.getMessage()));
        }
    }

    /** 速率采样：每次查询时按时间窗增量估算 bytes/s；非 RUNNING 归零。 */
    private void sampleSpeed(DownloadTask t) {
        if (t.status != Status.RUNNING) {
            t.speedBps = 0;
            t.speedSampleBytes = -1;
            return;
        }
        long now = System.currentTimeMillis();
        long bytes = t.downloadedBytes();
        if (t.speedSampleBytes >= 0 && now > t.speedSampleAt) {
            t.speedBps = Math.max(0, (bytes - t.speedSampleBytes) * 1000 / (now - t.speedSampleAt));
        }
        t.speedSampleBytes = bytes;
        t.speedSampleAt = now;
    }

    private int percent(DownloadTask t) {
        long total = t.totalBytes();
        return total > 0 ? (int) (t.downloadedBytes() * 100 / total) : -1;
    }

    /** 详情 JSON：任务全字段（剔除 token）+ 进度/速率派生字段。调用方需持有 this 锁。 */
    private JsonObject detailLocked(DownloadTask t) {
        sampleSpeed(t);
        JsonObject o = JsonParser.parseString(t.toJson()).getAsJsonObject();
        o.remove("token");
        o.addProperty("fileCount", t.files.size());
        o.addProperty("completedFiles", t.completedFiles());
        o.addProperty("totalBytes", t.totalBytes());
        o.addProperty("downloadedBytes", t.downloadedBytes());
        o.addProperty("percent", percent(t));
        o.addProperty("speedBps", t.speedBps);
        return o;
    }

    private DownloadTask requireTask(String id) {
        DownloadTask t = id == null ? null : tasks.get(id);
        if (t == null) {
            throw new UserException("DOWNLOAD_NOT_FOUND", Map.of("id", String.valueOf(id)),
                    "下载任务不存在: " + id);
        }
        return t;
    }

    private Path finalPath(String targetDir, String rel) {
        return modelsDir.resolve(targetDir).resolve(rel).normalize();
    }

    private Path partPath(Path finalPath) {
        String name = finalPath.getFileName().toString();
        return finalPath.resolveSibling(name + ".part");
    }

    /** 原子改名（部分文件系统不支持 ATOMIC_MOVE 时退化为普通 move）。 */
    private void movePart(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private UserException httpError(int code, String path) {
        if (code == 401 || code == 403) {
            return new UserException("DOWNLOAD_AUTH", Map.of("path", path, "status", code),
                    "下载需要授权（gated 仓库请提供 HF token）: " + path);
        }
        if (code == 404) {
            return new UserException("REMOTE_NOT_FOUND", Map.of("path", path),
                    "远端文件不存在: " + path);
        }
        return new UserException("HEAD_FAILED", Map.of("path", path, "status", code),
                "下载地址返回 HTTP " + code + ": " + path);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(1000L << (attempt - 1), 15_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledSignal();
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    /** 删除带重试：分段线程退出与文件句柄释放有延迟（尤其 Windows）。 */
    private void deleteWithRetry(Path path) {
        for (int i = 0; i < 3; i++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("删除失败（已重试）: {}", path);
    }
}
