package org.mark.audiocpp.hub.cert;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.mark.audiocpp.hub.AudioHubServer;
import org.mark.audiocpp.hub.util.UserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * HTTPS 证书管理：证书状态查询、基于 keytool 的自签 CA + 服务器证书生成。
 * <p>
 * 证书文件统一放在工作目录的 ssl/ 下：keystore.p12（服务器密钥库）与 ca-cert.cer（供客户端安装的 CA 根证书）。
 * 生成只更新 hub.config.json 的 https 配置，需重启程序后生效。
 */
public final class CertManager {

    private static final Logger log = LoggerFactory.getLogger(CertManager.class);

    /** 证书输出目录（相对工作目录） */
    private static final String SSL_DIR = "ssl";

    private CertManager() {}

    /** 证书状态：密钥库/CA 证书是否存在、路径、口令等。 */
    public static Map<String, Object> status() {
        String keystorePath = AudioHubServer.getHttpsKeystorePath();
        Path path = Paths.get(keystorePath).toAbsolutePath().normalize();
        boolean exists = Files.exists(path) && Files.isRegularFile(path);
        Path caCertPath = caCertPath();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", AudioHubServer.isHttpsEnabled());
        data.put("exists", exists);
        data.put("path", keystorePath);
        data.put("password", AudioHubServer.getHttpsKeystorePassword());
        data.put("caCertPath", caCertPath.toString());
        data.put("caCertExists", Files.exists(caCertPath) && Files.isRegularFile(caCertPath));
        if (exists) {
            try {
                data.put("size", Files.size(path));
            } catch (Exception ignore) {
            }
        }
        return data;
    }

    /** CA 根证书固定路径（供下载安装到客户端信任库）。 */
    public static Path caCertPath() {
        return Paths.get(SSL_DIR, "ca-cert.cer").toAbsolutePath().normalize();
    }

