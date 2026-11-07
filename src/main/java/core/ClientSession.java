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

/**
 * 一个「前端客户端」对应的播放会话：绑定独立的播放状态（{@link PlaybackSession}），
 * 并维护心跳时间戳与客户端网络信息，用于服务端判断会话是否存活。
 *
 * <ul>
 *   <li>普通前端通过 {@code connect} 命令创建，拿到 {@code sessionId}；</li>
 *   <li>之后每 {@code heartbeatIntervalMs} 发送一次心跳（{@link #touch()}）以保持存活；</li>
 *   <li>服务端定时扫描：心跳超时记为 stale（仅记录日志），超过删除阈值则结束并移除该会话。</li>
 * </ul>
 *
 * 起始会话（数字 ID {@code "0"}）在尚未被真实客户端占用前，作为「未带 {@code sessionId}」
 * 与「兼容模式（enableHeartbeat=0）」的兼容兜底，不参与心跳治理（永不因超时暂停或删除）。
 */
public class ClientSession {

    private final String sessionId;
    private final PlaybackSession playback;
    private final String clientIp;     // 客户端自报的 IP（本机为 127.0.0.1，而非 localhost）
    private final String remoteAddr;   // 服务端从请求中观测到的真实对端地址
    private final long createEpoch;
    // volatile：心跳线程（touch）与巡检线程（inspectSessions 读）分属不同线程，
    // 无同步屏障的话巡检可能读到陈旧时间戳，把「刚心跳过」的会话误判为超时。
    private volatile long lastHeartbeatEpoch;
    private volatile boolean connected;

    public ClientSession(String sessionId, String clientIp, String remoteAddr) {
        this.sessionId = sessionId;
        this.playback = new PlaybackSession();
        this.clientIp = clientIp;
        this.remoteAddr = remoteAddr;
        long now = System.currentTimeMillis();
        this.createEpoch = now;
        this.lastHeartbeatEpoch = now;
        this.connected = true;
    }

    public String getSessionId() {
        return sessionId;
    }

    public PlaybackSession getPlayback() {
        return playback;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public long getCreateEpoch() {
        return createEpoch;
    }

    public long getLastHeartbeatEpoch() {
        return lastHeartbeatEpoch;
    }

    public boolean isConnected() {
        return connected;
    }

    /** 客户端上报一次心跳：刷新「最后存活时间」。 */
    public void touch() {
        this.lastHeartbeatEpoch = System.currentTimeMillis();
        this.connected = true;
    }

    /** 服务端因超时主动结束会话（不再接受心跳）。 */
    public void markEnded() {
        this.connected = false;
    }
}
