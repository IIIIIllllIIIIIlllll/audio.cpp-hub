package org.mark.audiocpp.hub.instance;

import org.mark.audiocpp.hub.AudioHubServer;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.UserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例管理器：负责拉起 / 停止 audiocpp_server 子进程，并轮询健康状态。
 * 只有 STARTING/READY 的实例留在 map 中：失败（启动异常、进程提前退出、健康检查超时）
 * 与停止都会移除实例并记录事件（GET /api/events 可查）。
 */
public class InstanceManager {

    private static final Logger log = LoggerFactory.getLogger(InstanceManager.class);
    private static final int HEALTH_TIMEOUT_SECONDS = 120;
    private static final java.util.regex.Pattern ENV_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    /** 服务名规则：字母数字开头，可含 . _ -，最长 64（要能被 OpenAI 客户端当 model 名用）。 */
    private static final java.util.regex.Pattern INSTANCE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final AudioHubServer.HubConfig config;
    private final Map<String, ModelInstance> instances = new ConcurrentHashMap<>();
    private final EventLog events = new EventLog();
    private final HttpClient healthClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public InstanceManager(AudioHubServer.HubConfig config) {
        this.config = config;
    }

    public List<ModelInstance> list() {
        return new ArrayList<>(instances.values());
    }

    public ModelInstance get(String id) {
        return instances.get(id);
    }

    /** 按服务名查找 READY 实例（/v1/* 路由用），不存在或未就绪返回 null。 */
    public ModelInstance findByName(String name) {
        if (name == null) return null;
        for (ModelInstance instance : instances.values()) {
            if (name.equals(instance.getInstanceName()) && instance.getStatus() == ModelInstance.Status.READY) {
                return instance;
            }
        }
        return null;
    }

    /** 是否存在该服务名的存活实例（STARTING/READY），用于 /v1/* 区分"未就绪"与"不存在"。 */
    public ModelInstance findAnyByName(String name) {
        if (name == null) return null;
        for (ModelInstance instance : instances.values()) {
            if (name.equals(instance.getInstanceName())) {
                return instance;
            }
        }
        return null;
    }

    public EventLog events() {
        return events;
    }

