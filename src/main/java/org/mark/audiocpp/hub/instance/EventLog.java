package org.mark.audiocpp.hub.instance;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 事件缓冲：内存环形队列，保留最近 20 条（新到旧）。
 * 每条事件除原始中文 message 外，尽量带结构化 kind（事件类型键）与 args（参数表），
 * 供前端按界面语言翻译展示（见 web/i18n.js 的 evt.* 词条；旧事件/未识别类型回退原文）。
 */
public class EventLog {

    private static final int CAPACITY = 20;

    private final Deque<JsonObject> events = new ArrayDeque<>();

    /** 记录一条无结构化信息的事件（message 即展示文案）。 */
    public synchronized void add(String level, String message) {
        add(level, null, null, message);
    }

    /**
     * 记录一条结构化事件：kind 为 i18n 词条键（如 "evt.instanceReady"），
     * args 为词条占位符参数（须为 String/Number/Boolean 等可 JSON 化的标量）。
     */
    public synchronized void add(String level, String kind, JsonObject args, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("time", Instant.now().toString());
        event.addProperty("level", level);
        event.addProperty("message", message);
        if (kind != null) event.addProperty("kind", kind);
        if (args != null) event.add("args", args);
        events.addFirst(event);
        while (events.size() > CAPACITY) {
            events.removeLast();
        }
    }

    /** 新到旧返回。 */
    public synchronized List<JsonObject> list() {
        return new ArrayList<>(events);
    }
}
