package org.mark.audiocpp.hub.proxy;

import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.instance.ModelInstance;
import org.mark.audiocpp.hub.util.Jsons;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 通用任务转发器：把 {"request":{...}} 包成 {"model":<实例服务名>,"request":{...}}
 * POST 到实例 /v1/tasks/run，整个响应 JSON 原样透传给前端。
 */
public class SpeechForwarder {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 转发任务请求，成功返回响应 JSON 原文；失败抛出带响应摘要的异常。 */
    public String forward(ModelInstance instance, JsonObject frontendBody) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(buildRequest(instance, frontendBody),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("audiocpp_server 返回 " + response.statusCode() + ": "
                    + Jsons.summarize(response.body()));
        }
        return response.body();
    }

    /**
     * TTS 历史专用：200 响应体流式落盘到 target（大 base64 不进堆）；
     * 非 200 读小错误体抛带摘要的异常。
     */
    public void forwardToFile(ModelInstance instance, JsonObject frontendBody, Path target)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(buildRequest(instance, frontendBody),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String errBody;
            try (InputStream in = response.body()) {
                errBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("audiocpp_server 返回 " + response.statusCode() + ": "
                    + Jsons.summarize(errBody));
        }
        try (InputStream in = response.body();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 64 * 1024)) {
            in.transferTo(out);
        }
    }

    private HttpRequest buildRequest(ModelInstance instance, JsonObject frontendBody) {
        JsonObject body = new JsonObject();
        body.addProperty("model", instance.getInstanceName());
        body.add("request", frontendBody.has("request") ? frontendBody.get("request") : new JsonObject());
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + instance.getPort() + "/v1/tasks/run"))
                // 懒加载 + 生成可能很慢，给足超时
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.GSON.toJson(body)))
                .build();
    }
}
