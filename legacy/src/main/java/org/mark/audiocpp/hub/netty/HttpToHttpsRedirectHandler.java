package org.mark.audiocpp.hub.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * 将 HTTP 请求重定向到 HTTPS（308 Permanent Redirect，保留请求方法）。
 */
public class HttpToHttpsRedirectHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final HttpResponseStatus STATUS_308 = new HttpResponseStatus(308, "Permanent Redirect");

    private final int httpsPort;

    public HttpToHttpsRedirectHandler(int httpsPort) {
        this.httpsPort = httpsPort;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        try {
            String host = request.headers().get(HttpHeaderNames.HOST);
            if (host == null || host.isEmpty()) {
                host = "localhost";
            }

            // 去掉 IPv4 端口；IPv6 地址用 [] 包裹，避免误删内部冒号
            int colonIndex = host.lastIndexOf(':');
            int bracketCloseIndex = host.lastIndexOf(']');
            if (colonIndex > 0 && (bracketCloseIndex == -1 || colonIndex > bracketCloseIndex)) {
                host = host.substring(0, colonIndex);
            }

            String portPart = (httpsPort == 443) ? "" : ":" + httpsPort;
            String uri = request.uri();
            if (uri == null || uri.isEmpty()) {
                uri = "/";
            }
            String location = "https://" + host + portPart + uri;

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, STATUS_308);
            response.headers().set(HttpHeaderNames.LOCATION, location);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, must-revalidate");

            ctx.writeAndFlush(response);
        } finally {
            ctx.close();
        }
    }
}
