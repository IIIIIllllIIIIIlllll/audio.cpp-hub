package org.mark.audiocpp.hub.history;

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
import java.util.Arrays;
import java.util.Base64;

/**
 * 流式提取实例响应顶层 JSON 的 "audio" 字段：base64 内容边扫描边解码写入 wav 文件，
 * 大字段不进入 Java 字符串。逐字节扫描机制与 RequestModelExtractor 同源。
 */
public final class HistoryAudioExtractor {

    /** 提取结果：audioFound 是否拿到音频字段；bytes 为解码出的 WAV 字节数。 */
    public record Result(boolean audioFound, long bytes) {}

    private HistoryAudioExtractor() {}

    /** 扫描响应 JSON 文件，把顶层 "audio" 字符串解码落盘到 wavOut。 */
    public static Result extract(Path responseJson, Path wavOut) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(responseJson), 64 * 1024)) {
            return extract(in, wavOut);
        }
    }

    /** 同上，直接读流（调用方负责关闭）。 */
    static Result extract(InputStream input, Path wavOut) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(input, 1);
        if (nextNonWhitespace(stream) != '{') {
            throw new IOException("响应不是合法 JSON 对象");
        }
        boolean firstField = true;
        while (true) {
            int token = nextNonWhitespace(stream);
            if (token == '}') {
                break;
            }
            if (token < 0) {
                throw new IOException("响应不是合法 JSON 对象");
            }
            if (!firstField) {
                if (token != ',') {
                    throw new IOException("响应不是合法 JSON 对象");
                }
                token = nextNonWhitespace(stream);
            }
            firstField = false;
            if (token != '"') {
                throw new IOException("响应不是合法 JSON 对象");
            }
            String fieldName = readJsonString(stream);
            if (nextNonWhitespace(stream) != ':') {
                throw new IOException("响应不是合法 JSON 对象");
            }
            int valueStart = nextNonWhitespace(stream);
            if (valueStart < 0) {
                throw new IOException("响应不是合法 JSON 对象");
            }
            if ("audio".equals(fieldName) && valueStart == '"') {
                // 拿到音频即返回：响应其余部分无需校验
                long bytes = decodeStringBody(stream, wavOut);
                return new Result(true, bytes);
            }
            // 无关字段：原样跳过，不落内存
            copyValue(stream, valueStart);
        }
        return new Result(false, 0);
    }

    /** 字符串体内的 base64 边读边解码写入 wavOut，返回解码字节数。 */
    private static long decodeStringBody(PushbackInputStream stream, Path wavOut) throws IOException {
        if (wavOut.getParent() != null) {
            Files.createDirectories(wavOut.getParent());
        }
        try (OutputStream file = Files.newOutputStream(wavOut)) {
            Base64Sink sink = new Base64Sink(file);
            boolean escaped = false;
            while (true) {
                int b = stream.read();
                if (b < 0) {
                    throw new IOException("响应不是合法 JSON 对象");
                }
                if (escaped) {
                    // base64 字母表内无需转义的字符；\/ 这类写法原样收下（'/' 是合法 base64 字符）
                    sink.write(b);
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
                sink.write(b);
            }
            sink.finish();
        }
        return Files.size(wavOut);
    }

    /**
     * 流式 base64 解码器：只收字母表字符，攒满 4 的倍数即解码写盘（JDK 的 Decoder 只能包 InputStream）。
     */
    private static final class Base64Sink {
        private static final int CHUNK = 8192; // 4 的倍数

        private final OutputStream out;
        private final byte[] pending = new byte[CHUNK];
        private int len;

        Base64Sink(OutputStream out) {
            this.out = out;
        }

        void write(int b) throws IOException {
            char c = (char) b;
            if (!isBase64Char(c)) {
                return; // 空白等噪声直接忽略
            }
            pending[len++] = (byte) c;
            if (len == pending.length) {
                flush();
            }
        }

        void finish() throws IOException {
            if (len > 0) {
                flush();
            }
        }

        private void flush() throws IOException {
            try {
                out.write(Base64.getMimeDecoder().decode(Arrays.copyOf(pending, len)));
            } catch (IllegalArgumentException e) {
                throw new IOException("响应中的音频不是合法 base64");
            }
            len = 0;
        }

        private static boolean isBase64Char(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=';
        }
    }

    /* ---------- 以下为逐字节值跳过机制 ---------- */

    private static void copyValue(PushbackInputStream input, int firstByte) throws IOException {
        if (firstByte == '"') {
            copyString(input);
            return;
        }
        if (firstByte == '{' || firstByte == '[') {
            copyComposite(input, firstByte);
            return;
        }
        if (isPrimitiveStart(firstByte)) {
            copyPrimitive(input);
            return;
        }
        throw new IOException("响应不是合法 JSON 对象");
    }

    private static void copyString(PushbackInputStream input) throws IOException {
        boolean escaped = false;
        while (true) {
            int b = input.read();
            if (b < 0) {
                throw new IOException("响应不是合法 JSON 对象");
            }
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

    private static void copyComposite(PushbackInputStream input, int firstByte) throws IOException {
        int objectDepth = firstByte == '{' ? 1 : 0;
        int arrayDepth = firstByte == '[' ? 1 : 0;
        boolean inString = false;
        boolean escaped = false;
        while (objectDepth > 0 || arrayDepth > 0) {
            int b = input.read();
            if (b < 0) {
                throw new IOException("响应不是合法 JSON 对象");
            }
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

    private static void copyPrimitive(PushbackInputStream input) throws IOException {
        while (true) {
            int b = input.read();
            if (b < 0) {
                return;
            }
            if (isValueTerminator(b)) {
                input.unread(b);
                return;
            }
        }
    }

    private static boolean isPrimitiveStart(int value) {
        return value == 't' || value == 'f' || value == 'n' || value == '-' || (value >= '0' && value <= '9');
    }

    private static boolean isValueTerminator(int value) {
        return value == ',' || value == '}' || value == ']' || value == ' ' || value == '\t' || value == '\r'
                || value == '\n';
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
                throw new IOException("响应不是合法 JSON 对象");
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
            throw new IOException("响应不是合法 JSON 对象");
        }
    }
}
