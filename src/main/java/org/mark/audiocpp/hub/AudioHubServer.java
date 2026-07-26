package org.mark.audiocpp.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.mark.audiocpp.hub.config.ExecutableRegistry;
import org.mark.audiocpp.hub.config.ProfileRegistry;
import org.mark.audiocpp.hub.instance.InstanceManager;
import org.mark.audiocpp.hub.netty.HttpServerInitializer;
import org.mark.audiocpp.hub.util.Jsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * audio.cpp-hub 入口：Netty HTTP 服务 + audiocpp_server 进程管家。
 */
public class AudioHubServer {

    private static final Logger log = LoggerFactory.getLogger(AudioHubServer.class);

    public static void main(String[] args) throws Exception {
        HubConfig config = loadConfig();

        ExecutableRegistry executableRegistry = new ExecutableRegistry();
        ProfileRegistry profileRegistry = new ProfileRegistry();
        InstanceManager instanceManager = new InstanceManager(config);
        // JVM 退出时停止所有子进程
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("收到退出信号，停止所有模型实例...");
            instanceManager.stopAll();
        }));

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HttpServerInitializer(instanceManager, executableRegistry, profileRegistry));
            Channel channel = bootstrap.bind(config.httpPort).sync().channel();
            log.info("audio.cpp-hub 已启动: http://127.0.0.1:{}", config.httpPort);
            channel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    /** 读取工作目录下 hub.config.json，不存在则用默认值。 */
    private static HubConfig loadConfig() {
        HubConfig config = new HubConfig();
        Path path = Path.of("hub.config.json");
        if (Files.isRegularFile(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                if (obj.has("httpPort")) config.httpPort = obj.get("httpPort").getAsInt();
                if (obj.has("instancePortBase")) config.instancePortBase = obj.get("instancePortBase").getAsInt();
            } catch (Exception e) {
                log.warn("读取 hub.config.json 失败，使用默认配置: {}", Jsons.summarize(e.getMessage()));
            }
        } else {
            log.info("未找到 hub.config.json，使用默认配置");
        }
        return config;
    }

    /** hub 自身配置。 */
    public static class HubConfig {
        public int httpPort = 8080;
        public int instancePortBase = 18080;
    }
}
