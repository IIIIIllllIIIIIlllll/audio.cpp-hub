package org.mark.audiocpp.hub.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.audiocpp.hub.instance.ModelInstance;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.UserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 操作历史：按 modelId 隔离的任务记录与结果音频缓存。
 * <ul>
 *   <li>布局：data/history/&lt;modelId&gt;/index.jsonl（一行一条记录，只追加）+ &lt;taskId&gt;.wav
 *       + &lt;taskId&gt;.&lt;快照名&gt;.wav 参考音频快照 + groups.json 分组；</li>
 *   <li>内存索引启动时回放 index.jsonl 重建，新增记录只需一次追加写，无整文件 JSON 重写；</li>
 *   <li>淘汰：每模型数量/容量双上限（容量计入结果 wav 与参考音频快照字节），超出从最旧开始删；</li>
 *   <li>记录结构：{taskId,time,instanceName,category,ok,text,language,voice,options?,result?,error?,
 *       refs?,refBytes?,groupId?}，refs 为 {快照名:原始文件名}，groupId 为手动分组。</li>
 * </ul>
 */
public class HistoryManager {

    private static final Logger log = LoggerFactory.getLogger(HistoryManager.class);

    private static final Path ROOT = Path.of("data", "history");
    private static final String INDEX_FILE = "index.jsonl";
    private static final String GROUPS_FILE = "groups.json";
    /** modelId 含下划线（如 index_tts2），故比 AudioStore.SAFE_ID 多放行一个 '_'。 */
    private static final Pattern SAFE_KEY = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    /** 参考音频快照名：ref（voice_ref）/ emo（情感参考 audio）/ spk0..spk99（voice_samples 逐项）。 */
    private static final Pattern REF_NAME = Pattern.compile("ref|emo|spk\\d{1,2}");

    private static final int MAX_RECORDS_PER_MODEL = 50;
    private static final long MAX_BYTES_PER_MODEL = 500L * 1024 * 1024;
    /** 单个参考音频快照大小上限。 */
    private static final long MAX_REF_BYTES = 50L * 1024 * 1024;
    /** 列表接口的文本截断长度（完整文本走单条详情接口）。 */
    private static final int LIST_TEXT_LIMIT = 100;
    /** 分组名称长度上限。 */
    private static final int MAX_GROUP_NAME = 50;

    /** modelId → 记录队列（队首最旧）。 */
    private final Map<String, Deque<JsonObject>> index = new HashMap<>();
    private final Map<String, Long> modelBytes = new HashMap<>();

    public HistoryManager() {
        loadAll();
    }

    /* ---------- 记录 ---------- */

