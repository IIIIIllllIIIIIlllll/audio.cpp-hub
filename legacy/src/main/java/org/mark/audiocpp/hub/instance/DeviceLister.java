package org.mark.audiocpp.hub.instance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.mark.audiocpp.hub.util.Jsons;
import org.mark.audiocpp.hub.util.UserException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备探测：以 <可执行文件> --list-devices 拉起子进程，解析 stdout 中的设备行。
 * 输出格式（audio.cpp 的 print_backend_devices）：
 *   available_devices=N
 *   CUDA:0 "NVIDIA GeForce RTX 2080 Ti" [gpu]
 *   select with: --backend <cuda|hip|vulkan|metal|cpu> --device <index>
 * env 环境变量表与实例启动一致地注入（${VAR} 占位符按子进程环境展开），
 * CUDA/ROCm 等后端动态库常依赖 PATH 注入才能加载。
 */
public final class DeviceLister {

    /** 设备行：后端:序号 "名称" [类型]，名称段可选。 */
    private static final Pattern DEVICE_LINE =
            Pattern.compile("^([A-Za-z0-9_]+):(\\d+)(?:\\s+\"([^\"]*)\")?\\s+\\[([^\\]]+)\\]\\s*$");

    /** 探测超时：--list-devices 需加载各后端动态库，给足时间但避免无限挂起。 */
    private static final long TIMEOUT_SECONDS = 60;

    private DeviceLister() {
    }

    /**
     * 运行 --list-devices 并解析设备列表。
     * 返回 {"devices":[{backend,index,name,type}...], "raw": 原始输出}；
     * 进程超时/退出码非 0 抛 UserException（消息带输出末尾摘要）。
     */
    public static JsonObject listDevices(Path executable, Map<String, String> env)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(executable.toString(), "--list-devices");
        pb.redirectErrorStream(true);
        if (env != null && !env.isEmpty()) {
            Map<String, String> processEnv = pb.environment();
            for (Map.Entry<String, String> e : env.entrySet()) {
                processEnv.put(e.getKey(), InstanceManager.expandEnvValue(e.getValue(), processEnv));
            }
        }
        Process process = pb.start();
        String output;
        // redirectErrorStream 合并后只有一个流：先读完再等退出，避免管道写满互等
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new UserException("DEVICE_LIST_TIMEOUT", "列出设备超时（" + TIMEOUT_SECONDS + " 秒）");
        }
        if (process.exitValue() != 0) {
            throw new UserException("DEVICE_LIST_FAILED",
                    "列出设备失败（退出码 " + process.exitValue() + "）: " + Jsons.summarize(output));
        }
        JsonArray devices = new JsonArray();
        for (String line : output.split("\\R")) {
            Matcher m = DEVICE_LINE.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            JsonObject dev = new JsonObject();
            dev.addProperty("backend", m.group(1));
            dev.addProperty("index", Integer.parseInt(m.group(2)));
            dev.addProperty("name", m.group(3) == null ? "" : m.group(3));
            dev.addProperty("type", m.group(4));
            devices.add(dev);
        }
        JsonObject result = new JsonObject();
        result.add("devices", devices);
        result.addProperty("raw", output);
        return result;
    }
}
