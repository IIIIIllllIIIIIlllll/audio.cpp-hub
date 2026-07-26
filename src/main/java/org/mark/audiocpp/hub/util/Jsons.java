package org.mark.audiocpp.hub.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/** gson 单例与响应辅助。 */
public final class Jsons {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Jsons() {}

    /** 构造 {"ok":true,"data":...} 形式的 JSON 字符串。 */
    public static String ok(Object data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("data", data);
        return GSON.toJson(map);
    }

    /** 构造 {"ok":false,"error":...} 形式的 JSON 字符串。 */
    public static String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", message);
        return GSON.toJson(map);
    }

    /** 构造 {"ok":false,"code":...,"params":{...},"error":中文兜底} 形式的 JSON 字符串。 */
    public static String error(String code, Map<String, Object> params, String zhMessage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("code", code);
        map.put("params", params == null ? Map.of() : params);
        map.put("error", zhMessage);
        return GSON.toJson(map);
    }

    /** 截取摘要，避免超长错误信息。 */
    public static String summarize(String text) {
        if (text == null) return "";
        text = text.trim();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