    /**
     * 记录一条 TTS 任务：归一化请求结构（text/language/voice/options）+ 结果元数据。
     * result 非空表示拿到音频；error 非空表示失败（ok=false）。落盘失败只记日志，不影响主流程。
     */
    public synchronized void recordTts(ModelInstance instance, JsonObject frontendBody, String taskId,
                                       JsonObject result, String error) {
        String modelId = instance.getModelId();
        if (!SAFE_KEY.matcher(modelId).matches() || !SAFE_KEY.matcher(taskId).matches()) {
            log.warn("历史记录的 modelId/taskId 非法，跳过: {}/{}", modelId, taskId);
            return;
        }
        JsonObject request = frontendBody.has("request") && frontendBody.get("request").isJsonObject()
                ? frontendBody.getAsJsonObject("request") : new JsonObject();
        JsonObject rec = new JsonObject();
        rec.addProperty("taskId", taskId);
        rec.addProperty("time", System.currentTimeMillis());
        rec.addProperty("instanceName", instance.getInstanceName());
        rec.addProperty("category", "tts");
        rec.addProperty("ok", error == null);
        rec.addProperty("text", optString(request, "text"));
        rec.addProperty("language", optString(request, "language"));
        rec.add("voice", normalizeVoice(request));
        // qwen3_tts_voicedesign 的 seed 在请求顶层，并入 options 供前端「载入」还原
        JsonObject options = request.has("options") && request.get("options").isJsonObject()
                ? request.getAsJsonObject("options") : new JsonObject();
        if (request.has("seed") && request.get("seed").isJsonPrimitive()) {
            options.add("seed", request.get("seed"));
        }
        if (options.size() > 0) {
            rec.add("options", options);
        }
        if (result != null) {
            rec.add("result", result);
        }
        if (error != null) {
            rec.addProperty("error", Jsons.summarize(error));
        }
        try {
            Path dir = dirOf(modelId);
            Files.createDirectories(dir);
            snapshotRefAudios(dir, taskId, request, rec);
            Files.writeString(dir.resolve(INDEX_FILE), Jsons.GSON.toJson(rec) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            index.computeIfAbsent(modelId, k -> new ArrayDeque<>()).add(rec);
            modelBytes.merge(modelId, resultSize(rec), Long::sum);
            evict(modelId);
        } catch (IOException e) {
            log.warn("历史记录落盘失败: {}", e.getMessage());
        }
    }

    /** 归一化声音来源：voice_ref / speaker(+instruct) / instruct / default，另附情感参考 audio（若有）。 */
    private static JsonObject normalizeVoice(JsonObject request) {
        JsonObject voice = new JsonObject();
        if (hasText(request, "voice_ref")) {
            voice.addProperty("kind", "voice_ref");
            voice.addProperty("voiceRef", request.get("voice_ref").getAsString());
            if (hasText(request, "reference_text")) {
                voice.addProperty("referenceText", request.get("reference_text").getAsString());
            }
        } else if (hasText(request, "speaker")) {
            voice.addProperty("kind", "speaker");
            voice.addProperty("speaker", request.get("speaker").getAsString());
            if (hasText(request, "instruct")) {
                voice.addProperty("instruct", request.get("instruct").getAsString());
            }
        } else if (hasText(request, "instruct")) {
            voice.addProperty("kind", "instruct");
            voice.addProperty("instruct", request.get("instruct").getAsString());
        } else {
            voice.addProperty("kind", "default");
        }
        if (hasText(request, "audio")) {
            voice.addProperty("audio", request.get("audio").getAsString());
        }
        return voice;
    }

    /**
     * 复制请求里的参考音频为快照文件 &lt;taskId&gt;.&lt;快照名&gt;.wav：
     * voice_ref→ref、顶层 audio（情感参考）→emo、options.voice_samples（逗号拼接）逐项→spk0/spk1/…。
     * 成功的在 rec.refs 记录 {快照名:原始文件名} 并汇总 refBytes；源缺失/超限/IO 失败只记日志跳过，不阻断记录。
     */
    private static void snapshotRefAudios(Path dir, String taskId, JsonObject request, JsonObject rec) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (hasText(request, "voice_ref")) {
            sources.put("ref", request.get("voice_ref").getAsString());
        }
        if (hasText(request, "audio")) {
            sources.put("emo", request.get("audio").getAsString());
        }
        JsonObject options = request.has("options") && request.get("options").isJsonObject()
                ? request.getAsJsonObject("options") : null;
        if (options != null && hasText(options, "voice_samples")) {
            int i = 0;
            for (String p : options.get("voice_samples").getAsString().split(",")) {
                p = p.trim();
                if (!p.isEmpty()) {
                    sources.put("spk" + (i++), p);
                }
            }
        }
        if (sources.isEmpty()) {
            return;
        }
        JsonObject refs = new JsonObject();
        long total = 0;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String value = e.getValue();
            Path src;
            try {
                src = Path.of(value);
            } catch (Exception ex) {
                log.warn("参考音频路径非法，跳过快照: {}", value);
                continue;
            }
            try {
                if (!Files.isRegularFile(src) || Files.size(src) > MAX_REF_BYTES) {
                    log.warn("参考音频不存在或超过 50MB，跳过快照: {}", src);
                    continue;
                }
                Files.copy(src, dir.resolve(taskId + "." + e.getKey() + ".wav"),
                        StandardCopyOption.REPLACE_EXISTING);
                refs.addProperty(e.getKey(), src.getFileName().toString());
                total += Files.size(src);
            } catch (IOException ex) {
                log.warn("参考音频快照失败: {} ({})", src, ex.getMessage());
            }
        }
        if (refs.size() > 0) {
            rec.add("refs", refs);
            rec.addProperty("refBytes", total);
        }
    }

    /* ---------- 查询 ---------- */

    /** 列表（新→旧），只含列表展示的简要字段：taskId/time/instanceName/ok/text/error/result{durationSec,size}。 */
    public synchronized JsonArray list(String modelId) {
        JsonArray array = new JsonArray();
        Deque<JsonObject> records = index.get(modelId);
        if (records == null) {
            return array;
        }
        Iterator<JsonObject> it = records.descendingIterator();
        while (it.hasNext()) {
            JsonObject rec = it.next();
            JsonObject item = new JsonObject();
            item.add("taskId", rec.get("taskId"));
            item.add("time", rec.get("time"));
            item.add("instanceName", rec.get("instanceName"));
            item.add("ok", rec.get("ok"));
            // 文本截断：列表只给概要，完整文本在单条详情里
            if (rec.has("text") && rec.get("text").isJsonPrimitive()) {
                String text = rec.get("text").getAsString();
                if (text.length() > LIST_TEXT_LIMIT) {
                    item.addProperty("text", text.substring(0, LIST_TEXT_LIMIT) + "…");
                    item.addProperty("textTruncated", true);
                } else {
                    item.addProperty("text", text);
                }
            } else {
                item.add("text", rec.get("text"));
            }
            if (rec.has("groupId")) {
                item.add("groupId", rec.get("groupId"));
            }
            if (rec.has("error")) {
                item.add("error", rec.get("error"));
            }
            if (rec.has("result") && rec.get("result").isJsonObject()) {
                JsonObject r = rec.getAsJsonObject("result");
                JsonObject brief = new JsonObject();
                brief.add("durationSec", r.get("durationSec"));
                brief.add("size", r.get("size"));
                item.add("result", brief);
            }
            array.add(item);
        }
        return array;
    }

    /** 单条完整记录（含 voice/options，供前端重新载入参数）；不存在返回 null。 */
    public synchronized JsonObject get(String modelId, String taskId) {
        JsonObject rec = find(modelId, taskId);
        return rec == null ? null : rec.deepCopy();
    }

    /** 定位结果音频文件；key 非法或文件不存在返回 null（防路径穿越）。 */
    public synchronized Path audioPath(String modelId, String taskId) {
        if (!SAFE_KEY.matcher(modelId).matches() || !SAFE_KEY.matcher(taskId).matches()) {
            return null;
        }
        if (find(modelId, taskId) == null) {
            return null;
        }
        Path wav = dirOf(modelId).resolve(taskId + ".wav");
        return Files.isRegularFile(wav) ? wav : null;
    }

    /** 定位参考音频快照文件（&lt;taskId&gt;.&lt;name&gt;.wav）；key/name 非法或文件不存在返回 null。 */
    public synchronized Path refAudioPath(String modelId, String taskId, String name) {
        if (!SAFE_KEY.matcher(modelId).matches() || !SAFE_KEY.matcher(taskId).matches()
                || !REF_NAME.matcher(name).matches()) {
            return null;
        }
        Path wav = dirOf(modelId).resolve(taskId + "." + name + ".wav");
        return Files.isRegularFile(wav) ? wav : null;
    }

    /* ---------- 删除 ---------- */

    /** 删除单条记录（含 wav 与参考音频快照），不存在返回 false。 */
    public synchronized boolean delete(String modelId, String taskId) {
        Deque<JsonObject> records = index.get(modelId);
        if (records == null || !SAFE_KEY.matcher(taskId).matches()) {
            return false;
        }
        boolean removed = records.removeIf(rec -> taskId.equals(rec.get("taskId").getAsString()));
        if (!removed) {
            return false;
        }
        try {
            Path dir = dirOf(modelId);
            deleteTaskFiles(dir, taskId);
            modelBytes.put(modelId, records.stream().mapToLong(HistoryManager::resultSize).sum());
            rewriteIndex(dir, records);
        } catch (IOException e) {
            log.warn("历史索引重写失败: {}", e.getMessage());
        }
        return true;
    }

    /** 清空某模型的全部历史（分组保留）。 */
    public synchronized void clear(String modelId) {
        Deque<JsonObject> records = index.remove(modelId);
        modelBytes.remove(modelId);
        if (!SAFE_KEY.matcher(modelId).matches()) {
            return;
        }
        Path dir = dirOf(modelId);
        try {
            if (records != null) {
                for (JsonObject rec : records) {
                    deleteTaskFiles(dir, rec.get("taskId").getAsString());
                }
            }
            Files.deleteIfExists(dir.resolve(INDEX_FILE));
        } catch (IOException e) {
            log.warn("清空历史失败: {}", e.getMessage());
        }
    }

    /* ---------- 分组 ---------- */

    /** 分组列表（按创建顺序）。modelId 非法返回空数组。 */
    public synchronized JsonArray listGroups(String modelId) {
        if (!SAFE_KEY.matcher(modelId).matches()) {
            return new JsonArray();
        }
        return readGroups(modelId);
    }

    /** 新建分组：名称去空白后必填且不超过 50 字，同模型内不重名；返回组对象。 */
    public synchronized JsonObject createGroup(String modelId, String name) {
        String trimmed = validateGroupName(name);
        checkModelId(modelId);
        JsonArray groups = readGroups(modelId);
        for (JsonElement el : groups) {
            if (trimmed.equals(el.getAsJsonObject().get("name").getAsString())) {
                throw new UserException("GROUP_EXISTS", Map.of("name", trimmed), "分组已存在: " + trimmed);
            }
        }
        JsonObject group = new JsonObject();
        group.addProperty("id", UUID.randomUUID().toString().substring(0, 8));
        group.addProperty("name", trimmed);
        group.addProperty("createdAt", System.currentTimeMillis());
        groups.add(group);
        writeGroups(modelId, groups);
        return group;
    }

    /** 重命名分组；组不存在返回 false。 */
    public synchronized boolean renameGroup(String modelId, String groupId, String name) {
        String trimmed = validateGroupName(name);
        if (!SAFE_KEY.matcher(modelId).matches() || !isSafeKey(groupId)) {
            return false;
        }
        JsonArray groups = readGroups(modelId);
        for (JsonElement el : groups) {
            JsonObject group = el.getAsJsonObject();
            if (trimmed.equals(group.get("name").getAsString()) && !groupId.equals(group.get("id").getAsString())) {
                throw new UserException("GROUP_EXISTS", Map.of("name", trimmed), "分组已存在: " + trimmed);
            }
        }
        for (JsonElement el : groups) {
            JsonObject group = el.getAsJsonObject();
            if (groupId.equals(group.get("id").getAsString())) {
                group.addProperty("name", trimmed);
                writeGroups(modelId, groups);
                return true;
            }
        }
        return false;
    }

    /** 删除分组：先把该模型所有记录的 groupId 字段移除（内存 + 索引落盘），再删分组条目；组不存在返回 false。 */
    public synchronized boolean deleteGroup(String modelId, String groupId) {
        if (!SAFE_KEY.matcher(modelId).matches() || !isSafeKey(groupId)) {
            return false;
        }
        JsonArray groups = readGroups(modelId);
        int found = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (groupId.equals(groups.get(i).getAsJsonObject().get("id").getAsString())) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            return false;
        }
        Deque<JsonObject> records = index.get(modelId);
        if (records != null) {
            boolean changed = false;
            for (JsonObject rec : records) {
                if (rec.has("groupId") && groupId.equals(rec.get("groupId").getAsString())) {
                    rec.remove("groupId");
                    changed = true;
                }
            }
            if (changed) {
                try {
                    rewriteIndex(dirOf(modelId), records);
                } catch (IOException e) {
                    log.warn("历史索引重写失败: {}", e.getMessage());
                }
            }
        }
        groups.remove(found);
        writeGroups(modelId, groups);
        return true;
    }

    /**
     * 设置记录所属分组：groupId 为 null/空串表示移回未分组（移除记录 groupId 字段）；
     * 非空时校验组存在。记录不存在返回 false。
     */
    public synchronized boolean setRecordGroup(String modelId, String taskId, String groupId) {
        if (!SAFE_KEY.matcher(modelId).matches() || !SAFE_KEY.matcher(taskId).matches()) {
            return false;
        }
        JsonObject rec = find(modelId, taskId);
        if (rec == null) {
            return false;
        }
        if (groupId == null || groupId.isEmpty()) {
            rec.remove("groupId");
        } else {
            if (!isSafeKey(groupId) || !groupExists(modelId, groupId)) {
                throw new UserException("GROUP_NOT_FOUND", Map.of("groupId", groupId), "分组不存在: " + groupId);
            }
            rec.addProperty("groupId", groupId);
        }
        try {
            rewriteIndex(dirOf(modelId), index.get(modelId));
        } catch (IOException e) {
            log.warn("历史索引重写失败: {}", e.getMessage());
        }
        return true;
    }

    /** 分组名称校验：去空白后必填且不超过 50 字，返回去空白后的名称。 */
    private static String validateGroupName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new UserException("GROUP_NAME_REQUIRED", "分组名称不能为空");
        }
        if (trimmed.length() > MAX_GROUP_NAME) {
            throw new UserException("GROUP_NAME_TOO_LONG", Map.of("max", MAX_GROUP_NAME),
                    "分组名称不能超过 " + MAX_GROUP_NAME + " 字");
        }
        return trimmed;
    }

    private static void checkModelId(String modelId) {
        if (!SAFE_KEY.matcher(modelId).matches()) {
            throw new UserException("MODEL_ID_INVALID", Map.of("modelId", String.valueOf(modelId)),
                    "模型 ID 非法: " + modelId);
        }
    }

    private static boolean isSafeKey(String key) {
        return key != null && SAFE_KEY.matcher(key).matches();
    }

    private boolean groupExists(String modelId, String groupId) {
        for (JsonElement el : readGroups(modelId)) {
            if (groupId.equals(el.getAsJsonObject().get("id").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** 读分组文件；不存在/为空/损坏即空列表。 */
    private JsonArray readGroups(String modelId) {
        Path file = dirOf(modelId).resolve(GROUPS_FILE);
        if (!Files.isRegularFile(file)) {
            return new JsonArray();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return new JsonArray();
            }
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            log.warn("分组文件读取失败: {} ({})", file, e.getMessage());
            return new JsonArray();
        }
    }

    /** 写分组文件（整体重写，分组数量很小）。 */
    private void writeGroups(String modelId, JsonArray groups) {
        try {
            Path dir = dirOf(modelId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(GROUPS_FILE), Jsons.GSON.toJson(groups), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("分组文件写入失败: {} ({})", modelId, e.getMessage());
            throw new UserException("GROUP_SAVE_FAILED", "分组保存失败: " + e.getMessage());
        }
    }

    /* ---------- 文件定位（转发流程用） ---------- */

    /** TTS 响应落盘的临时文件路径（确保目录已建）。 */
    public Path tempResponsePath(String modelId, String taskId) throws IOException {
        Path dir = dirOf(modelId);
        Files.createDirectories(dir);
        return dir.resolve(taskId + ".resp.tmp");
    }

    /** 结果音频的目标路径（不建目录，由提取器负责）。 */
    public Path wavPath(String modelId, String taskId) {
        return dirOf(modelId).resolve(taskId + ".wav");
    }

    /* ---------- 内部 ---------- */

    private void loadAll() {
        if (!Files.isDirectory(ROOT)) {
            return;
        }
        try (var stream = Files.list(ROOT)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String modelId = dir.getFileName().toString();
                if (!SAFE_KEY.matcher(modelId).matches()) {
                    continue;
                }
                // 上次运行残留的响应临时文件直接清扫
                try (var files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (f.getFileName().toString().endsWith(".resp.tmp")) {
                            Files.deleteIfExists(f);
                        }
                    }
                }
                Path indexFile = dir.resolve(INDEX_FILE);
                if (!Files.isRegularFile(indexFile)) {
                    continue;
                }
                Deque<JsonObject> records = new ArrayDeque<>();
                long bytes = 0;
                for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonObject rec = JsonParser.parseString(line).getAsJsonObject();
                        records.add(rec);
                        bytes += resultSize(rec);
                    } catch (Exception e) {
                        log.warn("跳过损坏的历史索引行: {} ({})", indexFile, e.getMessage());
                    }
                }
                if (!records.isEmpty()) {
                    index.put(modelId, records);
                    modelBytes.put(modelId, bytes);
                }
            }
        } catch (IOException e) {
            log.warn("加载操作历史失败: {}", e.getMessage());
        }
    }

    /** 淘汰：超出数量/容量上限时从最旧开始删（至少保留最新一条），并重写索引。 */
    private void evict(String modelId) throws IOException {
        Deque<JsonObject> records = index.get(modelId);
        if (records == null) {
            return;
        }
        long bytes = modelBytes.getOrDefault(modelId, 0L);
        boolean evicted = false;
        while (records.size() > 1 && (records.size() > MAX_RECORDS_PER_MODEL || bytes > MAX_BYTES_PER_MODEL)) {
            JsonObject head = records.poll();
            if (head == null) {
                break;
            }
            bytes -= resultSize(head);
            deleteTaskFiles(dirOf(modelId), head.get("taskId").getAsString());
            evicted = true;
        }
        if (evicted) {
            modelBytes.put(modelId, Math.max(0, bytes));
            rewriteIndex(dirOf(modelId), records);
        }
    }

    private void rewriteIndex(Path dir, Deque<JsonObject> records) throws IOException {
        Path indexFile = dir.resolve(INDEX_FILE);
        if (records.isEmpty()) {
            Files.deleteIfExists(indexFile);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonObject rec : records) {
            sb.append(Jsons.GSON.toJson(rec)).append('\n');
        }
        Files.writeString(indexFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private JsonObject find(String modelId, String taskId) {
        Deque<JsonObject> records = index.get(modelId);
        if (records == null || taskId == null) {
            return null;
        }
        for (JsonObject rec : records) {
            if (taskId.equals(rec.get("taskId").getAsString())) {
                return rec;
            }
        }
        return null;
    }

    /** 记录占用字节：结果 wav 的 result.size + 参考音频快照 refBytes。 */
    private static long resultSize(JsonObject rec) {
        long size = 0;
        if (rec.has("result") && rec.get("result").isJsonObject()
                && rec.getAsJsonObject("result").has("size")) {
            size = rec.getAsJsonObject("result").get("size").getAsLong();
        }
        if (rec.has("refBytes") && rec.get("refBytes").isJsonPrimitive()) {
            size += rec.get("refBytes").getAsLong();
        }
        return size;
    }

    /** 删除任务的全部伴随文件：结果 wav + 参考音频快照 + 可能残留的响应临时文件（文件名前缀 taskId + "."）。 */
    private static void deleteTaskFiles(Path dir, String taskId) throws IOException {
        String prefix = taskId + ".";
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(f) && f.getFileName().toString().startsWith(prefix)) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    private static Path dirOf(String modelId) {
        return ROOT.resolve(modelId);
    }

    private static boolean hasText(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() && !obj.get(key).getAsString().isEmpty();
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }
}
