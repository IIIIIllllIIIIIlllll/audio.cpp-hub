package org.mark.audiocpp.hub.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.mark.audiocpp.hub.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 静态文件服务：从工作目录下的 web/ 提供前端文件，防路径穿越。
 */
public class StaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Path WEB_ROOT = Path.of("web").toAbsolutePath().normalize();

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "png", "image/png",
            "jpg", "image/jpeg",
            "svg", "image/svg+xml",
            "json", "application/json; charset=utf-8",
            "ico", "image/x-icon");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws IOException {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();
        if (path.equals("/")) {
            path = "/index.html";
        }
        // 防路径穿越：normalize 后必须仍在 web/ 目录内
        Path resolved = WEB_ROOT.resolve(path.substring(1)).normalize();
        if (!resolved.startsWith(WEB_ROOT) || !Files.isRegularFile(resolved)) {
            sendText(ctx, HttpResponseStatus.NOT_FOUND, "application/json; charset=utf-8",
                    Jsons.error("not found"), HttpVersion.HTTP_1_1, request);
            return;
        }
        byte[] content = Files.readAllBytes(resolved);
        String ext = "";
        int dot = resolved.getFileName().toString().lastIndexOf('.');
        if (dot >= 0) {
            ext = resolved.getFileName().toString().substring(dot + 1).toLowerCase();
        }
        sendBytes(ctx, HttpResponseStatus.OK, CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"),
                content, HttpVersion.HTTP_1_1, request);
    }

    static void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType,
                         String body, HttpVersion version, FullHttpRequest request) {
        sendBytes(ctx, status, contentType, body.getBytes(StandardCharsets.UTF_8), version, request);
    }

    static void sendBytes(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType,
                          byte[] body, HttpVersion version, FullHttpRequest request) {
        sendBytes(ctx, status, contentType, body, version, request, null);
    }

    static void sendBytes(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType,
                          byte[] body, HttpVersion version, FullHttpRequest request, String contentDisposition) {
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        FullHttpResponse response = new DefaultFullHttpResponse(version, status, buf);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        if (contentDisposition != null) {
            response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, contentDisposition);
        }
        if (io.netty.handler.codec.http.HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