    /**
     * 启动一个实例，立即返回（STARTING），后台线程轮询健康状态。
     * 进程拉起失败时记事件并抛异常，不会留下僵尸实例。
     * env 为可执行文件条目上配置的环境变量表，注入子进程；值中 ${VAR} 按 hub 进程环境展开。
     * instanceName 为服务名（/v1/* 路由键，写进 server.json 的 model id），为空默认 modelId；
     * 与存活实例重名时抛 UserException。
     */
    public ModelInstance start(String modelId, String weightsPath, String backend,
                               Integer device, Integer requestedPort, Integer threads,
                               String executablePath, String executableName, String serverTask,
                               Map<String, String> env, String instanceName) throws IOException {
        if (instanceName == null || instanceName.isBlank()) {
            instanceName = modelId;
        }
        instanceName = instanceName.trim();
        if (!INSTANCE_NAME.matcher(instanceName).matches()) {
            throw new UserException("INSTANCE_NAME_INVALID", Map.of("name", instanceName),
                    "服务名不合法（字母数字开头，可含 . _ -，最长 64）: " + instanceName);
        }
        for (ModelInstance existing : instances.values()) {
            if (existing.getInstanceName().equals(instanceName)) {
                throw new UserException("INSTANCE_NAME_DUPLICATE", Map.of("name", instanceName),
                        "服务名已被实例 #" + existing.getId() + " 占用: " + instanceName);
            }
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("modelId", id);
        try {
            int port = (requestedPort != null) ? requestedPort : allocatePort();
            Path dir = Path.of("run", id);
            Files.createDirectories(dir);
            Path serverJson = dir.resolve("server.json");
            ServerConfigWriter.write(serverJson, "127.0.0.1", port, backend, device, threads,
                    instanceName, modelId, weightsPath, serverTask);

            Path logFile = dir.resolve("server.log");
            ProcessBuilder pb = new ProcessBuilder(executablePath, "--config", serverJson.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            if (env != null && !env.isEmpty()) {
                Map<String, String> processEnv = pb.environment();
                for (Map.Entry<String, String> e : env.entrySet()) {
                    processEnv.put(e.getKey(), expandEnvValue(e.getValue(), processEnv));
                }
                log.info("注入环境变量: {}", String.join(", ", env.keySet()));
            }
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                // 二进制缺失等：记事件、清掉已生成的运行目录后抛出，实例不进 map
                events.add("error", "实例启动失败（" + executableName + "）: " + Jsons.summarize(e.getMessage()));
                log.error("进程拉起失败: {}", e.getMessage());
                cleanupRunDir(id);
                throw e;
            }

            ModelInstance instance = new ModelInstance(id, instanceName, modelId, weightsPath, port, backend, device,
                    executableName, serverJson);
            instance.setProcess(process);
            instances.put(id, instance);
            log.info("实例已启动: executable={}, name={}, modelId={}, backend={}, device={}, port={}, pid={}",
                    executableName, instanceName, modelId, backend, device, port, process.pid());
            events.add("info", "实例 #" + id + " 启动中（" + executableName + "，端口 " + port + "）");

            Thread waiter = new Thread(() -> awaitReady(instance), "instance-watch-" + id);
            waiter.setDaemon(true);
            waiter.start();
            return instance;
        } finally {
            MDC.remove("modelId");
        }
    }

    /** 停止实例：进程终止后从 map 移除并记事件。 */
    public boolean stop(String id) {
        ModelInstance instance = instances.remove(id);
        if (instance == null) return false;
        MDC.put("modelId", id);
        try {
            Process process = instance.getProcess();
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        process.waitFor();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            log.info("实例已停止: port={}", instance.getPort());
            events.add("info", "实例 #" + id + " 已停止");
            cleanupRunDir(id);
            return true;
        } finally {
            MDC.remove("modelId");
        }
    }

    /** 停止全部实例（shutdown hook 调用）。 */
    public void stopAll() {
        for (ModelInstance instance : list()) {
            stop(instance.getId());
        }
    }

    /** 从 instancePortBase 起分配一个未被占用的端口。 */
    private synchronized int allocatePort() {
        int port = config.instancePortBase;
        while (isPortUsed(port)) {
            port++;
        }
        return port;
    }

    private boolean isPortUsed(int port) {
        for (ModelInstance instance : instances.values()) {
            if (instance.getPort() == port) {
                return true;
            }
        }
        // 也探测一下系统层面是否被占用
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** 后台线程：每 1s 轮询 /health，最多 120s；失败路径移除实例并记事件。 */
    private void awaitReady(ModelInstance instance) {
        String id = instance.getId();
        MDC.put("modelId", id);
        try {
            long deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_SECONDS * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Process process = instance.getProcess();
                if (process != null && !process.isAlive()) {
                    String reason = "实例 #" + id + " 进程提前退出 (exit=" + process.exitValue()
                            + ")，日志尾部: " + readLogTail(instance);
                    log.error("实例进程提前退出: exit={}", process.exitValue());
                    events.add("error", reason);
                    instances.remove(id);
                    cleanupRunDir(id);
                    return;
                }
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + instance.getPort() + "/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET().build();
                    HttpResponse<String> response = healthClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        instance.setStatus(ModelInstance.Status.READY);
                        log.info("实例就绪: port={}", instance.getPort());
                        events.add("info", "实例 #" + id + " 已就绪（端口 " + instance.getPort() + "）");
                        return;
                    }
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // 连接拒绝等，继续等待
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.error("实例等待就绪超时");
            events.add("error", "实例 #" + id + " 等待就绪超时 (" + HEALTH_TIMEOUT_SECONDS
                    + "s)，日志尾部: " + readLogTail(instance));
            instances.remove(id);
            cleanupRunDir(id);
        } finally {
            MDC.remove("modelId");
        }
    }

    /**
     * 删除实例运行目录 run/<id>（server.json + server.log），清理失败只告警不中断。
     * 进程刚终止时子进程侧的文件句柄释放有延迟（Windows 上 server.log 常被短暂占用），
     * 因此带有限重试。
     */
    private void cleanupRunDir(String id) {
        Path dir = Path.of("run", id);
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Files.deleteIfExists(dir.resolve("server.json"));
                Files.deleteIfExists(dir.resolve("server.log"));
                Files.deleteIfExists(dir);
                return;
            } catch (IOException e) {
                if (attempt == 5) {
                    log.warn("清理实例运行目录失败: {}: {}", dir, e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 展开值中的 ${VAR} 占位符（按子进程将继承的环境查值，未定义展开为空串）。 */
    private static String expandEnvValue(String value, Map<String, String> processEnv) {
        java.util.regex.Matcher m = ENV_PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = processEnv.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 读取实例日志末尾几行，用于错误诊断。 */
    private String readLogTail(ModelInstance instance) {
        Path logFile = Path.of("run", instance.getId(), "server.log");
        try {
            if (Files.isRegularFile(logFile)) {
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 10);
                return Jsons.summarize(String.join(" | ", lines.subList(from, lines.size())));
            }
        } catch (IOException ignored) {
        }
        return "(无日志)";
    }
}
