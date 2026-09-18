package org.mark.audiocpp.hub.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.mark.audiocpp.hub.instance.InstanceManager;
import org.mark.audiocpp.hub.instance.ModelInstance;
import org.mark.audiocpp.hub.util.Jsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /v1/* 模型路由代理，位于 HttpObjectAggregator 之前。
 * <ul>
 *   <li>非 /v1 请求原样放行（后续 aggregator + ApiHandler 处理）；</li>
 *   <li>GET /v1/models 本地聚合 READY 实例，OpenAI 格式返回；</li>
 *   <li>POST/PUT /v1/* 移除 aggregator，body 分块落盘（run/proxy-cache/），
 *       扫出顶层 "model" 后按服务名路由，ofFile 零堆拷贝转发，响应分块流式回写。</li>
 * </ul>
 * 线程模型：eventLoop 只做 chunk 拷贝与投递；文件 IO / 上游请求 / 响应读取全在共享 executor。
 * 错误体为 OpenAI 风格 {"error":{"message","type"}}。
 */
public class V1ProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(V1ProxyHandler.class);
    private static final Path CACHE_DIR = Path.of("run", "proxy-cache");
    private static final int RESPONSE_CHUNK_BYTES = 8192;
    /** 待落盘字节积压上限，超过则关闭 autoRead 做入站背压 */
    private static final long MAX_PENDING_WRITE_BYTES = 8L * 1024 * 1024;

    /** 文件 IO / 上游通信专用线程池（守护线程，随 JVM 退出） */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "v1-proxy-io");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).executor(EXECUTOR).build();

    static {
        // 重启后上次残留的临时文件已无用，直接清扫
        try {
            Files.createDirectories(CACHE_DIR);
            try (var stream = Files.list(CACHE_DIR)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".req"))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("初始化代理缓存目录失败: {}", e.getMessage());
        }
    }

    private final InstanceManager instanceManager;
    private final long maxBodyBytes;

    /* ---------- per-channel 状态（handler 实例随 pipeline 每连接一份） ---------- */
    private boolean proxying;
    private boolean modelsRequest;
    private boolean failed;
    private volatile boolean cancelled;
    private HttpRequest currentRequest;
    private Path tempFile;
    private OutputStream fileOut;
    private long receivedBytes;
    private CompletableFuture<Void> writeChain = CompletableFuture.completedFuture(null);
    private final AtomicLong pendingWriteBytes = new AtomicLong();
    private volatile InputStream activeResponseStream;

    public V1ProxyHandler(InstanceManager instanceManager, long maxBodyBytes) {
        this.instanceManager = instanceManager;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            if (!path.startsWith("/v1/")) {
                super.channelRead(ctx, msg);
                return;
            }
            startProxy(ctx, request, path);
            return;
        }
        if (msg instanceof HttpContent content) {
            if (!proxying) {
                super.channelRead(ctx, msg);
                return;
            }
            handleContent(ctx, content);
            return;
        }
        super.channelRead(ctx, msg);
    }

    /** 接管一个 /v1 请求：移除 aggregator（body 由本 handler 自行分块接收）。 */
    private void startProxy(ChannelHandlerContext ctx, HttpRequest request, String path) {
        // 重置上一请求的状态（keep-alive 下 handler 复用）
        modelsRequest = false;
        failed = false;
        receivedBytes = 0;
        writeChain = CompletableFuture.completedFuture(null);
        proxying = true;
        currentRequest = request;
        if (ctx.pipeline().get(HttpObjectAggregator.class) != null) {
            ctx.pipeline().remove(HttpObjectAggregator.class);
        }
        if (HttpUtil.is100ContinueExpected(request)) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
        }
        if (path.equals("/v1/models") && request.method().equals(HttpMethod.GET)) {
            modelsRequest = true; // drain body 后在 handleContent 里响应
            return;
        }
        if (!request.method().equals(HttpMethod.POST) && !request.method().equals(HttpMethod.PUT)) {
            failEarly(ctx, 405, "Method not allowed: " + request.method().name());
            return;
        }
        try {
            Files.createDirectories(CACHE_DIR);
            tempFile = CACHE_DIR.resolve(UUID.randomUUID().toString().substring(0, 8) + ".req");
            fileOut = Files.newOutputStream(tempFile);
        } catch (IOException e) {
            log.warn("创建代理缓存文件失败: {}", e.getMessage());
            failEarly(ctx, 500, "Proxy cache unavailable");
        }
    }

    /** 请求接收前的即时失败：标记丢弃后续 content，直接回错误。 */
    private void failEarly(ChannelHandlerContext ctx, int status, String message) {
        failed = true;
        sendError(ctx, status, message);
    }

    private void handleContent(ChannelHandlerContext ctx, HttpContent content) {
        boolean last = content instanceof LastHttpContent;
        if (modelsRequest) {
            content.release();
            if (last) {
                respondModels(ctx);
                resetState();
            }
            return;
        }
        if (failed) {
            content.release();
            return;
        }
        ByteBuf buf = content.content();
        int n = buf.readableBytes();
        receivedBytes += n;
        if (receivedBytes > maxBodyBytes) {
            content.release();
            failed = true;
            closeFileQuietly();
            deleteTempQuietly();
            sendError(ctx, 413, "Request body exceeds proxy limit of " + maxBodyBytes + " bytes");
            return;
        }
        byte[] bytes = new byte[n];
        buf.getBytes(buf.readerIndex(), bytes);
        content.release();
        pendingWriteBytes.addAndGet(n);
        if (pendingWriteBytes.get() > MAX_PENDING_WRITE_BYTES && ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(false);
        }
        writeChain = writeChain.thenRunAsync(() -> writeChunk(ctx, bytes), EXECUTOR);
        if (last) {
            writeChain.thenRunAsync(() -> finishRequest(ctx), EXECUTOR)
                    .exceptionally(e -> {
                        log.warn("代理请求处理失败: {}", e.getMessage());
                        cleanupAfterError();
                        if (!cancelled) {
                            sendError(ctx, 500, "Proxy internal error");
                        }
                        return null;
                    });
        }
    }

    private void writeChunk(ChannelHandlerContext ctx, byte[] bytes) {
        try {
            if (fileOut != null) {
                fileOut.write(bytes);
            }
        } catch (IOException e) {
            failed = true;
            log.warn("代理缓存写入失败: {}", e.getMessage());
        } finally {
            long pending = pendingWriteBytes.addAndGet(-bytes.length);
            if (pending <= MAX_PENDING_WRITE_BYTES && !ctx.channel().config().isAutoRead()) {
                ctx.channel().config().setAutoRead(true);
            }
        }
    }

    /** body 全部落盘后：扫描 model → 路由 → 转发。运行在 executor 线程。 */
    private void finishRequest(ChannelHandlerContext ctx) {
        closeFileQuietly();
        if (cancelled || failed) {
            deleteTempQuietly();
            return;
        }
        String model;
        try {
            model = RequestModelExtractor.extract(tempFile, maxBodyBytes);
        } catch (RequestModelExtractor.ProxyRequestException e) {
            sendError(ctx, e.getStatus(), e.getMessage());
            deleteTempQuietly();
            resetState();
            return;
        } catch (IOException e) {
            sendError(ctx, 500, "Failed to read cached request body");
            deleteTempQuietly();
            resetState();
            return;
        }
        if (model == null) {
            sendError(ctx, 400, "Missing required parameter: model");
            deleteTempQuietly();
            resetState();
            return;
        }
        ModelInstance instance = instanceManager.findByName(model);
        if (instance == null) {
            if (instanceManager.findAnyByName(model) != null) {
                sendError(ctx, 409, "Model is still starting: " + model);
            } else {
                sendError(ctx, 404, "Model not found: " + model);
            }
            deleteTempQuietly();
            resetState();
            return;
        }
        forward(ctx, instance);
    }

    /** ofFile 转发到实例同名路径，响应分块流式回写。运行在 executor 线程。 */
    private void forward(ChannelHandlerContext ctx, ModelInstance instance) {
        String contentType = currentRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
        try {
            java.net.http.HttpRequest upstream = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + instance.getPort() + currentRequest.uri()))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", contentType != null ? contentType : "application/json")
                    .method(currentRequest.method().name(),
                            java.net.http.HttpRequest.BodyPublishers.ofFile(tempFile))
                    .build();
            HttpResponse<InputStream> response = CLIENT.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
            // 请求体已完整发出，缓存文件使命结束
            deleteTempQuietly();
            if (cancelled) {
                response.body().close();
                resetState();
                return;
            }
            streamResponse(ctx, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteTempQuietly();
            resetState();
        } catch (Exception e) {
            log.warn("转发到实例 #{} 失败: {}", instance.getId(), e.getMessage());
            deleteTempQuietly();
            if (!cancelled) {
                sendError(ctx, 502, "Failed to reach model instance: " + Jsons.summarize(e.getMessage()));
            }
            resetState();
        }
    }

    /** 上游响应分块回写：每块切回 eventLoop 写出并等待完成，天然限流。运行在 executor 线程。 */
    private void streamResponse(ChannelHandlerContext ctx, HttpResponse<InputStream> response) {
        boolean keepAlive = HttpUtil.isKeepAlive(currentRequest);
        try {
            runOnEventLoop(ctx, () -> {
                DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(response.statusCode()));
                head.headers().set(HttpHeaderNames.CONTENT_TYPE,
                        response.headers().firstValue("Content-Type").orElse("application/json"));
                head.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                if (!keepAlive) {
                    head.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                }
                ctx.writeAndFlush(head);
            });
            InputStream in = response.body();
            activeResponseStream = in;
            byte[] buf = new byte[RESPONSE_CHUNK_BYTES];
            int n;
            while (!cancelled && (n = in.read(buf)) >= 0) {
                // 出站背压：channel 不可写时短暂等待（本地并发极低，简单轮询即可）
                while (!cancelled && ctx.channel().isOpen() && !ctx.channel().isWritable()) {
                    Thread.sleep(5);
                }
                if (cancelled || !ctx.channel().isOpen()) {
                    break;
                }
                byte[] copy = Arrays.copyOf(buf, n);
                runOnEventLoop(ctx, () -> ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(copy))));
            }
            in.close();
            boolean open = ctx.channel().isOpen();
            if (open && !cancelled) {
                runOnEventLoop(ctx, () -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(f -> {
                            if (!keepAlive) {
                                ctx.close();
                            }
                        }));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 响应头可能已发出，无法再回错误体；直接断连让客户端感知截断
            log.warn("响应流式回写中断: {}", e.getMessage());
            ctx.close();
        } finally {
            activeResponseStream = null;
            resetState();
        }
    }

    /** 切到 eventLoop 执行并等待完成（保持写出顺序、避免多线程并发写 channel）。 */
    private void runOnEventLoop(ChannelHandlerContext ctx, Runnable task) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ctx.executor().execute(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }

    /** GET /v1/models：聚合 READY 实例，OpenAI 格式。 */
    private void respondModels(ChannelHandlerContext ctx) {
        JsonArray data = new JsonArray();
        for (ModelInstance instance : instanceManager.list()) {
            if (instance.getStatus() != ModelInstance.Status.READY) {
                continue;
            }
            JsonObject model = new JsonObject();
            model.addProperty("id", instance.getInstanceName());
            model.addProperty("object", "model");
            model.addProperty("created", instance.getCreatedAt().getEpochSecond());
            model.addProperty("owned_by", "audiocpp");
            data.add(model);
        }
        JsonObject root = new JsonObject();
        root.addProperty("object", "list");
        root.add("data", data);
        sendJson(ctx, 200, Jsons.GSON.toJson(root));
    }

    /** OpenAI 风格错误体。可在任意线程调用（内部切 eventLoop）。 */
    private void sendError(ChannelHandlerContext ctx, int status, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        error.addProperty("type", status >= 500 ? "server_error" : "invalid_request_error");
        JsonObject root = new JsonObject();
        root.add("error", error);
        sendJson(ctx, status, Jsons.GSON.toJson(root));
    }

    private void sendJson(ChannelHandlerContext ctx, int status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        boolean keepAlive = currentRequest != null && HttpUtil.isKeepAlive(currentRequest);
        ctx.executor().execute(() -> {
            if (!ctx.channel().isOpen()) {
                return;
            }
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            if (!keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
            ctx.writeAndFlush(response).addListener(f -> {
                if (!keepAlive) {
                    ctx.close();
                }
            });
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelled = true;
        closeFileQuietly();
        deleteTempQuietly();
        InputStream in = activeResponseStream;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cancelled = true;
        closeFileQuietly();
        deleteTempQuietly();
        ctx.close();
    }

    /** 清理一次请求的状态（keep-alive 下同连接可复用 handler）。 */
    private void resetState() {
        proxying = false;
        modelsRequest = false;
        failed = false;
        currentRequest = null;
        receivedBytes = 0;
        writeChain = CompletableFuture.completedFuture(null);
    }

    private void cleanupAfterError() {
        closeFileQuietly();
        deleteTempQuietly();
        resetState();
    }

    private void closeFileQuietly() {
        if (fileOut != null) {
            try {
                fileOut.close();
            } catch (IOException ignored) {
            }
            fileOut = null;
        }
    }

    private void deleteTempQuietly() {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            tempFile = null;
        }
    }
}
