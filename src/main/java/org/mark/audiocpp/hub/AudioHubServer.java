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
import org.mark.audiocpp.hub.win.AutoStartManager;
import org.mark.audiocpp.hub.win.WindowsTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * audio.cpp-hub 入口：Netty HTTP 服务 + audiocpp_server 进程管家。
 */
public class AudioHubServer {

    private static final Logger log = LoggerFactory.getLogger(AudioHubServer.class);

    private static final Object RESTART_LOCK = new Object();
    private static volatile Channel serverChannel;
    private static volatile EventLoopGroup bossGroup;
    private static volatile EventLoopGroup workerGroup;

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
        bossGroup = boss;
        workerGroup = worker;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HttpServerInitializer(instanceManager, executableRegistry, profileRegistry));
            Channel channel = bootstrap.bind(config.httpPort).sync().channel();
            serverChannel = channel;
            log.info("audio.cpp-hub 已启动: http://127.0.0.1:{}", config.httpPort);
            createWindowsSystemTray(config.httpPort);
            channel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    /** 重启程序：先关闭 HTTP 服务释放端口，再拉起新进程并退出当前进程。 */
    public static void restartApplication() {
        synchronized (RESTART_LOCK) {
            log.info("准备重启程序...");
            try {
                // 1. 关闭 HTTP 服务，释放端口（模型实例由 JVM 退出钩子负责停止）
                Channel channel = serverChannel;
                if (channel != null) {
                    channel.close().syncUninterruptibly();
                }
                if (bossGroup != null) bossGroup.shutdownGracefully();
                if (workerGroup != null) workerGroup.shutdownGracefully();

                // 2. 拉起新进程
                RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
                List<String> jvmArgs = mx.getInputArguments();
                String classpath = System.getProperty("java.class.path");
                boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
                String javaBin = System.getProperty("java.home") + File.separator + "bin"
                        + File.separator + (isWindows ? "java.exe" : "java");

                List<String> cmd = new ArrayList<>();
                cmd.add(javaBin);
                cmd.addAll(jvmArgs);
                cmd.add("-classpath");
                cmd.add(classpath);
                cmd.add("org.mark.audiocpp.hub.AudioHubServer");

                new ProcessBuilder(cmd).inheritIO().start();
                log.info("重启进程已启动");
            } catch (Exception e) {
                // 失败了就别重启了
                log.error("重启失败", e);
                return;
            }
            System.exit(0);
        }
    }

    /** 创建 Windows 系统托盘。 */
    private static void createWindowsSystemTray(int httpPort) {
        // 判断操作系统是否为Windows，如果不是则直接返回
        String osName = System.getProperty("os.name");
        if (!osName.toLowerCase().startsWith("windows")) {
            return;
        }

        // 根据系统语言选择托盘菜单文本
        boolean isChinese = "zh".equals(Locale.getDefault().getLanguage());
        String btnOpen = isChinese ? "打开首页" : "Open Homepage";
        String btnRestart = isChinese ? "重启程序" : "Restart";
        String btnAutoStart = isChinese ? "开机自启" : "Auto Start";
        String btnExit = isChinese ? "退出程序" : "Exit";
        String notifyTitle = isChinese ? "启动成功" : "Started";
        String notifyMsg = isChinese ? "audio.cpp-hub 已在后台运行" : "audio.cpp-hub is running in background";

        try {
            WindowsTray tray = WindowsTray.getInstance();
            String host = "http://127.0.0.1:" + httpPort;
            tray.addButton(btnOpen, () -> {
                try {
                    Desktop.getDesktop().browse(new URI(host));
                } catch (Exception e) {
                    log.warn("打开浏览器失败: {}", e.getMessage());
                }
            });
            tray.addButton(btnRestart, AudioHubServer::restartApplication);
            String autoStartId = "autostart-toggle";
            tray.addCheckBoxButton(autoStartId, btnAutoStart, AutoStartManager.isAutoStartEnabled(), () -> {
                boolean current = AutoStartManager.isAutoStartEnabled();
                new Thread(() -> {
                    boolean success = current ? AutoStartManager.disableAutoStart() : AutoStartManager.enableAutoStart();
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (!success) {
                            tray.setCheckBoxSelected(autoStartId, current);
                            javax.swing.JOptionPane.showMessageDialog(null,
                                current ? (isChinese ? "关闭开机自启失败" : "Failed to disable auto start")
                                        : (isChinese ? "设置开机自启失败，请确认 audio.cpp-hub.exe 存在"
                                                     : "Failed to enable auto start. Ensure audio.cpp-hub.exe exists."),
                                "audio.cpp-hub", javax.swing.JOptionPane.ERROR_MESSAGE);
                        } else {
                            javax.swing.JOptionPane.showMessageDialog(null,
                                current ? (isChinese ? "已关闭开机自启" : "Auto start disabled")
                                        : (isChinese ? "已开启开机自启" : "Auto start enabled"),
                                "audio.cpp-hub", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                        }
                    });
                }, "autostart-toggle").start();
            });
            tray.addSeparator();
            tray.addButton(btnExit, () -> System.exit(0));

            tray.setDefaultAction(() -> {
                // 双击托盘图标触发，打开首页
                try {
                    Desktop.getDesktop().browse(new URI(host));
                } catch (Exception e) {
                    log.warn("打开浏览器失败: {}", e.getMessage());
                }
            });

            tray.start("audio.cpp-hub");
            tray.displayInfoMessage(notifyTitle, notifyMsg);
        } catch (Exception e) {
            log.warn("创建系统托盘失败: {}", e.getMessage());
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
