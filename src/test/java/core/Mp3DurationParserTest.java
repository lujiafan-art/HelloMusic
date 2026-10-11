package core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖 {@link Mp3DurationParser}：合成最小可解析的 MP3 字节流，
 * 分别验证 CBR 估算路径与 Xing（VBR）头部路径。
 */
class Mp3DurationParserTest {

    /** MPEG1 Layer3 / 128 kbps / 44100 Hz / 立体声 / 无 CRC 的合法帧头。 */
    private static final int[] FRAME_HEADER = {0xFF, 0xFB, 0x90, 0x00};

    private static final int SAMPLE_RATE = 44_100;
    private static final int SAMPLES_PER_FRAME_MPEG1_L3 = 1152;
    /** MPEG1 立体声的 side info 为 32 字节，Xing 标签紧随其后。 */
    private static final int XING_OFFSET = 4 + 32;

    @Test
    void estimatesCbrDurationFromFirstFrame(@TempDir Path tempDir) throws Exception {
        // 128 kbps = 16000 字节/秒；160000 字节音频 → 恰好 10 秒
        File file = tempDir.resolve("cbr.mp3").toFile();
        writeFile(file, makeCbrMp3(160_000));

        int duration = Mp3DurationParser.getDuration(file);

        assertEquals(10, duration);
    }

    @Test
    void readsDurationFromXingHeader(@TempDir Path tempDir) throws Exception {
        // 声明 200 帧：200 * 1152 / 44100 ≈ 5.22 秒 → 四舍五入 5 秒
        int frameCount = 200;
        File file = tempDir.resolve("vbr.mp3").toFile();
        writeFile(file, makeMp3WithXingHeader(frameCount));

        int duration = Mp3DurationParser.getDuration(file);

        int expected = (int) Math.round(frameCount * (double) SAMPLES_PER_FRAME_MPEG1_L3 / SAMPLE_RATE);
        assertEquals(expected, duration);
        assertEquals(5, duration);
    }

    @Test
    void skipsId3v2TagBeforeScanningForFrames(@TempDir Path tempDir) throws Exception {
        byte[] id3v2 = makeId3v2Tag(1000);
        byte[] audio = makeCbrMp3(160_000);
        byte[] combined = new byte[id3v2.length + audio.length];
        System.arraycopy(id3v2, 0, combined, 0, id3v2.length);
        System.arraycopy(audio, 0, combined, id3v2.length, audio.length);

        File file = tempDir.resolve("with-id3.mp3").toFile();
        writeFile(file, combined);

        // 音频长度仍是 160000 字节，ID3v2 头被跳过，因此时长不变
        assertEquals(10, Mp3DurationParser.getDuration(file));
    }

    @Test
    void returnsZeroForUnparsableFile(@TempDir Path tempDir) throws Exception {
        File empty = tempDir.resolve("empty.mp3").toFile();
        try (RandomAccessFile raf = new RandomAccessFile(empty, "rw")) {
            raf.write(new byte[0]);
        }
        assertEquals(0, Mp3DurationParser.getDuration(empty));

        File garbage = tempDir.resolve("garbage.mp3").toFile();
        writeFile(garbage, "this is definitely not an mp3 stream".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0, Mp3DurationParser.getDuration(garbage));
    }

    private static void writeFile(File file, byte[] content) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.write(content);
        }
    }

    /** 帧头 + 全零音频数据（末尾不是 "TAG"，因此不会被误判为 ID3v1）。 */
    private static byte[] makeCbrMp3(int audioBytes) {
        byte[] data = new byte[audioBytes];
        for (int i = 0; i < FRAME_HEADER.length; i++) {
            data[i] = (byte) FRAME_HEADER[i];
        }
        return data;
    }

    /** 帧头 + side info 占位 + Xing 标签（含帧数）。 */
    private static byte[] makeMp3WithXingHeader(int frameCount) {
        byte[] data = new byte[20_000];
        for (int i = 0; i < FRAME_HEADER.length; i++) {
            data[i] = (byte) FRAME_HEADER[i];
        }
        data[XING_OFFSET] = 'X';
        data[XING_OFFSET + 1] = 'i';
        data[XING_OFFSET + 2] = 'n';
        data[XING_OFFSET + 3] = 'g';
        writeInt(data, XING_OFFSET + 4, 0x00000001);   // flags：包含帧数字段
        writeInt(data, XING_OFFSET + 8, frameCount);
        return data;
    }

    /** 构造一个合法的 ID3v2.3 标签头（10 字节）+ bodyLength 字节的内容。 */
    private static byte[] makeId3v2Tag(int bodyLength) {
        byte[] tag = new byte[10 + bodyLength];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;      // 版本
        tag[4] = 0;      // 修订号
        tag[5] = 0;      // flags
        // size 为 synchsafe 整数（每字节只用低 7 位）
        tag[6] = (byte) ((bodyLength >> 21) & 0x7F);
        tag[7] = (byte) ((bodyLength >> 14) & 0x7F);
        tag[8] = (byte) ((bodyLength >> 7) & 0x7F);
        tag[9] = (byte) (bodyLength & 0x7F);
        return tag;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }
}
