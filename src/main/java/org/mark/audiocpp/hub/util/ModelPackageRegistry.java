package org.mark.audiocpp.hub.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型下载清单注册表：classpath 读 model-packages.json（由 audio.cpp 的 model_specs 转换生成），
 * 按模型 id（= spec family）提供下载包（repo/revision/targetDir/files）查询与 resolve URL 构造。
 */
public final class ModelPackageRegistry {

    private static volatile Map<String, JsonObject> families;

    private ModelPackageRegistry() {}

    /** 按模型 id 查找下载清单（displayName/category/status/packages），不存在返回 null。 */
    public static JsonObject findByModel(String id) {
        if (id == null) return null;
        return load().get(id);
    }

    /**
     * 选择下载包：packageId 为 null 时取 default 标记的包（无标记取第一个）。
     * 找不到返回 null。
     */
    public static JsonObject resolvePackage(JsonObject family, String packageId) {
        if (family == null || !family.has("packages")) return null;
        JsonObject fallback = null;
        for (JsonElement el : family.getAsJsonArray("packages")) {
            JsonObject pkg = el.getAsJsonObject();
            if (packageId != null) {
                if (packageId.equals(pkg.get("id").getAsString())) return pkg;
            } else {
                if (fallback == null) fallback = pkg;
                if (pkg.has("default") && pkg.get("default").getAsBoolean()) return pkg;
            }
        }
        return packageId == null ? fallback : null;
    }

    /** 构造 HF resolve URL：逐段 percent 编码（空格、括号、中文等），保留 / 分隔。 */
    public static String buildUrl(String endpoint, String repo, String revision, String remote) {
        StringBuilder sb = new StringBuilder();
        sb.append(endpoint).append('/').append(repo).append("/resolve/").append(revision).append('/');
        String[] segments = remote.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static Map<String, JsonObject> load() {
        Map<String, JsonObject> cached = families;
        if (cached == null) {
            synchronized (ModelPackageRegistry.class) {
                if (families == null) {
                    try (InputStream in = ModelPackageRegistry.class.getResourceAsStream("/model-packages.json")) {
                        if (in == null) throw new IllegalStateException("classpath 中找不到 model-packages.json");
                        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        Map<String, JsonObject> map = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> e
                                : JsonParser.parseString(text).getAsJsonObject().entrySet()) {
                            map.put(e.getKey(), e.getValue().getAsJsonObject());
                        }
                        families = map;
                    } catch (IOException e) {
                        throw new IllegalStateException("读取 model-packages.json 失败", e);
                    }
                }
                cached = families;
            }
        }
        return cached;
    }
}
