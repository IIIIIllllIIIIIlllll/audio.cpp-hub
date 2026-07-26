package org.mark.audiocpp.hub.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.mark.audiocpp.hub.config.ExecutableRegistry;
import org.mark.audiocpp.hub.config.ProfileRegistry;
import org.mark.audiocpp.hub.instance.InstanceManager;

/** HTTP pipeline：编解码 + 聚合（base64 音频可能很大，给到 64MB）+ 路由。 */
public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final InstanceManager instanceManager;
    private final ExecutableRegistry executableRegistry;
    private final ProfileRegistry profileRegistry;

    public HttpServerInitializer(InstanceManager instanceManager, ExecutableRegistry executableRegistry,
                                 ProfileRegistry profileRegistry) {
        this.instanceManager = instanceManager;
        this.executableRegistry = executableRegistry;
        this.profileRegistry = profileRegistry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(64 * 1024 * 1024))
                .addLast(new ApiHandler(instanceManager, executableRegistry, profileRegistry))
                .addLast(new StaticFileHandler());
    }
}
