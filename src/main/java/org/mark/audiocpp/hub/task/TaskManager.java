package org.mark.audiocpp.hub.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.audio.AudioStore;
import org.mark.audiocpp.hub.history.HistoryAudioExtractor;
import org.mark.audiocpp.hub.history.HistoryManager;
import org.mark.audiocpp.hub.instance.ModelInstance;
import org.mark.audiocpp.hub.proxy.SpeechForwarder;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推理任务管理器：提交 → 同实例串行排队执行 → 前端轮询结果。
 * <p>
 * 每个实例一个单线程 executor（与引擎 busy 锁的串行语义一致，多实例可并行）；
 * 无执行时长上限（区别于旧同步链路的 10 分钟硬超时）。状态纯内存，hub 重启即丢；
 * 非 TTS 结果落盘 data/tasks/&lt;id&gt;.result.json（启动时清空该目录）；
 * TTS 结果复用历史链路（taskId 即历史记录 id，音频经 GET /api/history/&lt;modelId&gt;/&lt;taskId&gt;/audio 提供）。
 * <p>
 * 取消：QUEUED 直接标记（worker 执行前会跳过）；RUNNING 中断 worker 线程的 HttpClient 等待
 * （引擎侧会跑完这次推理，属已知限制）。与 ApiHandler 共享同一个 HistoryManager（内存索引唯一）。
 * 单例，由 AudioHubServer 创建并注入 ApiHandler。
 */
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private static final Path STATE_DIR = Path.of("data", "tasks");
    /** 已完成任务在内存中的保留条数，超出淘汰最旧并删除其结果文件 */
    private static final int FINISHED_KEEP = 100;

    private final HistoryManager historyManager = new HistoryManager();
    private final SpeechForwarder forwarder = new SpeechForwarder();
    /** 全部任务（插入序），由 this 守护 */
    private final Map<String, HubTask> tasks = new LinkedHashMap<>();
    /** 每实例单线程执行器，由 this 守护 */
    private final Map<String, ExecutorService> executors = new HashMap<>();

    public TaskManager() {
        // 任务状态纯内存，上次运行残留的结果文件没有意义，启动时清空
        try {
            if (Files.isDirectory(STATE_DIR)) {
                try (var stream = Files.list(STATE_DIR)) {
                    for (Path p : stream.toList()) {
                        deleteQuietly(p);
                    }
                }
            }
            Files.createDirectories(STATE_DIR);
        } catch (IOException e) {
            log.warn("任务目录清理失败: {}", Jsons.summarize(e.getMessage()));
        }
    }

    /** 与 ApiHandler 共享的历史管理器（TTS 结果/失败都进同一份内存索引与 jsonl）。 */
    public HistoryManager historyManager() {
        return historyManager;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 创建任务并入队。调用方（ApiHandler）负责实例存在/READY 校验。 */
    public synchronized HubTask submit(ModelInstance instance, JsonObject body) {
        HubTask t = new HubTask();
        t.id = UUID.randomUUID().toString().substring(0, 8);
        t.instanceId = instance.getId();
        t.instanceName = instance.getInstanceName();
        t.modelId = instance.getModelId();
        t.category = categoryOf(t.modelId);
        t.text = previewText(body);
        t.instance = instance;
        t.requestJson = body;
        tasks.put(t.id, t);
        t.future = executorFor(t.instanceId).submit(() -> execute(t));
        log.info("任务已入队: {} (实例 {}, category {})", t.id, t.instanceName, t.category);
        return t;
    }

    /**
     * 取消/删除任务：QUEUED/RUNNING → 置 CANCELLED（RUNNING 中断 worker 等待）；
     * 已结束 → 删除记录并清理结果文件。任务不存在返回 false。
     */
    public synchronized boolean cancel(String id) {
        HubTask t = tasks.get(id);
        if (t == null) {
            return false;
        }
        if (t.active()) {
            t.status = HubTask.Status.CANCELLED;
            t.finishedAt = System.currentTimeMillis();
            if (t.future != null) {
                t.future.cancel(true);
            }
            log.info("任务已取消: {}", id);
            return true;
        }
        tasks.remove(id);
        deleteQuietly(t.resultPath);
        return true;
    }

    // ------------------------------------------------------------------ 查询

    /** 列表：活跃在前（其余按创建时间倒序）；activeOnly 只留 QUEUED/RUNNING，modelId 非空时过滤。 */
    public synchronized JsonArray list(boolean activeOnly, String modelId) {
        List<HubTask> all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparingLong((HubTask t) -> t.createdAt).reversed());
        // 稳定排序：活跃任务提到前面，组内保持时间倒序
        all.sort(Comparator.comparingInt(t -> t.active() ? 0 : 1));
        JsonArray array = new JsonArray();
        for (HubTask t : all) {
            if (activeOnly && !t.active()) {
                continue;
            }
            if (modelId != null && !modelId.equals(t.modelId)) {
                continue;
            }
            array.add(toJson(t));
        }
        return array;
    }

    /** 单任务详情（含 position），不存在返回 null。 */
    public synchronized JsonObject get(String id) {
        HubTask t = tasks.get(id);
        return t == null ? null : toJson(t);
    }

    /** 非 TTS 已完成任务的结果文件路径，其余情况（TTS / 未完成 / 无文件）返回 null。 */
    public synchronized Path resultPath(String id) {
        HubTask t = tasks.get(id);
        return t != null && t.status == HubTask.Status.DONE ? t.resultPath : null;
    }

    /** 队列位置：同实例排在该任务前面的 QUEUED 任务数；非 QUEUED 状态恒为 0。 */
    private int position(HubTask task) {
        if (task.status != HubTask.Status.QUEUED) {
            return 0;
        }
        int n = 0;
        for (HubTask o : tasks.values()) {
            if (o.status == HubTask.Status.QUEUED && o.instanceId.equals(task.instanceId)
                    && o.createdAt < task.createdAt) {
                n++;
            }
        }
        return n;
    }

    private JsonObject toJson(HubTask t) {
        JsonObject o = Jsons.GSON.toJsonTree(t).getAsJsonObject();
        o.addProperty("position", position(t));
        return o;
    }

    // ------------------------------------------------------------------ 执行

    private void execute(HubTask t) {
        // 入队后被取消：直接跳过
        if (t.status != HubTask.Status.QUEUED) {
            return;
        }
        t.status = HubTask.Status.RUNNING;
        t.startedAt = System.currentTimeMillis();
        try {
            if ("tts".equals(t.category)) {
                runTts(t);
            } else {
                Path out = STATE_DIR.resolve(t.id + ".result.json");
                forwarder.forwardToFile(t.instance, t.requestJson, out);
                t.resultPath = out;
            }
            // 执行末尾可能刚被 cancel() 标记为 CANCELLED，不覆盖
            if (t.status == HubTask.Status.RUNNING) {
                t.status = HubTask.Status.DONE;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            t.status = HubTask.Status.CANCELLED;
        } catch (Exception e) {
            log.warn("任务执行失败: {}: {}", t.id, e.getMessage());
            t.status = HubTask.Status.FAILED;
            t.error = e.getMessage();
            if ("tts".equals(t.category)) {
                // 失败记录进历史，与旧同步链路行为一致
                historyManager.recordTts(t.instance, t.requestJson, t.id, null, e.getMessage());
            }
        } finally {
            if (t.finishedAt == null) {
                t.finishedAt = System.currentTimeMillis();
            }
            evictFinished();
        }
    }

    /**
     * TTS 执行：响应落盘临时文件 → 流式提取 audio 写成 wav → 解析 WAV 头取元数据 → 记历史。
     * 与 ApiHandler.handleRunTts 的旧同步链路同构；error 非空视为失败（抛出让 execute 走 FAILED）。
     */
    private void runTts(HubTask t) throws IOException, InterruptedException {
        Path tmp;
        try {
            tmp = historyManager.tempResponsePath(t.modelId, t.id);
        } catch (IOException e) {
            throw new IOException("历史目录不可用: " + e.getMessage(), e);
        }
        try {
            forwarder.forwardToFile(t.instance, t.requestJson, tmp);
            JsonObject result = null;
            String error = null;
            Path wav = historyManager.wavPath(t.modelId, t.id);
            try {
                HistoryAudioExtractor.Result extracted = HistoryAudioExtractor.extract(tmp, wav);
                if (extracted.audioFound()) {
                    AudioStore.WavInfo info = AudioStore.parseWav(wav);
                    result = new JsonObject();
                    result.addProperty("file", t.id + ".wav");
                    result.addProperty("size", Files.size(wav));
                    result.addProperty("durationSec", Math.round(info.durationSec * 1000.0) / 1000.0);
                    result.addProperty("sampleRate", info.sampleRate);
                    result.addProperty("channels", info.channels);
                } else {
                    error = "响应中未找到音频数据";
                }
            } catch (Exception e) {
                error = "结果音频提取失败: " + Jsons.summarize(e.getMessage());
                deleteQuietly(wav);
            }
            historyManager.recordTts(t.instance, t.requestJson, t.id, result, error);
            if (error != null) {
                throw new IOException(error);
            }
            t.result = result;
        } finally {
            deleteQuietly(tmp);
        }
    }

    // ------------------------------------------------------------------ 内部

    private static String categoryOf(String modelId) {
        JsonObject model = ModelRegistry.findById(modelId);
        if (model != null && model.has("category")) {
            return model.get("category").getAsString();
        }
        return "other";
    }

    /** 文本预览：request.text 截断 100 字（与历史列表口径一致），无文本返回 null。 */
    private static String previewText(JsonObject body) {
        if (body == null || !body.has("request") || !body.get("request").isJsonObject()) {
            return null;
        }
        JsonObject req = body.getAsJsonObject("request");
        if (!req.has("text") || !req.get("text").isJsonPrimitive()) {
            return null;
        }
        String text = req.get("text").getAsString();
        return text.length() > 100 ? text.substring(0, 100) + "…" : text;
    }

    private ExecutorService executorFor(String instanceId) {
        return executors.computeIfAbsent(instanceId, id -> Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("task-" + id);
            return thread;
        }));
    }

    /** 已完成任务超出保留上限时淘汰最旧（连带删除结果文件）。 */
    private void evictFinished() {
        synchronized (this) {
            List<HubTask> finished = new ArrayList<>();
            for (HubTask t : tasks.values()) {
                if (!t.active()) {
                    finished.add(t);
                }
            }
            if (finished.size() <= FINISHED_KEEP) {
                return;
            }
            finished.sort(Comparator.comparingLong(t -> t.createdAt));
            for (int i = 0; i < finished.size() - FINISHED_KEEP; i++) {
                HubTask t = finished.get(i);
                tasks.remove(t.id);
                deleteQuietly(t.resultPath);
            }
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("文件删除失败: {}: {}", p, Jsons.summarize(e.getMessage()));
        }
    }

    /** 程序退出时中断全部 worker。 */
    public synchronized void shutdown() {
        for (ExecutorService e : executors.values()) {
            e.shutdownNow();
        }
    }
}
