package org.mark.audiocpp.hub.netty;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * HTTPS 支持：从密钥库（PKCS12/JKS）构建 Netty SslContext。
 * <p>
 * 任何加载失败都返回 null，调用方按纯 HTTP 启动，不影响服务可用性。
 */
public final class HttpsSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpsSupport.class);

    private HttpsSupport() {}

    /**
     * 构建服务端 SslContext。
     *
     * @param certPath 密钥库文件路径；若指向目录，则优先取目录内的 .p12 文件
     * @param password 密钥库口令
     * @return 加载成功返回 SslContext，否则返回 null
     */
    public static SslContext buildSslContext(String certPath, String password) {
        try {
            File keystoreFile = new File(certPath);
            if (keystoreFile.isDirectory()) {
                File[] candidates = keystoreFile.listFiles((dir, name) -> {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".jks")
                            || lower.endsWith(".keystore");
                });
                if (candidates == null || candidates.length == 0) {
                    log.info("HTTPS证书目录中未找到证书文件: {}, 使用HTTP协议启动", certPath);
                    return null;
                }
                File chosen = null;
                for (File f : candidates) {
                    if (f.getName().toLowerCase().endsWith(".p12")) {
                        chosen = f;
                        break;
                    }
                }
                if (chosen == null) {
                    chosen = candidates[0];
                }
                keystoreFile = chosen;
            }
            if (!keystoreFile.exists()) {
                log.info("HTTPS证书文件不存在: {}, 使用HTTP协议启动", certPath);
                return null;
            }
            String storeType = "PKCS12";
            String fileName = keystoreFile.getName().toLowerCase();
            if (fileName.endsWith(".jks") || fileName.endsWith(".keystore")) {
                storeType = "JKS";
            }
            KeyStore keyStore = KeyStore.getInstance(storeType);
            char[] passwordChars = password != null ? password.toCharArray() : new char[0];
            try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                keyStore.load(fis, passwordChars);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, passwordChars);
            SslContext sslContext = SslContextBuilder.forServer(kmf).build();
            log.info("HTTPS证书加载成功: {}", keystoreFile.getAbsolutePath());
            return sslContext;
        } catch (Exception e) {
            log.info("HTTPS证书加载失败: {}, 使用HTTP协议启动", e.getMessage());
            return null;
        }
    }
}
