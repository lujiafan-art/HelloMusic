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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 命令分发器：控制面的唯一入口，替代原先的 4 个 HTTP Controller。
 *
 * <p>请求是一个 JSON 对象，必含 {@code "cmd"} 字段标识命令，其余字段为该命令的参数；响应是一个
 * JSON 对象，成功为 {@code {"ok": true, "data": ...}}，失败为
 * {@code {"ok": false, "error": "...", "status": <HTTP 语义码>}}。{@code status} 沿用原 REST
 * 的状态码语义（400 参数非法 / 404 不存在 / 409 冲突），便于客户端按统一契约处理。传输层是否加
 * 行分隔（Unix Socket 的 JSON 行协议）或映射为 HTTP 状态码（{@link HttpGateway}）由调用方决定。</p>
 *
 * <p>命令清单与原 HTTP 端点一一对应（除音频流/封面/歌词三处二进制数据面由
 * {@link HttpGateway} 承担）。本类与传输层无关：既被本机 Unix Socket（{@link UnixSocketServer}）
 * 直接调用，也被 HTTP 网关的 {@code POST /control}（TCP 包装）调用。</p>
 * <ul>
 *   <li>会话：{@code connect} / {@code heartbeat} / {@code disconnect} / {@code session.status}</li>
 *   <li>播放：{@code mode} / {@code mode.switch} / {@code mode.set} / {@code current} /
 *       {@code prepare} / {@code start} / {@code play} / {@code pause} / {@code seek} /
 *       {@code progress} / {@code sync.ack} / {@code next} / {@code previous}</li>
 *   <li>队列：{@code load.all} / {@code select} / {@code load} / {@code load.playlist} /
 *       {@code queue} / {@code queue.clear} / {@code queue.add} / {@code queue.add.batch}</li>
 *   <li>音乐库：{@code music} / {@code music.get} / {@code search} / {@code artist} / {@code artists}</li>
 *   <li>歌单：{@code playlists} / {@code playlist.get} / {@code playlist.create} /
 *       {@code playlist.update} / {@code playlist.delete} / {@code playlist.add} /
 *       {@code playlist.add.batch} / {@code playlist.remove} / {@code playlist.clear} /
 *       {@code playlist.reorder}</li>
 *   <li>系统：{@code stats} / {@code scan} / {@code broadcast} / {@code config} /
 *       {@code config.set} / {@code info}</li>
 * </ul>
 */
public class CommandDispatcher {

    private final PlaybackService playbackService;
    private final PlaylistService playlistService;
    private final MusicScannerService scannerService;
    private final BroadcastService broadcastService;
    private final ConfigManager configManager;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    /** 异步任务（扫描 / 配置变更后的重扫）；为 {@code null}（测试）时退化为同步执行。 */
    private final ExecutorService executor;

    public CommandDispatcher(PlaybackService playbackService,
                             PlaylistService playlistService,
                             MusicScannerService scannerService,
                             BroadcastService broadcastService,
                             ConfigManager configManager,
                             ObjectMapper objectMapper,
                             Validator validator,
                             ExecutorService executor) {
        this.playbackService = playbackService;
        this.playlistService = playlistService;
        this.scannerService = scannerService;
        this.broadcastService = broadcastService;
        this.configManager = configManager;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.executor = executor;
    }

