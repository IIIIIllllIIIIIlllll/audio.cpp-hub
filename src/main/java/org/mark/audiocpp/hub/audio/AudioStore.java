package org.mark.audiocpp.hub.audio;

import org.mark.audiocpp.hub.util.UserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 上传音频存储与 WAV 头解析。只认标准 PCM WAV（audioFormat 1 或 3），不引入第三方依赖。
 */
public final class AudioStore {

    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    private static final Path UPLOAD_DIR = Path.of("data", "uploads");
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9-]{1,32}");

    private AudioStore() {}

    /** 保存上传的 WAV 到 data/uploads/<uuid8>.wav，返回音频信息。 */
    public static Map<String, Object> saveUpload(byte[] wav) throws IOException {
        WavInfo info = parseWav(wav);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(UPLOAD_DIR);
        Path path = UPLOAD_DIR.resolve(id + ".wav");
        Files.write(path, wav);
        return toMap(id, path.toAbsolutePath().toString(), info, wav.length);
    }

    /** 解析本地路径的 WAV 信息（"本地路径"Tab 用）。 */
    public static Map<String, Object> probe(String pathStr) throws IOException {
        Path path = Path.of(pathStr);
        if (!Files.isRegularFile(path)) {
            throw new UserException("FILE_NOT_FOUND", Map.of("path", pathStr), "文件不存在: " + pathStr);
        }
        long size = Files.size(path);
        if (size > MAX_UPLOAD_BYTES) {
            throw new UserException("FILE_TOO_LARGE", Map.of("path", pathStr), "文件超过 50MB 上限: " + pathStr);
        }
        byte[] data = Files.readAllBytes(path);
        WavInfo info = parseWav(data);
        return toMap(null, path.toAbsolutePath().toString(), info, size);
    }

    /** 定位上传文件；id 不合法或文件不存在返回 null（防路径穿越）。 */
    public static Path uploadPath(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            return null;
        }
        Path path = UPLOAD_DIR.resolve(id + ".wav");
        return Files.isRegularFile(path) ? path : null;
    }

    /** RIFF/WAV 头解析：提取 fmt 与 data chunk。 */
    static WavInfo parseWav(byte[] data) throws IOException {
        if (data.length < 44
                || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            throw new UserException("NOT_WAV", "不是标准 RIFF/WAVE 文件");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int audioFormat = -1, channels = 0, sampleRate = 0, bitsPerSample = 0;
        long dataSize = -1;
        int offset = 12;
        while (offset + 8 <= data.length) {
            int chunkId = buf.getInt(offset);
            long chunkSize = Integer.toUnsignedLong(buf.getInt(offset + 4));
            if (chunkId == 0x20746D66) { // "fmt "
                if (offset + 8 + 16 > data.length) break;
                audioFormat = Short.toUnsignedInt(buf.getShort(offset + 8));
                channels = Short.toUnsignedInt(buf.getShort(offset + 10));
                sampleRate = buf.getInt(offset + 12);
                bitsPerSample = Short.toUnsignedInt(buf.getShort(offset + 22));
            } else if (chunkId == 0x61746164) { // "data"
                dataSize = Math.min(chunkSize, data.length - (offset + 8L));
            }
            // chunk 按 2 字节对齐
            long next = offset + 8L + chunkSize + (chunkSize % 2);
            if (next <= offset || next > Integer.MAX_VALUE) break;
            offset = (int) next;
        }
        if (audioFormat == -1 || dataSize < 0) {
            throw new UserException("WAV_CHUNKS_MISSING", "WAV 中缺少 fmt 或 data chunk");
        }
        return buildInfo(audioFormat, channels, sampleRate, bitsPerSample, dataSize);
    }

    /**
     * 流式解析 WAV 头：逐 chunk 读头，命中 data 记大小即止，不整文件读入内存。
     * 用于操作历史的结果音频（可能几十 MB）。
     */
    public static WavInfo parseWav(Path path) throws IOException {
        long fileSize = Files.size(path);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 64 * 1024)) {
            byte[] magic = in.readNBytes(12);
            if (magic.length < 12
                    || magic[0] != 'R' || magic[1] != 'I' || magic[2] != 'F' || magic[3] != 'F'
                    || magic[8] != 'W' || magic[9] != 'A' || magic[10] != 'V' || magic[11] != 'E') {
                throw new UserException("NOT_WAV", "不是标准 RIFF/WAVE 文件");
            }
            int audioFormat = -1, channels = 0, sampleRate = 0, bitsPerSample = 0;
            long dataSize = -1;
            long position = 12;
            while (true) {
                byte[] head = in.readNBytes(8);
                if (head.length < 8) break;
                int chunkId = leInt(head, 0);
                long chunkSize = Integer.toUnsignedLong(leInt(head, 4));
                position += 8;
                if (chunkId == 0x20746D66) { // "fmt "
                    byte[] fmt = in.readNBytes(16);
                    if (fmt.length < 16) break;
                    position += 16;
                    audioFormat = Short.toUnsignedInt(leShort(fmt, 0));
                    channels = Short.toUnsignedInt(leShort(fmt, 2));
                    sampleRate = leInt(fmt, 4);
                    bitsPerSample = Short.toUnsignedInt(leShort(fmt, 14));
                    long rest = chunkSize - 16 + (chunkSize % 2);
                    if (rest > 0) {
                        skipFully(in, rest);
                        position += rest;
                    }
                } else if (chunkId == 0x61746164) { // "data"：内容无需读取
                    dataSize = Math.min(chunkSize, Math.max(0, fileSize - position));
                    break;
                } else {
                    long skip = chunkSize + (chunkSize % 2);
                    skipFully(in, skip);
                    position += skip;
                }
            }
            if (audioFormat == -1 || dataSize < 0) {
                throw new UserException("WAV_CHUNKS_MISSING", "WAV 中缺少 fmt 或 data chunk");
            }
            return buildInfo(audioFormat, channels, sampleRate, bitsPerSample, dataSize);
        }
    }

    /** 校验 fmt 参数并计算时长（两个 parseWav 共用的收尾逻辑）。 */
    private static WavInfo buildInfo(int audioFormat, int channels, int sampleRate, int bitsPerSample,
                                     long dataSize) {
        if (audioFormat != 1 && audioFormat != 3) {
            throw new UserException("WAV_NOT_PCM", Map.of("format", audioFormat),
                    "非 PCM WAV（audioFormat=" + audioFormat + "），仅支持 1(PCM int) 或 3(float)");
        }
        if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
            throw new UserException("WAV_FMT_INVALID", "WAV fmt 参数非法");
        }
        double bytesPerSec = sampleRate * (double) channels * bitsPerSample / 8.0;
        double durationSec = bytesPerSec > 0 ? dataSize / bytesPerSec : 0;
        return new WavInfo(sampleRate, channels, bitsPerSample, durationSec);
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static short leShort(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static Map<String, Object> toMap(String id, String absPath, WavInfo info, long sizeBytes) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (id != null) map.put("id", id);
        map.put("path", absPath);
        map.put("durationSec", Math.round(info.durationSec * 1000.0) / 1000.0);
        map.put("sampleRate", info.sampleRate);
        map.put("channels", info.channels);
        map.put("bitsPerSample", info.bitsPerSample);
        map.put("sizeBytes", sizeBytes);
        return map;
    }

    /** 解析结果。 */
    public static final class WavInfo {
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;
        public final double durationSec;

        WavInfo(int sampleRate, int channels, int bitsPerSample, double durationSec) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.durationSec = durationSec;
        }
    }
}
