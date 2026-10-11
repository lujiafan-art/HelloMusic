/*
 * HelloMusic - Local music server with UDP broadcast and REST API
 * Copyright (C) 2026 Lu Jiafan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 纯 Java MP3 时长解析器。
 *
 * <p>解析顺序：</p>
 * <ol>
 *   <li>优先读取首帧之后的 <b>Xing/Info</b>（大多数 VBR 编码器写入）或 <b>VBRI</b>
 *       （Fraunhofer 编码器写入）头部里的总帧数，按
 *       「帧数 × 每帧采样数 ÷ 采样率」得到精确时长 —— 对 VBR 文件同样准确；</li>
 *   <li>没有上述头部时，退回按首帧比特率做 CBR 估算（即原实现的行为）。</li>
 * </ol>
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

			if (hasId3v1(raf, fileLength)) {
				audioLength -= 128;
			}

			Mp3Frame firstFrame = findFirstFrame(raf, audioStart);
			if (firstFrame == null) {
				return 0;
			}

			// 1) 优先使用 Xing/Info/VBRI 头部的总帧数：VBR 文件也能算准
			Integer headerDuration = durationFromVbrHeader(raf, firstFrame);
			if (headerDuration != null && headerDuration > 0) {
				return headerDuration;
			}

			// 2) 兜底：按首帧比特率做 CBR 估算
			if (audioLength <= 0) {
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

	private static boolean hasId3v1(RandomAccessFile raf, long fileLength) {
		try {
			if (fileLength < 128) {
				return false;
			}
			raf.seek(fileLength - 128);
			byte[] tag = new byte[3];
			if (raf.read(tag) != 3) {
				return false;
			}
			return tag[0] == 'T' && tag[1] == 'A' && tag[2] == 'G';
		} catch (IOException e) {
			return false;
		}
	}

	private static Mp3Frame findFirstFrame(RandomAccessFile raf, long startPos) throws IOException {
		long maxScan = Math.min(startPos + 65536, raf.length());
		long pos = startPos;

		while (pos < maxScan - 4) {
			raf.seek(pos);
			int b1 = raf.read();
			int b2 = raf.read();
			int b3 = raf.read();
			int b4 = raf.read();

			if (b1 == 0xFF && (b2 & 0xE0) == 0xE0) {
				Mp3Frame frame = parseFrameHeader(pos, b2, b3, b4);
				if (frame != null) {
					return frame;
				}
			}
			pos++;
		}
		return null;
	}

	/**
	 * 解析帧头（只需要 b2, b3, b4），并记录帧在文件中的偏移。
	 */
	private static Mp3Frame parseFrameHeader(long offset, int b2, int b3, int b4) {
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

		// b2 的 bit0 为保护位：0 表示带 CRC（帧头后多 2 字节）
		boolean crcProtected = (b2 & 0x01) == 0;
		// b4 的 bit7-6 为声道模式：0=立体声, 1=联合立体声, 2=双声道, 3=单声道
		int channelMode = (b4 >> 6) & 0x03;

		return new Mp3Frame(offset, bitrate, sampleRate, layer, version, crcProtected, channelMode);
	}

	/** 尝试从 Xing/Info 或 VBRI 头部读取总帧数并换算时长；头部不存在时返回 {@code null}。 */
	private static Integer durationFromVbrHeader(RandomAccessFile raf, Mp3Frame frame) throws IOException {
		int samplesPerFrame = samplesPerFrame(frame.version(), frame.layer());
		if (samplesPerFrame <= 0 || frame.sampleRate() <= 0) {
			return null;
		}

		int sideInfoSize = sideInfoSize(frame.version(), frame.channelMode());
		// Xing/Info 标签紧跟「帧头 + 可选 CRC + side info」之后
		long xingPosition = frame.offset() + 4 + (frame.crcProtected() ? 2 : 0) + sideInfoSize;
		Long frames = readXingFrameCount(raf, xingPosition);
		if (frames == null) {
			// VBRI 标签由 Fraunhofer 编码器写在帧头之后固定 32 字节处
			frames = readVbriFrameCount(raf, frame.offset() + 36);
		}
		if (frames == null || frames <= 0) {
			return null;
		}

		return (int) Math.round(frames * (double) samplesPerFrame / frame.sampleRate());
	}

	/** 读取 Xing/Info 头部的帧数字段；标签不存在或未包含帧数时返回 {@code null}。 */
	private static Long readXingFrameCount(RandomAccessFile raf, long position) throws IOException {
		if (position < 0 || position + 12 > raf.length()) {
			return null;
		}
		raf.seek(position);
		byte[] buffer = new byte[12];
		if (raf.read(buffer) != 12) {
			return null;
		}

		boolean xing = buffer[0] == 'X' && buffer[1] == 'i' && buffer[2] == 'n' && buffer[3] == 'g';
		boolean info = buffer[0] == 'I' && buffer[1] == 'n' && buffer[2] == 'f' && buffer[3] == 'o';
		if (!xing && !info) {
			return null;
		}

		int flags = readInt(buffer, 4);
		if ((flags & 0x01) == 0) {
			return null;   // flags 未标记存在帧数字段
		}
		return (long) readInt(buffer, 8) & 0xFFFFFFFFL;
	}

	/** 读取 VBRI 头部的帧数字段；标签不存在时返回 {@code null}。 */
	private static Long readVbriFrameCount(RandomAccessFile raf, long position) throws IOException {
		if (position < 0 || position + 18 > raf.length()) {
			return null;
		}
		raf.seek(position);
		byte[] buffer = new byte[18];
		if (raf.read(buffer) != 18) {
			return null;
		}
		if (!(buffer[0] == 'V' && buffer[1] == 'B' && buffer[2] == 'R' && buffer[3] == 'I')) {
			return null;
		}
		// VBRI 布局："VBRI"(4) + version(2) + delay(2) + quality(2) + bytes(4) + frames(4)
		return (long) readInt(buffer, 14) & 0xFFFFFFFFL;
	}

	/** 大端序读取 4 字节整数。 */
	private static int readInt(byte[] buffer, int offset) {
		return ((buffer[offset] & 0xFF) << 24)
				| ((buffer[offset + 1] & 0xFF) << 16)
				| ((buffer[offset + 2] & 0xFF) << 8)
				| (buffer[offset + 3] & 0xFF);
	}

	/** side info 长度：MPEG1 单声道 17、立体声 32；MPEG2/2.5 单声道 9、立体声 17。 */
	private static int sideInfoSize(int version, int channelMode) {
		boolean mono = channelMode == 3;
		if (version == MPEG_VERSION_1) {
			return mono ? 17 : 32;
		}
		return mono ? 9 : 17;
	}

	/** 每帧采样数：Layer1 = 384，Layer2 = 1152，Layer3 在 MPEG1 下 1152、MPEG2/2.5 下 576。 */
	private static int samplesPerFrame(int version, int layer) {
		if (layer == 1) {
			return 384;
		}
		if (layer == 2) {
			return 1152;
		}
		return version == MPEG_VERSION_1 ? 1152 : 576;
	}

	/**
	 * MP3 帧信息（record 类型）
	 */
	private record Mp3Frame(long offset, int bitrate, int sampleRate, int layer, int version,
	                        boolean crcProtected, int channelMode) {}
}
