package org.mark.audiocpp.hub.instance;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 事件缓冲：内存环形队列，保留最近 20 条（新到旧）。
 */
public class EventLog {

    private static final int CAPACITY = 20;

    private final Deque<JsonObject> events = new ArrayDeque<>();

    public synchronized void add(String level, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("time", Instant.now().toString());
        event.addProperty("level", level);
        event.addProperty("message", message);
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
