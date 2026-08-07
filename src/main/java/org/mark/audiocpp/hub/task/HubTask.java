package org.mark.audiocpp.hub.task;

import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.instance.ModelInstance;

import java.nio.file.Path;
import java.util.concurrent.Future;

/**
 * 一个异步推理任务（POST /api/tasks 创建）。状态由 TaskManager 落盘
 * data/tasks/<id>.task.json，hub 重启后回放（进行中标记为 CANCELLED）。
 * TTS 任务的 id 同时是历史记录 id（结果音频在 data/history/<modelId>/<id>.wav）；
 * 非 TTS 任务的结果 JSON 落盘 data/tasks/<id>.result.json。
 */
public class HubTask {

    public enum Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public String id;
    public String instanceId;
    public String instanceName;
    public String modelId;
    public String category;
    public volatile Status status = Status.QUEUED;
    public long createdAt = System.currentTimeMillis();
    public volatile Long startedAt;
    public volatile Long finishedAt;
    public volatile String error;
    /** 文本预览（request.text 截断 100 字；请求无文本时回填结果 JSON 顶层 "text"，如 ASR 转写文本），侧栏记录展示用；无文本的任务为 null（不序列化）。 */
    public String text;
    /** TTS 成功时的结果元数据（durationSec/size/sampleRate 等），与历史记录中的 result 一致。 */
    public volatile JsonObject result;

    /* ---------- 以下不参与 Gson 序列化 ---------- */
    /** 提交时的实例引用（停止后执行会在转发时连接失败，走 FAILED 分支）。 */
    public transient ModelInstance instance;
    /** 提交时的原始 body {"request":{...}}，转发与历史记录要用。 */
    public transient JsonObject requestJson;
    /** 非 TTS 结果文件路径（data/tasks/<id>.result.json）。 */
    public transient Path resultPath;
    /** worker Future，取消 RUNNING 任务时中断用。 */
    public transient Future<?> future;

    public boolean active() {
        return status == Status.QUEUED || status == Status.RUNNING;
    }
}
