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

import java.util.HashMap;
import java.util.Map;

/**
 * 播放进度快照组装：把「当前歌曲元数据」拼成前端可用的 Map。
 *
 * <p>纯无状态映射（{@code PlaybackSession} + {@code MusicFile} → {@code Map}），从
 * {@link PlaybackService} 拆出，避免「组装 DTO」与「控制播放 / 管理队列」混在一起。</p>
 *
 * <p><b>位置归客户端。</b>服务端不持有时钟、不推算「现在播到第几秒」，因此本类只返回
 * 歌曲元数据（songId / title / artist / album / duration）与播放模式，不返回任何实时
 * 位置（position / percent / isPlaying / serverTime）。没有客户端上报通道，故 {@code status}
 * 诚实地返回 {@code "unknown"}——表达「服务器不知道位置」。</p>
 */
public class ProgressMapper {

    /**
     * 把当前歌曲的元数据拼成前端可用的进度快照。
     *
     * <p>{@code sessionId} 无条件写入，使「空队列」与「有当前歌曲」两种快照都带同一会话标识，
     * 前端不必为多种 empty 形状分别解析。</p>
     */
    public Map<String, Object> buildProgress(String sessionId, PlaybackSession session, MusicFile current) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("sessionId", sessionId);
        if (current == null) {
            progress.put("status", "empty");
            return progress;
        }

        MusicMetadata meta = current.getMetadata();
        progress.put("status", "unknown");
        progress.put("songId", current.getId());
        // 显示名统一走 MusicFile.displayTitle()/displayArtist()：元数据优先、占位符
        // 「Unknown Title/Artist」视为缺失、标题回退文件名（README 约定「显示名唯一实现」）。
        progress.put("title", current.displayTitle());
        progress.put("artist", current.displayArtist());
        progress.put("album", meta != null ? meta.getAlbum() : "");
        progress.put("duration", (int) Math.round(session.getDurationMillis() / 1000.0));
        progress.put("playMode", session.getPlayMode().getDisplayName());
        return progress;
    }
}
