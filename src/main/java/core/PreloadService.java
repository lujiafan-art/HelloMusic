/*
 * HelloMusic - Local music server with Unix socket control plane and HTTP media stream
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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 歌曲首段预缓冲服务（流缓存）。
 *
 * <p>原本 preload 逻辑内嵌在数据面流式端点（HTTP 层），并被
 * {@link PlaybackService}（Service 层）反向调用，形成「Service 依赖传输层」的
 * 分层违规。现抽出为独立的 {@code PreloadService}：</p>
 *
 * <ul>
 *   <li>{@link PlaybackEngine#prepare} 在「准备」阶段调用 {@link #preload} 把歌曲开头载入内存；</li>
 *   <li>数据面流式端点（{@code /stream}）在流式播放时调用 {@link #getPreloaded} 取已缓存的首段，
 *       免首段读盘延迟。</li>
 * </ul>
 *
 * <p>两者共享同一个实例，因此预载与读取命中同一份有界 LRU 缓存。预缓冲是纯加速手段，
 * 读取失败或歌曲不存在都不影响正常播放（退化为普通流式）。</p>
 */
public class PreloadService {

    /** 预缓冲单首首段字节数（1MB）：「准备」阶段把歌曲开头载入内存以加速起始播放。 */
    private static final int PRELOAD_BYTES = 1 << 20;
    /** 有界 LRU 预缓冲缓存：避免预载块无限占用内存（最多保留 8 首的预载首段）。 */
    private final LinkedHashMap<String, byte[]> preloadCache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 8;
                }
            };

    private final MusicScannerService scannerService;
    /** 异步预载用的线程池；为 {@code null}（测试桩）时退化为同步预载。 */
    private final ExecutorService taskExecutor;

    public PreloadService(MusicScannerService scannerService, ExecutorService taskExecutor) {
        this.scannerService = scannerService;
        this.taskExecutor = taskExecutor;
    }

    /** 测试用：无 {@link ExecutorService} 时退化为同步预载，避免依赖线程池。 */
    PreloadService(MusicScannerService scannerService) {
        this(scannerService, null);
    }

    /**
     * 预载入指定歌曲开头的一部分字节到内存，加速客户端开始播放时的首段流式。
     * 这是「准备」阶段的加速手段：失败不影响后续正常播放（退化为普通流式）。
     *
     * <p>读盘在后台线程执行（{@link ExecutorService}），不阻塞 {@code prepare}、{@code select}
     * 的请求线程——否则大文件读 1MB 会让这两个端点的响应延迟被放大。预载完成后结果进缓存，
     * 未命中时 {@code streamMusic} 走普通流式。</p>
     */
    public void preload(String id) {
        MusicFile mf = scannerService.getMusicById(id).orElse(null);
        if (mf == null) {
            return;
        }
        File file = Paths.get(mf.getFilePath()).toFile();
        if (!file.exists()) {
            return;
        }
        Runnable task = () -> loadIntoCache(id, file);
        if (taskExecutor != null) {
            taskExecutor.execute(task);
        } else {
            task.run(); // 测试路径：同步执行，保证可观测
        }
    }

    /** 把歌曲首段读入内存并写进 LRU 缓存（后台线程执行）。 */
    private void loadIntoCache(String id, File file) {
        try {
            long want = Math.min(PRELOAD_BYTES, file.length());
            if (want <= 0) {
                return;
            }
            byte[] buf = new byte[(int) want];
            int total = 0, n;
            try (InputStream in = new FileInputStream(file)) {
                while (total < want && (n = in.read(buf, total, (int) (want - total))) > 0) {
                    total += n;
                }
            }
            if (total > 0) {
                byte[] data = (total == buf.length) ? buf : Arrays.copyOf(buf, total);
                synchronized (preloadCache) {
                    preloadCache.put(id, data);
                }
            }
        } catch (IOException ignored) {
            // 预缓冲是加速手段，失败可忽略，后续走普通流式
        }
    }

    /**
     * 取已预载的歌曲首段字节；未预载过返回 {@code null}。供流式端点做首段加速。
     *
     * @param id 歌曲 ID
     * @return 已缓存的首段字节，或 {@code null}
     */
    public byte[] getPreloaded(String id) {
        synchronized (preloadCache) {
            return preloadCache.get(id);
        }
    }
}
