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
 * 播放进度快照组装：把会话的播放状态与歌曲元数据拼成前端进度条 / 「正在播放」可用的 Map。
 *
 * <p>纯无状态映射（{@code PlaybackSession} + {@code MusicFile} → {@code Map}），从
 * {@link PlaybackService} 拆出，避免「组装 DTO」与「控制播放 / 管理队列」混在一起。</p>
 */
public class ProgressMapper {

    /**
     * 把会话的播放状态与歌曲元数据拼成前端可用的进度快照。
     *
     * <p>{@code sessionId} 无条件写入，使「空队列」与「正常播放」两种快照都带同一会话标识，
     * 前端不必为多种 empty 形状（此前 {@code PlaybackEngine.getProgress} 手工构造的 empty 与
     * 本方法内部的 empty 形状不一致）分别解析。</p>
     */
    public Map<String, Object> buildProgress(String sessionId, PlaybackSession session, MusicFile current) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("sessionId", sessionId);
        if (current == null) {
            progress.put("status", "empty");
            // 空队列也带上同步对账字段，与非空快照的形状保持一致
            progress.put("lastSyncedAt", session.getLastClientSyncEpochMillis());
            progress.put("syncDueAt", session.getLastClientSyncEpochMillis() + SessionRegistry.CLIENT_SYNC_INTERVAL_MS);
            return progress;
        }

        long durationMillis = session.getDurationMillis();
        long positionMillis = session.getPositionMillis();
        double durationSeconds = durationMillis / 1000.0;
        double positionSeconds = positionMillis / 1000.0;
        boolean ended = durationMillis > 0 && positionMillis >= durationMillis;
        // isPlaying 与 status 必须自洽：进度已到末尾时视为「已结束」，不再报告 playing。
        // （buildProgress 本身不结算 stopIfEnded，故这里做一次「仅快照层面」的收敛，避免
        //   返回 status="ended" 却 isPlaying=true 的矛盾状态。）
        boolean isPlaying = session.isPlaying() && !ended;
        String status = ended ? "ended" : (isPlaying ? "playing" : "paused");

        MusicMetadata meta = current.getMetadata();
        progress.put("status", status);
        progress.put("songId", current.getId());
        progress.put("title", meta != null ? meta.getTitle() : current.getFileName());
        progress.put("artist", meta != null ? meta.getArtist() : "");
        progress.put("album", meta != null ? meta.getAlbum() : "");
        progress.put("position", Math.round(positionSeconds * 10) / 10.0); // 保留 1 位小数
        progress.put("duration", (int) Math.round(durationSeconds));
        progress.put("isPlaying", isPlaying);
        progress.put("percent", durationMillis > 0
                ? Math.min(100.0, positionMillis * 100.0 / durationMillis)
                : 0.0);
        progress.put("playMode", session.getPlayMode().getDisplayName());
        // 同步对账信息：客户端应在此窗口内再来同步一次，否则服务端记日志
        progress.put("lastSyncedAt", session.getLastClientSyncEpochMillis());
        progress.put("syncDueAt", session.getLastClientSyncEpochMillis() + SessionRegistry.CLIENT_SYNC_INTERVAL_MS);
        return progress;
    }
}
