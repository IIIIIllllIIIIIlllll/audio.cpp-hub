package org.mark.audiocpp.hub.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型注册表：classpath 读 models.json，gson 解析为结构化对象并缓存。
 */
public final class ModelRegistry {

    private static volatile String rawJson;
    private static volatile List<JsonObject> models;

    private ModelRegistry() {}

    /** 全部模型（JsonObject 原样持有）。 */
    public static List<JsonObject> list() {
        return load();
    }

    /** 按 id 查找，不存在返回 null。 */
    public static JsonObject findById(String id) {
        if (id == null) return null;
        for (JsonObject model : load()) {
            if (model.has("id") && id.equals(model.get("id").getAsString())) {
                return model;
            }
        }
        return null;
    }

    /** models.json 原文（/api/models 直接返回）。 */
    public static String rawJson() {
        load();
        return rawJson;
    }

    private static List<JsonObject> load() {
        List<JsonObject> cached = models;
        if (cached == null) {
            synchronized (ModelRegistry.class) {
                if (models == null) {
                    try (InputStream in = ModelRegistry.class.getResourceAsStream("/models.json")) {
                        if (in == null) throw new IllegalStateException("classpath 中找不到 models.json");
                        rawJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        JsonArray array = JsonParser.parseString(rawJson).getAsJsonArray();
                        List<JsonObject> list = new ArrayList<>();
                        for (JsonElement el : array) {
                            list.add(el.getAsJsonObject());
                        }
                        models = list;
                    } catch (IOException e) {
                        throw new IllegalStateException("读取 models.json 失败", e);
                    }
                }
                cached = models;
            }
        }
        return cached;
    }
}
