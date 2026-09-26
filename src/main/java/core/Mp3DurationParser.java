package core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 纯 Java MP3 时长解析器
 * 通过解析 MP3 帧头和比特率计算时长
 */
public class Mp3DurationParser {

	private static final int MPEG_VERSION_1 = 1;
	private static final int MPEG_VERSION_2 = 2;
	private static final int MPEG_VERSION_25 = 25;

	private static final int[][][] BITRATES = {
			{
					{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
					{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
					{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
			},
			{
					{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
					{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
			}
	};

	private static final int[][] SAMPLE_RATES = {
			{44100, 48000, 32000, 0},
			{22050, 24000, 16000, 0},
			{11025, 12000, 8000, 0}
	};

	public static int getDuration(File file) {
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long fileLength = raf.length();
			long audioStart = skipId3v2(raf);
			long audioLength = fileLength - audioStart;

			if (hasId3v1(file)) {
				audioLength -= 128;
			}

			Mp3Frame firstFrame = findFirstFrame(raf, audioStart);
			if (firstFrame == null) {
				return 0;
			}

			double bitrateBps = firstFrame.bitrate() * 1000.0 / 8.0;
			double duration = audioLength / bitrateBps;

			return (int) Math.round(duration);
		} catch (IOException e) {
			return 0;
		}
	}

	private static long skipId3v2(RandomAccessFile raf) throws IOException {
		raf.seek(0);
		byte[] header = new byte[10];
		if (raf.read(header) != 10) {
			return 0;
		}

		if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
			int size = ((header[6] & 0x7F) << 21)
					| ((header[7] & 0x7F) << 14)
					| ((header[8] & 0x7F) << 7)
					| (header[9] & 0x7F);
			return 10 + size;
		}
		return 0;
	}

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

	private static Mp3Frame findFirstFrame(RandomAccessFile raf, long startPos) throws IOException {
		raf.seek(startPos);
		long maxScan = Math.min(startPos + 65536, raf.length());
		long pos = startPos;

		while (pos < maxScan - 4) {
			raf.seek(pos);
			int b1 = raf.read();
			int b2 = raf.read();
			int b3 = raf.read();
			int b4 = raf.read();

			if (b1 == 0xFF && (b2 & 0xE0) == 0xE0) {
				Mp3Frame frame = parseFrameHeader(b2, b3, b4);
				if (frame != null) {
					return frame;
				}
			}
			pos++;
		}
		return null;
	}

	/**
	 * 解析帧头（只需要 b2, b3, b4）
	 */
	private static Mp3Frame parseFrameHeader(int b2, int b3, int b4) {
		int versionBits = (b2 >> 3) & 0x03;
		int version;
		switch (versionBits) {
			case 0: version = MPEG_VERSION_25; break;
			case 1: return null;
			case 2: version = MPEG_VERSION_2; break;
			case 3: version = MPEG_VERSION_1; break;
			default: return null;
		}

		int layerBits = (b2 >> 1) & 0x03;
		int layer;
		switch (layerBits) {
			case 0: return null;
			case 1: layer = 3; break;
			case 2: layer = 2; break;
			case 3: layer = 1; break;
			default: return null;
		}

		int bitrateIndex = (b3 >> 4) & 0x0F;
		if (bitrateIndex == 0 || bitrateIndex == 15) return null;

		int sampleRateIndex = (b3 >> 2) & 0x03;
		if (sampleRateIndex == 3) return null;

		int versionIndex = (version == MPEG_VERSION_1) ? 0 : 1;
		int layerIndex;
		if (version == MPEG_VERSION_1) {
			layerIndex = layer - 1;
		} else {
			layerIndex = (layer == 1) ? 0 : 1;
		}
		int bitrate = BITRATES[versionIndex][layerIndex][bitrateIndex];
		if (bitrate == 0) return null;

		int versionForSampleRate;
		if (version == MPEG_VERSION_1) versionForSampleRate = 0;
		else if (version == MPEG_VERSION_2) versionForSampleRate = 1;
		else versionForSampleRate = 2;

		int sampleRate = SAMPLE_RATES[versionForSampleRate][sampleRateIndex];
		if (sampleRate == 0) return null;

		return new Mp3Frame(bitrate, sampleRate, layer, version);
	}

	/**
	 * MP3 帧信息（record 类型）
	 */
	private record Mp3Frame(int bitrate, int sampleRate, int layer, int version) {}
}