    /**
     * 生成自签 CA + 服务器证书，写入 hub.config.json 的 https 配置（重启后生效）。
     * body 字段：{"ips"?, "hostnames"?, "validity"?, "password"?, "keysize"?, "cn"?}。
     */
    public static Map<String, Object> generate(JsonObject body) throws Exception {
        List<String> ips = getJsonStringList(body.get("ips"));
        List<String> hostnames = getJsonStringList(body.get("hostnames"));
        int validity = getJsonInt(body, "validity", 3650);
        if (validity < 1) {
            validity = 3650;
        }
        String password = getJsonString(body, "password");
        if (password.isEmpty()) {
            password = generatePassword();
        }
        if (password.length() < 6) {
            throw new UserException("CERT_PASSWORD_MINLENGTH", null, "证书密码长度至少 6 位");
        }
        int keysize = getJsonInt(body, "keysize", 2048);
        if (keysize != 2048 && keysize != 4096) {
            keysize = 2048;
        }

        String userCn = getJsonString(body, "cn");
        if (userCn.isEmpty()) {
            if (hostnames != null && !hostnames.isEmpty()) {
                userCn = hostnames.get(0);
            } else {
                userCn = "localhost";
            }
        }

        Path outputPath = Paths.get(SSL_DIR);
        Files.createDirectories(outputPath);
        Path keystoreFile = outputPath.resolve("keystore.p12");
        Path caCertFile = outputPath.resolve("ca-cert.cer");
        Path caKeystore = outputPath.resolve("ca-keystore-temp.p12");
        Path serverCsr = outputPath.resolve("server.csr");
        Path serverSigned = outputPath.resolve("server-signed.cer");
        Path caCertTemp = outputPath.resolve("ca-cert-temp.cer");

        // 清理旧文件
        try {
            Files.deleteIfExists(keystoreFile);
            Files.deleteIfExists(caCertFile);
            Files.deleteIfExists(caKeystore);
            Files.deleteIfExists(serverCsr);
            Files.deleteIfExists(serverSigned);
            Files.deleteIfExists(caCertTemp);
        } catch (IOException e) {
            log.warn("清理旧证书文件失败", e);
        }

        String cn = userCn;
        String caDname = "CN=" + cn + "-CA,OU=audiocpp-hub,O=audiocpp-hub,L=Unknown,ST=Unknown,C=CN";
        String serverDname = "CN=" + cn + ",OU=audiocpp-hub,O=audiocpp-hub,L=Unknown,ST=Unknown,C=CN";

        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        String keytoolPath = javaHome + File.separator + "bin" + File.separator
                + (isWindows ? "keytool.exe" : "keytool");

        StringBuilder sanBuilder = new StringBuilder();
        if (hostnames != null) {
            for (String h : hostnames) {
                String t = h.trim();
                if (!t.isEmpty()) {
                    if (sanBuilder.length() > 0) {
                        sanBuilder.append(",");
                    }
                    sanBuilder.append("DNS:").append(t);
                }
            }
        }
        if (!sanBuilder.toString().contains("DNS:localhost")) {
            if (sanBuilder.length() > 0) {
                sanBuilder.append(",");
            }
            sanBuilder.append("DNS:localhost");
        }
        if (!sanBuilder.toString().contains("IP:127.0.0.1")) {
            sanBuilder.append(",IP:127.0.0.1");
        }
        if (ips != null) {
            for (String ip : ips) {
                String t = ip.trim();
                if (!t.isEmpty() && !"127.0.0.1".equals(t)) {
                    sanBuilder.append(",IP:").append(t);
                }
            }
        }

        // 1. 生成 CA 根证书（含 BasicConstraints CA:true 与 keyCertSign）
        runKeytool(keytoolPath,
                "-genkeypair",
                "-alias", "ca",
                "-keyalg", "RSA",
                "-keysize", String.valueOf(keysize),
                "-keystore", caKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", password,
                "-keypass", password,
                "-dname", caDname,
                "-validity", String.valueOf(validity),
                "-ext", "bc:critical=ca:true,pathlen:1",
                "-ext", "ku:critical=keyCertSign,cRLSign");

        // 2. 生成服务器密钥库（自签占位证书，后续会被替换）
        runKeytool(keytoolPath,
                "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", String.valueOf(keysize),
                "-keystore", keystoreFile.toString(),
                "-storetype", "PKCS12",
                "-storepass", password,
                "-keypass", password,
                "-dname", serverDname,
                "-validity", String.valueOf(validity));

        // 3. 为服务器生成证书签名请求 CSR
        runKeytool(keytoolPath,
                "-certreq",
                "-alias", "server",
                "-keystore", keystoreFile.toString(),
                "-storepass", password,
                "-keypass", password,
                "-file", serverCsr.toString());

        // 4. 使用 CA 签发服务器证书（含 SAN、serverAuth EKU）
        runKeytool(keytoolPath,
                "-gencert",
                "-infile", serverCsr.toString(),
                "-outfile", serverSigned.toString(),
                "-alias", "ca",
                "-keystore", caKeystore.toString(),
                "-storepass", password,
                "-keypass", password,
                "-validity", String.valueOf(validity),
                "-ext", "SAN=" + sanBuilder.toString(),
                "-ext", "ku=digitalSignature,keyEncipherment",
                "-ext", "eku=serverAuth",
                "-rfc");

        // 5. 导出 CA 公钥证书，供客户端安装到受信任根证书颁发机构
        runKeytool(keytoolPath,
                "-exportcert",
                "-alias", "ca",
                "-keystore", caKeystore.toString(),
                "-storepass", password,
                "-file", caCertTemp.toString(),
                "-rfc");

        // 6. 构建服务器证书链并导入密钥库，替换原有自签占位证书
        String chainContent = Files.readString(caCertTemp, StandardCharsets.UTF_8);
        String serverCertContent = Files.readString(serverSigned, StandardCharsets.UTF_8);
        String fullChain = serverCertContent + System.lineSeparator() + chainContent;
        Path serverChain = outputPath.resolve("server-chain.cer");
        Files.writeString(serverChain, fullChain, StandardCharsets.UTF_8);

        runKeytool(keytoolPath,
                "-importcert",
                "-alias", "server",
                "-file", serverChain.toString(),
                "-keystore", keystoreFile.toString(),
                "-storepass", password,
                "-keypass", password,
                "-noprompt");

        // 7. 保存 CA 证书到固定位置，方便浏览器/客户端下载安装
        Files.copy(caCertTemp, caCertFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // 清理临时文件
        Files.deleteIfExists(caKeystore);
        Files.deleteIfExists(serverCsr);
        Files.deleteIfExists(serverSigned);
        Files.deleteIfExists(caCertTemp);
        Files.deleteIfExists(serverChain);

        String expireDate = LocalDate.now().plusDays(validity).format(DateTimeFormatter.ISO_LOCAL_DATE);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", keystoreFile.toString());
        data.put("caCertPath", caCertFile.toAbsolutePath().normalize().toString());
        data.put("password", password);
        data.put("san", sanBuilder.toString());
        data.put("expireDate", expireDate);
        data.put("keysize", keysize);
        data.put("restartRequired", true);

        // 只更新证书路径与口令，是否启用 HTTPS 由用户在配置中自行打开
        AudioHubServer.updateHttpsConfig(null, keystoreFile.toString(), password);
        log.info("HTTPS证书生成成功: {}, CA证书: {}", keystoreFile, caCertFile);
        return data;
    }

    /** 执行 keytool 命令，失败时抛出异常并附带命令输出。 */
    private static String runKeytool(String keytoolPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(keytoolPath);
        for (String arg : args) {
            cmd.add(arg);
        }

        String cmdString = cmd.stream().map(s -> {
            if (s.contains(" ") || s.contains(",") || s.contains("=") || s.contains(";")) {
                return "\"" + s + "\"";
            }
            return s;
        }).collect(Collectors.joining(" "));
        log.info("执行命令: {}", cmdString);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String err = output.toString().trim();
            log.error("keytool 执行失败, exitCode={}, 输出: {}", exitCode, err);
            throw new UserException("CERT_GENERATE_FAILED", Map.of("msg", err),
                    "keytool 执行失败 (exitCode=" + exitCode + "): " + err);
        }
        return output.toString();
    }

    private static String generatePassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    private static int getJsonInt(JsonObject obj, String key, int def) {
        if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception ignore) {
            }
        }
        return def;
    }

    private static List<String> getJsonStringList(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            if (e.isJsonPrimitive()) {
                list.add(e.getAsString());
            }
        }
        return list;
    }
}