    /**
     * 分发一条命令，返回可序列化为 JSON 行的响应 Map。
     *
     * @param request 已解析的请求 JSON（含 {@code cmd} 字段）
     * @return 响应 Map（恒非 {@code null}）
     */
    public Map<String, Object> dispatch(JsonNode request) {
        String cmd = text(request, "cmd");
        if (cmd == null) {
            return error(400, "缺少 cmd 字段");
        }
        try {
            Map<String, Object> data = switch (cmd) {
                // ===== 会话 =====
                case "connect" -> connect(request);
                case "heartbeat" -> heartbeat(request);
                case "disconnect" -> disconnect(request);
                case "session.status" -> sessionStatus(request);

                // ===== 播放模式 =====
                case "mode" -> mode(request);
                case "mode.switch" -> switchMode(request);
                case "mode.set" -> setMode(request);

                // ===== 播放控制 =====
                case "current" -> current(request);
                case "prepare" -> prepare(request);
                case "start" -> start(request);
                case "play" -> play(request);
                case "pause" -> pause(request);
                case "seek" -> seek(request);
                case "progress" -> progress(request);
                case "sync.ack" -> syncAck(request);
                case "next" -> next(request);
                case "previous" -> previous(request);

                // ===== 队列 =====
                case "load.all" -> loadAll(request);
                case "select" -> select(request);
                case "load" -> loadPlaylist(request);
                case "load.playlist" -> loadPlaylistById(request);
                case "queue" -> queueInfo(request);
                case "queue.clear" -> clearQueue(request);
                case "queue.add" -> addToQueue(request);
                case "queue.add.batch" -> addBatchToQueue(request);

                // ===== 音乐库 =====
                case "music" -> ok(scannerService.getAllMusic());
                case "music.get" -> musicGet(request);
                case "search" -> search(request);
                case "artist" -> byArtist(request);
                case "artists" -> ok(scannerService.getArtists());

                // ===== 歌单 =====
                case "playlists" -> ok(playlistService.getAllPlaylists());
                case "playlist.get" -> playlistGet(request);
                case "playlist.create" -> playlistCreate(request);
                case "playlist.update" -> playlistUpdate(request);
                case "playlist.delete" -> playlistDelete(request);
                case "playlist.add" -> playlistAdd(request);
                case "playlist.add.batch" -> playlistAddBatch(request);
                case "playlist.remove" -> playlistRemove(request);
                case "playlist.clear" -> playlistClear(request);
                case "playlist.reorder" -> playlistReorder(request);

                // ===== 系统 =====
                case "stats" -> stats();
                case "scan" -> scan();
                case "broadcast" -> broadcast();
                case "config" -> ok(configManager.getConfig());
                case "config.set" -> setConfig(request);
                case "info" -> info();

                default -> error(400, "未知命令: " + cmd);
            };
            return data;
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (RuntimeException e) {
            return error(500, "服务端错误: " + e.getMessage());
        }
    }

    // ===== 会话 =====

    private Map<String, Object> connect(JsonNode req) {
        String clientIp = text(req, "clientIp");
        // Unix Socket 连接无对端地址语义，统一记为 "unix"
        return ok(playbackService.connect(clientIp, "unix"));
    }

    private Map<String, Object> heartbeat(JsonNode req) {
        String sessionId = text(req, "sessionId");
        if (!playbackService.heartbeat(sessionId)) {
            return error(404, "unknown_session");
        }
        return ok(Map.of("status", "alive"));
    }

    private Map<String, Object> disconnect(JsonNode req) {
        String sessionId = text(req, "sessionId");
        if (!playbackService.disconnect(sessionId)) {
            return error(400, "cannot_disconnect");
        }
        return ok(Map.of("status", "disconnected"));
    }

    private Map<String, Object> sessionStatus(JsonNode req) {
        String sessionId = text(req, "sessionId");
        Map<String, Object> result = playbackService.sessionStatus(sessionId);
        if ("not_found".equals(result.get("status"))) {
            return error(404, "unknown_session");
        }
        return ok(result);
    }

    // ===== 播放模式 =====

    private Map<String, Object> mode(JsonNode req) {
        PlayMode mode = playbackService.getCurrentMode(text(req, "sessionId"));
        return ok(Map.of("mode", mode.name(), "displayName", mode.getDisplayName()));
    }

    private Map<String, Object> switchMode(JsonNode req) {
        PlayMode mode = playbackService.switchMode(text(req, "sessionId"));
        return ok(Map.of("mode", mode.name(), "displayName", mode.getDisplayName()));
    }

    private Map<String, Object> setMode(JsonNode req) {
        String modeName = text(req, "mode");
        PlayMode playMode;
        try {
            playMode = PlayMode.valueOf(modeName == null ? "" : modeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return error(400, "无效的播放模式");
        }
        PlayMode result = playbackService.setMode(playMode, text(req, "sessionId"));
        return ok(Map.of("mode", result.name(), "displayName", result.getDisplayName()));
    }

    // ===== 播放控制 =====

    private Map<String, Object> current(JsonNode req) {
        MusicFile current = playbackService.getCurrentSong(text(req, "sessionId"));
        if (current == null) {
            return ok(Map.of("status", "empty"));
        }
        return ok(current);
    }

    private Map<String, Object> prepare(JsonNode req) {
        String musicId = text(req, "musicId");
        Map<String, Object> ready = playbackService.prepare(musicId, text(req, "sessionId"));
        if (ready == null) {
            return error(400, "歌曲不存在");
        }
        return ok(ready);
    }

    private Map<String, Object> start(JsonNode req) {
        Map<String, Object> started = playbackService.start(text(req, "sessionId"));
        if (started == null) {
            return error(409, "尚未准备（请先调用 prepare）");
        }
        return ok(started);
    }

    private Map<String, Object> play(JsonNode req) {
        Integer duration = integer(req, "duration");
        Map<String, Object> progress = playbackService.play(duration, text(req, "sessionId"));
        if (progress == null) {
            return ok(Map.of("status", "empty"));
        }
        return ok(progress);
    }

    private Map<String, Object> pause(JsonNode req) {
        Map<String, Object> result = playbackService.pause(text(req, "sessionId"));
        if (result == null) {
            return ok(Map.of("status", "empty"));
        }
        return ok(result);
    }

    private Map<String, Object> seek(JsonNode req) {
        Integer position = integer(req, "position");
        Map<String, Object> result = playbackService.seek(position == null ? 0 : position,
                text(req, "sessionId"));
        if (result == null) {
            return ok(Map.of("status", "empty"));
        }
        return ok(result);
    }

    private Map<String, Object> progress(JsonNode req) {
        return ok(playbackService.getProgress(text(req, "sessionId")));
    }

    private Map<String, Object> syncAck(JsonNode req) {
        playbackService.markClientSynced(text(req, "sessionId"));
        return ok(Map.of("status", "synced"));
    }

    private Map<String, Object> next(JsonNode req) {
        MusicFile next = playbackService.next(text(req, "sessionId"));
        if (next == null) {
            return ok(Map.of("status", "end"));
        }
        return ok(next);
    }

    private Map<String, Object> previous(JsonNode req) {
        MusicFile prev = playbackService.previous(text(req, "sessionId"));
        if (prev == null) {
            return ok(Map.of("status", "begin"));
        }
        return ok(prev);
    }

    // ===== 队列 =====

    private Map<String, Object> loadAll(JsonNode req) {
        String sessionId = text(req, "sessionId");
        playbackService.loadAllMusic(sessionId);
        return ok(Map.of("status", "loaded", "total", playbackService.getQueue(sessionId).size()));
    }

    private Map<String, Object> select(JsonNode req) {
        String musicId = text(req, "musicId");
        Map<String, Object> progress = playbackService.playSongById(musicId, text(req, "sessionId"));
        if (progress == null) {
            return error(400, "歌曲不存在");
        }
        return ok(progress);
    }

    private Map<String, Object> loadPlaylist(JsonNode req) {
        List<String> musicIds = stringList(req, "musicIds");
        if (musicIds == null || musicIds.isEmpty()) {
            return error(400, "音乐列表不能为空");
        }
        QueueManager.LoadResult result = playbackService.loadPlaylist(musicIds, text(req, "sessionId"));
        if (result.loaded() == 0) {
            return error(400, "没有可加载的歌曲：提交的 ID 均不在曲库中");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "loaded");
        body.put("total", result.loaded());
        if (result.invalid() > 0) {
            body.put("invalid", result.invalid());
        }
        if (result.dropped() > 0) {
            body.put("dropped", result.dropped());
        }
        return ok(body);
    }

    private Map<String, Object> loadPlaylistById(JsonNode req) {
        String id = text(req, "playlistId");
        String sessionId = text(req, "sessionId");
        if (!playbackService.loadPlaylistById(id, sessionId)) {
            return error(400, "歌单不存在或为空");
        }
        return ok(Map.of("status", "loaded", "total", playbackService.getQueue(sessionId).size()));
    }

    private Map<String, Object> queueInfo(JsonNode req) {
        return ok(playbackService.getQueueInfo(text(req, "sessionId")));
    }

    private Map<String, Object> clearQueue(JsonNode req) {
        playbackService.clearQueue(text(req, "sessionId"));
        return ok(Map.of("status", "cleared"));
    }

    private Map<String, Object> addToQueue(JsonNode req) {
        String musicId = text(req, "musicId");
        if (scannerService.getMusicById(musicId).isEmpty()) {
            return error(400, "歌曲不存在");
        }
        if (!playbackService.addToQueue(musicId, text(req, "sessionId"))) {
            return error(409, "队列已满");
        }
        return ok(Map.of("status", "added"));
    }

    private Map<String, Object> addBatchToQueue(JsonNode req) {
        List<String> musicIds = stringList(req, "musicIds");
        if (musicIds == null || musicIds.isEmpty()) {
            return error(400, "音乐列表不能为空");
        }
        int added = 0, invalid = 0, dropped = 0;
        for (String id : musicIds) {
            if (scannerService.getMusicById(id).isEmpty()) {
                invalid++;
            } else if (!playbackService.addToQueue(id, text(req, "sessionId"))) {
                dropped++;
            } else {
                added++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "added");
        body.put("count", added);
        if (invalid > 0) {
            body.put("invalid", invalid);
        }
        if (dropped > 0) {
            body.put("dropped", dropped);
        }
        return ok(body);
    }

    // ===== 音乐库 =====

    private Map<String, Object> musicGet(JsonNode req) {
        String id = text(req, "id");
        MusicFile musicFile = scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            return error(404, "歌曲不存在");
        }
        return ok(musicFile);
    }

    private Map<String, Object> search(JsonNode req) {
        String q = text(req, "q");
        if (q == null || q.trim().isEmpty()) {
            return error(400, "查询词不能为空");
        }
        return ok(scannerService.searchMusic(q.trim()));
    }

    private Map<String, Object> byArtist(JsonNode req) {
        return ok(scannerService.getMusicByArtist(text(req, "artist")));
    }

    // ===== 歌单 =====

    private Map<String, Object> playlistGet(JsonNode req) {
        Map<String, Object> playlist = playlistService.getPlaylistWithDetails(text(req, "id"));
        if (playlist == null) {
            return error(404, "歌单不存在");
        }
        return ok(playlist);
    }

    private Map<String, Object> playlistCreate(JsonNode req) {
        String name = text(req, "name");
        if (name == null || name.trim().isEmpty()) {
            return error(400, "歌单名不能为空");
        }
        return ok(playlistService.createPlaylist(name.trim(), text(req, "description")));
    }

    private Map<String, Object> playlistUpdate(JsonNode req) {
        Playlist playlist = playlistService.updatePlaylist(text(req, "id"),
                text(req, "name"), text(req, "description"));
        if (playlist == null) {
            return error(404, "歌单不存在");
        }
        return ok(playlist);
    }

    private Map<String, Object> playlistDelete(JsonNode req) {
        if (!playlistService.deletePlaylist(text(req, "id"))) {
            return error(404, "歌单不存在");
        }
        return ok(Map.of("status", "Deleted"));
    }

    private Map<String, Object> playlistAdd(JsonNode req) {
        PlaylistService.AddResult result = playlistService.addSongToPlaylist(
                text(req, "id"), text(req, "musicId"));
        return switch (result) {
            case ADDED -> ok(Map.of("status", "Added"));
            case PLAYLIST_NOT_FOUND -> error(404, "歌单不存在");
            case SONG_NOT_FOUND -> error(404, "歌曲不存在");
            case ALREADY_PRESENT -> error(409, "该歌曲已在歌单中");
        };
    }

    private Map<String, Object> playlistAddBatch(JsonNode req) {
        List<String> musicIds = stringList(req, "musicIds");
        if (musicIds == null || musicIds.isEmpty()) {
            return error(400, "musicIds 不能为空");
        }
        int added = playlistService.addSongsToPlaylist(text(req, "id"), musicIds);
        return ok(Map.of("added", added));
    }

    private Map<String, Object> playlistRemove(JsonNode req) {
        if (!playlistService.removeSongFromPlaylist(text(req, "id"), text(req, "musicId"))) {
            return error(404, "歌单不存在，或该歌曲不在歌单中");
        }
        return ok(Map.of("status", "Removed"));
    }

    private Map<String, Object> playlistClear(JsonNode req) {
        if (!playlistService.clearPlaylist(text(req, "id"))) {
            return error(404, "歌单不存在");
        }
        return ok(Map.of("status", "Cleared"));
    }

    private Map<String, Object> playlistReorder(JsonNode req) {
        List<String> musicIds = stringList(req, "musicIds");
        if (musicIds == null || musicIds.isEmpty()) {
            return error(400, "musicIds 不能为空");
        }
        if (!playlistService.reorderPlaylist(text(req, "id"), musicIds)) {
            return error(400, "重排序失败：ID 列表必须是该歌单的完整排列（长度一致、无重复、每个 ID 都在歌单中）");
        }
        return ok(Map.of("status", "Reordered"));
    }

    // ===== 系统 =====

    private Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalFiles", scannerService.getLibrarySize());
        stats.put("scanning", scannerService.isScanning());
        stats.put("lastScan", scannerService.getLastScanTime());
        stats.put("config", configManager.getConfig());
        return ok(stats);
    }

    private Map<String, Object> scan() {
        // 手动重扫：扫描可能耗时数分钟，交给后台线程执行、立即返回。
        // 若当前已有扫描在跑，本次请求会被合并为「待重扫」（当前扫描结束后立即补跑），
        // 不会被静默丢弃——据此如实告知客户端是「开始」还是「排队」。
        boolean alreadyScanning = scannerService.isScanning();
        runAsync(scannerService::scanLibrary);
        if (alreadyScanning) {
            return ok(Map.of("status", "queued",
                    "detail", "当前扫描进行中，将在其结束后立即重扫"));
        }
        return ok(Map.of("status", "started"));
    }

    private Map<String, Object> broadcast() {
        broadcastService.sendBroadcastNow();
        return ok(Map.of("status", "Broadcast sent"));
    }

    private Map<String, Object> setConfig(JsonNode req) {
        // 请求体的其余字段即配置 patch（Config 忽略未知字段，cmd 会被安全忽略）
        ConfigManager.Config patch = objectMapper.convertValue(req, ConfigManager.Config.class);
        if (patch == null) {
            return error(400, "配置体不能为空");
        }
        // Bean Validation：非法字段逐条返回原因（对齐原 HTTP 400 语义）
        List<Map<String, String>> details = new ArrayList<>();
        for (ConstraintViolation<ConfigManager.Config> v : validator.validate(patch)) {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("field", v.getPropertyPath().toString());
            detail.put("message", v.getMessage());
            details.add(detail);
        }
        if (!details.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "请求参数校验失败");
            body.put("details", details);
            return errorBody(400, body);
        }
        configManager.setConfig(patch);
        // 配置变更后异步重扫，避免阻塞命令响应
        runAsync(scannerService::scanLibrary);
        return ok(configManager.getConfig());
    }

    private Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "HelloMusic");
        info.put("version", "1.0.0");
        info.put("status", "running");
        info.put("librarySize", scannerService.getLibrarySize());
        return ok(info);
    }

    // ===== 响应构建辅助 =====

    /** 成功响应：{@code {"ok": true, "data": <业务结果>}}。 */
    private static Map<String, Object> ok(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("data", data);
        return r;
    }

    /** 失败响应：{@code {"ok": false, "error": "...", "status": <code>}}。 */
    private static Map<String, Object> error(int status, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("error", message);
        r.put("status", status);
        return r;
    }

    /** 带额外字段的失败响应（如校验失败附 details）。 */
    private static Map<String, Object> errorBody(int status, Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("status", status);
        r.putAll(body);
        return r;
    }

    /** 有后台线程池时异步执行，否则同步执行（测试路径）。 */
    private void runAsync(Runnable task) {
        if (executor != null) {
            executor.execute(task);
        } else {
            task.run();
        }
    }

    // ===== 参数读取辅助 =====

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asInt();
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            out.add(item.asText());
        }
        return out;
    }
}
