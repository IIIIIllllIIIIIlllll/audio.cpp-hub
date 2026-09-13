package org.mark.audiocpp.hub.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.BuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 系统状态采集：GPU 指标（优先 nvidia-smi，失败回退 DRI 卡号探测）+ hub 自身运行时长 / JVM 内存 / 版本。
 * <p>
 * nvidia-smi 不存在、不在 PATH、驱动异常或超时（统称"不可用"）时，进入 60s 冷却期，
 * 冷却期内直接返回缓存的错误，不再反复拉起子进程（容器内无该命令时会持续失败）。
 * 所有查询无锁（volatile 状态 + 每次调用独立采样），前端 2s 轮询安全。
 */
public class SystemStatsCollector {

    private static final Logger log = LoggerFactory.getLogger(SystemStatsCollector.class);

    /** 启动时刻（hub 进程） */
    private static final Instant START = Instant.now();
    /** nvidia-smi 单次采样超时 */
    private static final long SMI_TIMEOUT_SECONDS = 3;
    /** nvidia-smi 不可用后的重试冷却 */
    private static final long SMI_COOLDOWN_MS = 60_000;
    /** nvidia-smi 在 PATH 中找不到时的常见绝对路径（Windows 默认安装位置） */
    private static final List<String> SMI_FALLBACK_PATHS = List.of(
            "C:\\Windows\\System32\\nvidia-smi.exe",
            "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe"
    );

    private volatile String smiUnavailable = "not probed yet";
    private volatile long smiLastFailureAt = 0;

    /** hub 自身状态（无 GPU 依赖，永远可用）。 */
    public JsonObject hubStats() {
        JsonObject o = new JsonObject();
        o.addProperty("startedAt", START.toString());
        o.addProperty("uptimeSec", Duration.between(START, Instant.now()).getSeconds());
        Runtime rt = Runtime.getRuntime();
        o.addProperty("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
        o.addProperty("heapMaxBytes", rt.maxMemory());
        o.addProperty("version", BuildInfo.getVersion());
        o.addProperty("tag", BuildInfo.getTag());
        return o;
    }

    /**
     * GPU 指标数组；无 NVIDIA 环境时数组为空并带 unavailable 原因。
     * 前端据此显示 GPU 面板的"不可用"态而不是报错。
     */
    public JsonObject gpuStats() {
        JsonObject o = new JsonObject();
        JsonArray gpus = new JsonArray();
        String err = null;
        if (inCooldown()) {
            err = smiUnavailable;
        } else {
            try {
                gpus = queryNvidiaSmi();
                smiUnavailable = null;
            } catch (Exception e) {
                err = summarize(e.getMessage() == null ? e.toString() : e.getMessage());
                smiUnavailable = err;
                smiLastFailureAt = System.currentTimeMillis();
                log.debug("nvidia-smi 采样失败: {}", err);
            }
        }
        if (gpus.isEmpty()) {
            // nvidia-smi 不可用时回退：DRI 卡号探测（llvmpipe/AMD/Intel 部分场景），至少给出卡号
            for (int idx : listDriCardIndexes()) {
                JsonObject g = new JsonObject();
                g.addProperty("index", idx);
                g.addProperty("name", "dri/card" + idx);
                gpus.add(g);
            }
        }
        o.add("gpus", gpus);
        o.addProperty("available", gpus.size() > 0);
        if (err != null) {
            o.addProperty("unavailableReason", err);
        }
        return o;
    }

    private boolean inCooldown() {
        return smiUnavailable != null
                && System.currentTimeMillis() - smiLastFailureAt < SMI_COOLDOWN_MS;
    }

    private JsonArray queryNvidiaSmi() throws Exception {
        String smi = "nvidia-smi";
        if (!existsOnPath(smi)) {
            String found = SMI_FALLBACK_PATHS.stream().filter(p -> Files.isRegularFile(Path.of(p)))
                    .findFirst().orElse(null);
            if (found == null) {
                throw new IllegalStateException("nvidia-smi not found");
            }
            smi = found;
        }
        List<String> cmd = List.of(smi,
                "--query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                "--format=csv,noheader,nounits");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        JsonArray arr = new JsonArray();
        boolean ok;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject g = parseSmiLine(line);
                if (g != null) {
                    arr.add(g);
                }
            }
            p.waitFor(SMI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ok = p.exitValue() == 0;
        } finally {
            p.destroyForcibly();
        }
        if (!ok) {
            throw new IllegalStateException("nvidia-smi exited non-zero");
        }
        if (arr.isEmpty()) {
            throw new IllegalStateException("nvidia-smi returned no GPU rows");
        }
        return arr;
    }

    /** 解析单行 CSV：index, name, util%, memUsed MiB, memTotal MiB, temp°C。字段缺失容忍。 */
    private static JsonObject parseSmiLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(",");
        if (parts.length < 6) {
            return null;
        }
        try {
            JsonObject g = new JsonObject();
            g.addProperty("index", Integer.parseInt(parts[0].trim()));
            g.addProperty("name", parts[1].trim());
            g.addProperty("utilPct", intOrNull(parts[2]));
            g.addProperty("memUsedMib", intOrNull(parts[3]));
            g.addProperty("memTotalMib", intOrNull(parts[4]));
            g.addProperty("tempC", intOrNull(parts[5]));
            return g;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean existsOnPath(String cmd) {
        // Windows 上 ProcessBuilder 无法直接执行 nvidia-smi（依赖 PATHEXT），逐目录探测
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (String dir : System.getenv("PATH").split(";", -1)) {
                if (Files.isRegularFile(Path.of(dir, cmd + ".exe"))) {
                    return true;
                }
            }
            return false;
        }
        for (String dir : System.getenv("PATH").split(":", -1)) {
            if (Files.isRegularFile(Path.of(dir, cmd))) {
                return true;
            }
        }
        return false;
    }

    /** DRI 卡号探测（nvidia-smi 之外的兜底）：/dev/dri/cardN 存在则列出。 */
    private static List<Integer> listDriCardIndexes() {
        List<Integer> cards = new ArrayList<>();
        try (var stream = Files.list(Path.of("/dev/dri"))) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("card")) {
                    try {
                        cards.add(Integer.parseInt(name.substring(4)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return cards;
    }

    private static String summarize(String msg) {
        String s = msg == null ? "" : msg.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
