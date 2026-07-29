package org.mark.audiocpp.hub.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 流式提取代理请求顶层 JSON 的 "model" 字段。
 * 逐字节扫描整个顶层对象（只透传不落内存），model 出现在任意位置都能拿到；
 * 大字段（base64 音频）在扫描中被跳过，不会进入 Java 字符串。
 * 机制移植自 LlamacppServer 的 ChatRequestStreamingTransformer，裁剪为只读提取。
 */
public final class RequestModelExtractor {

    /** model 值的捕获上限（服务名最长 64，给足余量；超限即视为非法请求）。 */
    private static final int MODEL_CAPTURE_LIMIT = 4096;

    private RequestModelExtractor() {}

    /** 代理请求错误：带 HTTP 状态码，V1ProxyHandler 转成 OpenAI 风格错误体。 */
    public static class ProxyRequestException extends IOException {
        private static final long serialVersionUID = 1L;
		private final int status;

        public ProxyRequestException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * 扫描请求体文件，返回顶层 "model" 字符串值；缺失/非字符串/为空返回 null。
     * 文件超过 maxBytes 抛 413，顶层结构非法抛 400。
     */
    public static String extract(Path file, long maxBytes) throws IOException {
        if (Files.size(file) > maxBytes) {
            throw new ProxyRequestException(413, "Request body exceeds proxy limit of " + maxBytes + " bytes");
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            return extract(in, maxBytes);
        }
    }

    /** 同上，直接读流（调用方负责关闭）。 */
    public static String extract(InputStream input, long maxBytes) throws IOException {
        CountingOutputStream devNull = new CountingOutputStream(maxBytes);
        PushbackInputStream stream = new PushbackInputStream(input, 1);
        int firstToken = nextNonWhitespace(stream);
        if (firstToken != '{') {
            throw new ProxyRequestException(400, "Request body is not a valid JSON object");
        }
        String model = null;
        boolean firstField = true;
        while (true) {
            int token = nextNonWhitespace(stream);
            if (token == '}') {
                break;
            }
            if (token < 0) {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            if (!firstField) {
                if (token != ',') {
                    throw new ProxyRequestException(400, "Request body is not a valid JSON object");
                }
                token = nextNonWhitespace(stream);
            }
            firstField = false;
            if (token != '"') {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            String fieldName = readJsonString(stream);
            int colon = nextNonWhitespace(stream);
            if (colon != ':') {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            int valueStart = nextNonWhitespace(stream);
            if (valueStart < 0) {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            if ("model".equals(fieldName)) {
                ByteArrayOutputStream capture = new ByteArrayOutputStream();
                try {
                    copyValue(stream, valueStart, new LimitedOutputStream(capture, MODEL_CAPTURE_LIMIT));
                } catch (IllegalStateException e) {
                    throw new ProxyRequestException(400, "model value too large");
                }
                model = parseModelValue(capture.toByteArray());
            } else {
                // 大字段热点：原样跳过，只计数不落内存
                copyValue(stream, valueStart, devNull);
            }
        }
        return model;
    }

    /** 解析捕获到的 model 原始值，非 JSON 字符串返回 null。 */
    private static String parseModelValue(byte[] raw) {
        try {
            JsonElement element = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                return value.isBlank() ? null : value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /* ---------- 以下为逐字节值拷贝（跳过）机制 ---------- */

    private static void copyValue(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
        if (firstByte == '"') {
            copyString(input, output);
            return;
        }
        if (firstByte == '{' || firstByte == '[') {
            copyComposite(input, firstByte, output);
            return;
        }
        if (isPrimitiveStart(firstByte)) {
            copyPrimitive(input, firstByte, output);
            return;
        }
        throw new ProxyRequestException(400, "Request body is not a valid JSON object");
    }

    private static void copyString(PushbackInputStream input, OutputStream output) throws IOException {
        output.write('"');
        boolean escaped = false;
        while (true) {
            int b = input.read();
            if (b < 0) {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            output.write(b);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (b == '\\') {
                escaped = true;
                continue;
            }
            if (b == '"') {
                return;
            }
        }
    }

    private static void copyComposite(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
        int objectDepth = firstByte == '{' ? 1 : 0;
        int arrayDepth = firstByte == '[' ? 1 : 0;
        boolean inString = false;
        boolean escaped = false;
        output.write(firstByte);
        while (objectDepth > 0 || arrayDepth > 0) {
            int b = input.read();
            if (b < 0) {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            output.write(b);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
                continue;
            }
            if (b == '"') {
                inString = true;
            } else if (b == '{') {
                objectDepth++;
            } else if (b == '}') {
                objectDepth--;
            } else if (b == '[') {
                arrayDepth++;
            } else if (b == ']') {
                arrayDepth--;
            }
        }
    }

    private static void copyPrimitive(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
        output.write(firstByte);
        while (true) {
            int b = input.read();
            if (b < 0) {
                return;
            }
            if (isValueTerminator(b)) {
                input.unread(b);
                return;
            }
            output.write(b);
        }
    }

    private static boolean isPrimitiveStart(int value) {
        return value == 't' || value == 'f' || value == 'n' || value == '-' || (value >= '0' && value <= '9');
    }

    private static boolean isValueTerminator(int value) {
        return value == ',' || value == '}' || value == ']' || value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static int nextNonWhitespace(PushbackInputStream input) throws IOException {
        while (true) {
            int b = input.read();
            if (b < 0) {
                return -1;
            }
            // JSON 空白均为 ASCII，逐字节判断即可
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return b;
            }
        }
    }

    private static String readJsonString(PushbackInputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('"');
        boolean escaped = false;
        while (true) {
            int b = input.read();
            if (b < 0) {
                throw new ProxyRequestException(400, "Request body is not a valid JSON object");
            }
            out.write(b);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (b == '\\') {
                escaped = true;
                continue;
            }
            if (b == '"') {
                break;
            }
        }
        try {
            return JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsString();
        } catch (Exception e) {
            throw new ProxyRequestException(400, "Request body is not a valid JSON object");
        }
    }

    /** 只计数的丢弃流：超过 maxBytes 抛 413。 */
    private static class CountingOutputStream extends OutputStream {
        private final long maxBytes;
        private long count;

        CountingOutputStream(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            if (++count > maxBytes) {
                throw new ProxyRequestException(413, "Request body exceeds proxy limit of " + maxBytes + " bytes");
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count += len;
            if (count > maxBytes) {
                throw new ProxyRequestException(413, "Request body exceeds proxy limit of " + maxBytes + " bytes");
            }
        }
    }

    /** 带容量上限的包装流，超限抛 IllegalStateException。 */
    private static class LimitedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private int count;

        LimitedOutputStream(ByteArrayOutputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(int b) {
            if (++count > limit) {
                throw new IllegalStateException("capture limit exceeded");
            }
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
            if (count > limit) {
                throw new IllegalStateException("capture limit exceeded");
            }
            delegate.write(b, off, len);
        }
    }
}
