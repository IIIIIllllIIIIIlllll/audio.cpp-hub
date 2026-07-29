package org.mark.audiocpp.hub.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import org.mark.audiocpp.hub.AudioHubServer;
import org.mark.audiocpp.hub.config.ExecutableRegistry;
import org.mark.audiocpp.hub.config.ProfileRegistry;
import org.mark.audiocpp.hub.instance.InstanceManager;
import org.mark.audiocpp.hub.proxy.V1ProxyHandler;

/** HTTP pipeline：编解码 + /v1/* 流式代理（aggregator 之前）+ 聚合（base64 音频可能很大，给到 64MB）+ 路由。
 *  HTTPS 启用时改为统一端口协议探测。 */
public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final InstanceManager instanceManager;
    private final ExecutableRegistry executableRegistry;
    private final ProfileRegistry profileRegistry;
    private final SslContext sslContext;
    private final AudioHubServer.HubConfig config;

    public HttpServerInitializer(InstanceManager instanceManager, ExecutableRegistry executableRegistry,
                                 ProfileRegistry profileRegistry, SslContext sslContext,
                                 AudioHubServer.HubConfig config) {
        this.instanceManager = instanceManager;
        this.executableRegistry = executableRegistry;
        this.profileRegistry = profileRegistry;
        this.sslContext = sslContext;
        this.config = config;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        if (sslContext != null) {
            // HTTPS 已启用：统一端口探测协议，TLS 走 HTTPS，纯 HTTP 一律 308 跳转
            ch.pipeline().addLast(new HttpHttpsUnificationHandler(sslContext, config,
                    instanceManager, executableRegistry, profileRegistry));
            return;
        }
        ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new V1ProxyHandler(instanceManager, config.proxyMaxBodyBytes))
                .addLast(new HttpObjectAggregator(64 * 1024 * 1024))
                .addLast(new ApiHandler(instanceManager, executableRegistry, profileRegistry))
                .addLast(new StaticFileHandler());
    }
}
