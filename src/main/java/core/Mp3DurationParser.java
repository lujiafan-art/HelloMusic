package core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 纯 Java MP3 时长解析器
 * 通过解析 MP3 帧头和比特率计算时长
 */
public class Mp3DurationParser {

	// MPEG 版本
	private static final int MPEG_VERSION_1 = 1;
	private static final int MPEG_VERSION_2 = 2;
	private static final int MPEG_VERSION_25 = 25;

	// 比特率表 [版本][层][比特率索引]
	private static final int[][][] BITRATES = {
			// MPEG 1
			{
					// Layer 1
					{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
					// Layer 2
					{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
					// Layer 3
					{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
			},
			// MPEG 2 / 2.5
			{
					// Layer 1
					{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
					// Layer 2 & 3
					{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
			}
	};

	// 采样率表 [版本][采样率索引]
	private static final int[][] SAMPLE_RATES = {
			// MPEG 1
			{44100, 48000, 32000, 0},
			// MPEG 2
			{22050, 24000, 16000, 0},
			// MPEG 2.5
			{11025, 12000, 8000, 0}
	};

	/**
	 * 解析 MP3 文件时长（秒）
	 * @param file MP3 文件
	 * @return 时长（秒），解析失败返回 0
	 */
	public static int getDuration(File file) {
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long fileLength = raf.length();

			// 跳过 ID3v2 标签（如果有）
			long audioStart = skipId3v2(raf);
			long audioLength = fileLength - audioStart;

			// 如果末尾有 ID3v1 标签，减去 128 字节
			if (hasId3v1(file)) {
				audioLength -= 128;
			}

			// 扫描第一个有效的 MP3 帧
			Mp3Frame firstFrame = findFirstFrame(raf, audioStart);
			if (firstFrame == null) {
				return 0;
			}

			// 计算时长
			// 公式：时长 = 音频数据长度 / 比特率（字节/秒）
			double bitrateBps = firstFrame.bitrate * 1000.0 / 8.0; // 转换为字节/秒
			double duration = audioLength / bitrateBps;

			return (int) Math.round(duration);
		} catch (IOException e) {
			return 0;
		}
	}

	/**
	 * 跳过 ID3v2 标签，返回音频数据起始位置
	 */
	private static long skipId3v2(RandomAccessFile raf) throws IOException {
		raf.seek(0);
		byte[] header = new byte[10];
		if (raf.read(header) != 10) {
			return 0;
		}

		// 检查 ID3v2 标识
		if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
			// 计算 ID3v2 大小（syncsafe integer）
			int size = ((header[6] & 0x7F) << 21)
					| ((header[7] & 0x7F) << 14)
					| ((header[8] & 0x7F) << 7)
					| (header[9] & 0x7F);
			return 10 + size;
		}
		return 0;
	}

	/**
	 * 检查是否有 ID3v1 标签
	 */
	private static boolean hasId3v1(File file) {
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long len = raf.length();
			if (len < 128) return false;
			raf.seek(len - 128);
			byte[] tag = new byte[3];
			raf.read(tag);
			return tag[0] == 'T' && tag[1] == 'A' && tag[2] == 'G';
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * 查找第一个有效的 MP3 帧
	 */
	private static Mp3Frame findFirstFrame(RandomAccessFile raf, long startPos) throws IOException {
		raf.seek(startPos);
		long maxScan = Math.min(startPos + 65536, raf.length()); // 最多扫描 64KB
		long pos = startPos;

		while (pos < maxScan - 4) {
			raf.seek(pos);
			int b1 = raf.read();
			int b2 = raf.read();
			int b3 = raf.read();
			int b4 = raf.read();

			// 检查帧同步（11 位为 1）
			if (b1 == 0xFF && (b2 & 0xE0) == 0xE0) {
				Mp3Frame frame = parseFrameHeader(b1, b2, b3, b4);
				if (frame != null) {
					return frame;
				}
			}
			pos++;
		}
		return null;
	}

	/**
	 * 解析帧头
	 */
	private static Mp3Frame parseFrameHeader(int b1, int b2, int b3, int b4) {
		// MPEG 版本
		int versionBits = (b2 >> 3) & 0x03;
		int version;
		switch (versionBits) {
			case 0: version = MPEG_VERSION_25; break;
			case 1: version = 0; return null; // 保留
			case 2: version = MPEG_VERSION_2; break;
			case 3: version = MPEG_VERSION_1; break;
			default: return null;
		}

		// Layer
		int layerBits = (b2 >> 1) & 0x03;
		int layer;
		switch (layerBits) {
			case 0: return null; // 保留
			case 1: layer = 3; break;
			case 2: layer = 2; break;
			case 3: layer = 1; break;
			default: return null;
		}

		// 比特率索引
		int bitrateIndex = (b3 >> 4) & 0x0F;
		if (bitrateIndex == 0 || bitrateIndex == 15) return null; // 无效

		// 采样率索引
		int sampleRateIndex = (b3 >> 2) & 0x03;
		if (sampleRateIndex == 3) return null; // 保留

		// 计算比特率
		int bitrate;
		int versionIndex = (version == MPEG_VERSION_1) ? 0 : 1;
		int layerIndex;
		if (version == MPEG_VERSION_1) {
			layerIndex = layer - 1; // 1→0, 2→1, 3→2
		} else {
			layerIndex = (layer == 1) ? 0 : 1; // Layer1→0, Layer2/3→1
		}
		bitrate = BITRATES[versionIndex][layerIndex][bitrateIndex];
		if (bitrate == 0) return null;

		// 计算采样率
		int sampleRate;
		int versionForSampleRate;
		if (version == MPEG_VERSION_1) versionForSampleRate = 0;
		else if (version == MPEG_VERSION_2) versionForSampleRate = 1;
		else versionForSampleRate = 2;
		sampleRate = SAMPLE_RATES[versionForSampleRate][sampleRateIndex];
		if (sampleRate == 0) return null;

		return new Mp3Frame(bitrate, sampleRate, layer, version);
	}

	/**
	 * MP3 帧信息
	 */
	private static class Mp3Frame {
		final int bitrate;    // kbps
		final int sampleRate; // Hz
		final int layer;
		final int version;

		Mp3Frame(int bitrate, int sampleRate, int layer, int version) {
			this.bitrate = bitrate;
			this.sampleRate = sampleRate;
			this.layer = layer;
			this.version = version;
		}
	}
}