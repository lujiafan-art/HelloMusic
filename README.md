# HelloMusic - 本地音乐服务器

[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://makeapullrequest.com)
[![AI Generated](https://img.shields.io/badge/AI-Generated-8A2BE2?logo=openai&logoColor=white)](https://github.com)

> 本项目由 AI 辅助生成 —— 基于深度求索（DeepSeek）AI 模型，通过自然语言对话完成完整项目开发。

扫描本机的音乐目录，提取标签与时长，在局域网里用 UDP 广播自我通告。**完全脱离 Spring**，纯 JDK 21 实现：**对外统一走 HTTP**，控制面同时提供两条**对等**通道 ——

- **控制面 · 双通道（对等）**：本机客户端通过 Unix domain socket 走 **JSON 行协议**（每请求一行 JSON、每响应一行 JSON），零 TCP/IP 协议栈、内核零拷贝、不占端口；局域网 / 远程客户端通过 **HTTP `POST /control`** 走**同一条命令分发链路**，命令集与权限完全一致、且**默认无鉴权**；
- **数据面 · HTTP**：浏览器 `<audio>` 与局域网播放器通过 JDK 内置 `com.sun.net.httpserver` 拉取**音频流 / 封面 / 歌词**三个只读媒体端点（支持 HTTP Range 拖动）。

控制面与数据面共用一个 HTTP 门（`com.sun.net.httpserver`），因此**对外就是一组 HTTP 端点**：`POST /control` + `GET /stream /cover /lyrics`。没有数据库、没有前端构建、没有外部服务依赖，`java -jar` 一条命令就能跑起来。⚠️ 注意：`POST /control` 网络可达且无鉴权，任何能访问该端口的客户端都能执行全部控制命令（详见[安全提示](#安全提示)）。

---

## 目录

- [架构总览](#架构总览)
- [特性](#特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [控制面命令](#控制面命令)
- [HTTP 端点](#http-端点)
- [网络广播](#网络广播)
- [播放模式](#播放模式)
- [运行时文件](#运行时文件)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [设计约定](#设计约定)
- [开发与测试](#开发与测试)
- [常见问题](#常见问题)
- [安全提示](#安全提示)
- [贡献指南](#贡献指南)
- [许可证](#许可证)
- [致谢](#致谢)

---

## 架构总览

```
  本机客户端 ──Unix Socket──▶┐   ┌─────────────────────────────────┐
  (JSON 行协议，仅本机)        ├─▶│  控制面（CommandDispatcher 路由）  │──▶ 播放/会话/队列/歌单/扫描/配置
                             │   │  · UnixSocketServer（本机）        │
  局域网/远程 ──HTTP──▶       │   │  · HttpGateway /control（无鉴权）  │
  (POST /control，无鉴权) ─────┘   └─────────────────────────────────┘

  浏览器 <audio> ──HTTP──▶  ┌──────────────────────────────┐
  (Range 拖动) ───────────▶ │  HttpGateway 数据面           │──▶ 音频字节 / 封面图 / 歌词文本
                             │  /stream /cover /lyrics       │
                             └──────────────────────────────┘
                                       │
                               UDP 广播（255.255.255.255 + 子网）
```

- **控制面**与**数据面**完全解耦：业务状态（会话、队列、播放、歌单、配置）只能通过 `CommandDispatcher` 修改；它同时被 Unix Socket（本机，JSON 行协议）与 HTTP `POST /control`（网络可达）两条**对等**传输层调用——HTTP 不是 Unix Socket 的「兼容包装」，而是能执行全部命令的完整控制入口。
- ⚠️ **风险提示**：HTTP `POST /control` 默认监听所有网卡、**无任何鉴权**，任何能访问该端口的客户端都能改配置、清队列、删歌单；Unix Socket 则仅限本机进程（由 socket 文件权限控制）。两者权限不对等，详见[安全提示](#安全提示)。
- 音频流、封面、歌词是「数据」，不涉及业务状态，因此留在 HTTP 数据面供浏览器直接消费。
- 两条控制通道与数据面共享同一套会话、队列与播放状态（同一个进程内的对象图）。

---

## 特性

| 功能 | 说明 |
| --- | --- |
| 自动扫描 | 递归扫描配置的音乐库目录，按扩展名过滤（默认 MP3、FLAC、WAV、M4A、OGG、MKV）；**跟随符号链接**——目录链接、文件链接均可收录，循环链接自动跳过 |
| 增量扫描 | 按「绝对路径 + 文件大小 + 修改时间」判断文件是否变过：没变的直接复用上次解析结果，只有新增或改动过的文件才会被重新读取 |
| 并行解析 | 目录遍历与指纹比对串行、元数据解析并行：线程数默认取 CPU 核数，可用 `scanThreads` 调整（设为 `1` 退回串行） |
| 稳定 ID | 用文件绝对路径生成 UUID（`nameUUIDFromBytes`），重启或重新扫描后 ID 不变 |
| 元数据提取 | 基于 jaudiotagger 读取标题、艺术家、专辑、流派、音轨号、年份，支持中文标签（UTF-8/GBK） |
| 元数据兜底 | 无标签或解析失败时回退为「艺术家 - 标题」文件名解析，字段填充 `Unknown Title/Artist/Album/Genre` |
| 时长解析 | 优先取标签时长；MP3 额外提供纯 Java 解析兜底，支持 CBR 估算与 Xing/Info、VBRI（VBR）头部 |
| 流式播放 | `/stream/{id}` 支持 HTTP Range，可拖动进度条，返回 `206 Partial Content` |
| 网络广播 | UDP 广播自我通告，同时发往 `255.255.255.255` 与本机子网广播地址 |
| 控制面命令 | 会话、播放、队列、音乐库、歌单、系统六组命令；本机走 Unix Socket 行协议、远程走 HTTP `POST /control` |
| Unix Socket + HTTP | 控制面双通道：本机经 Unix domain socket 走 JSON 行协议（零网络协议栈、不占端口），局域网/远程经 HTTP `POST /control` 访问同一条命令分发链路（**默认无鉴权**） |
| 播放列表 | 创建/改名/删除歌单，增删歌曲、批量添加、重排序，持久化到 `playlists.json` |
| 封面与歌词 | 按歌曲返回内嵌封面图（`/cover/{id}`，回退同目录同名图片）与歌词文本（`/lyrics/{id}`，回退同目录 `.lrc`） |
| 播放控制 | 正向、逆向、单曲循环、随机四种模式，可切换并自动持久化 |
| 播放队列 | 全部音乐入队、按歌单入队、单曲/批量追加、清空、查看队列状态 |
| 配置持久化 | 配置与上次播放模式写入 `hellomusic-config.json`，重启不丢 |
| 队列持久化 | 播放队列与播放位置写入 `hellomusic-config.json`，重启后自动恢复上次听歌现场 |
| 定时刷新 | 启动时同步扫描一次，之后按 `rescanInterval` 自动重扫（默认 5 分钟）；重扫是增量的，只会处理新增与改动过的文件，也可随时 `scan` 手动重扫 |
| SHUFFLE 历史 | 随机模式维护播放历史栈，使"上一首"真正回溯（而非重播当前） |
| 预缓冲加速 | "准备"阶段把歌曲开头 1MB 载入内存（有界 LRU 缓存），首段 Range 请求直接从内存返回，降低起始播放延迟 |
| 前端会话 / 心跳 | 每个客户端通过「连接」创建独立会话，服务端分配**数字 sessionId**（`0` 起始、递增，会话断开/超时后复用最小空闲数字），各自拥有独立队列；每 1 分钟上报心跳，服务端超时记为 stale（仅记录日志）、超过 3 分钟无响应则结束并删除会话，多端互不串台 |

---

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21+ | `pom.xml` 中 `maven.compiler.release=21`，低版本无法编译 |
| Maven | 3.6+ | 构建与测试 |

> 无需任何应用服务器或框架：HTTP 数据面用 JDK 内置 `com.sun.net.httpserver`，控制面用 JDK 内置 `ServerSocketChannel`（`StandardProtocolFamily.UNIX`）。

---

## 快速开始

```bash
git clone https://github.com/lujiafan-art/HelloMusic.git
cd HelloMusic
mvn clean package
java -jar target/HelloMusic.jar
```

启动后终端会打印横幅：

```
╔══════════════════════════════════════════════════════════════════╗
║                    HelloMusic Server Started                     ║
║                                                                  ║
║  Control (Unix Socket): ~/.local/share/HelloMusic/hellomusic.sock║
║  Control (HTTP): POST http://localhost:8080/control              ║
║  Media (HTTP): GET http://localhost:8080/stream/{id}             ║
║  Broadcast: UDP 9999                                             ║
╚══════════════════════════════════════════════════════════════════╝
```

**首次启动会自动完成三件事**：

1. 在当前工作目录生成 `hellomusic-config.json`，音乐库默认指向 `~/Music`；
2. 立即全量扫描一次音乐库（同步执行，库很大时启动会慢一些，日志会打印文件数与耗时；此后的重扫都是增量的，只处理有变化的文件）；
3. 拉起控制面（Unix Socket）与数据面（HTTP，默认 8080）、UDP 广播（默认 9999 / 每 30 秒一次）。

> ⚠️ **务必固定在同一目录启动。** 配置与歌单文件是相对**当前工作目录**（不是 classpath）读写的。在 A 目录启动过、又切到 B 目录启动，看起来就像"配置和歌单全丢了"。

### 在浏览器里播放

拿到歌曲 ID 后直接丢给 `<audio>` 即可，`Range` 已支持，进度条可以拖：

```html
<audio controls src="http://localhost:8080/stream/替换成你的歌曲ID"></audio>
```

### 客户端连接控制面

控制面有两条对等通道，命令协议完全一致（见[控制面命令](#控制面命令)）：

**远程 / 局域网（HTTP `POST /control`）** —— 用 `curl` 发普通 HTTP 请求即可（默认无鉴权）：

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data '{"cmd":"info"}' \
     http://localhost:8080/control
# {"ok":true,"data":{"name":"HelloMusic","version":"1.0.0","status":"running","librarySize":1250}}
```

**本机（Unix Socket，JSON 行协议）** —— 直接发一行 JSON、读回一行 JSON。注意：`curl --unix-socket` 会套 HTTP 帧，与行协议不兼容，请改用 `nc -U` / `socat` 或 Python：

```bash
printf '{"cmd":"info"}\n' | nc -U ~/.local/share/HelloMusic/hellomusic.sock
# {"ok":true,"data":{"name":"HelloMusic","version":"1.0.0","status":"running","librarySize":1250}}
```

Python 客户端（无第三方依赖）：

```python
import socket

s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect("/home/你的用户名/.local/share/HelloMusic/hellomusic.sock")
s.sendall(b'{"cmd":"info"}\n')
print(s.recv(4096).decode())
```

---

## 配置说明

配置文件为当前工作目录下的 `hellomusic-config.json`，首次启动自动生成。**这是唯一的配置文件**——服务器字段（端口 / 广播 / 扫描 / 扩展名）与用户选项（队列上限 / 自动保存 / 音乐目录 / 日志 / 心跳）都收在这里：

```json
{
  "serverPort" : 8080,
  "broadcastPort" : 9999,
  "broadcastInterval" : 30000,
  "scanThreads" : 8,
  "rescanInterval" : 300000,
  "supportedExtensions" : [ "mp3", "mkv", "flac", "wav", "m4a", "ogg" ],
  "serverName" : "HelloMusic",
  "maxQueueSize" : 200,
  "autoSaveQueue" : 0,
  "musicPaths" : [ "~/Music" ],
  "enableFileLog" : 0,
  "logPath" : "~/.local/share/HelloMusic/logs",
  "enableHeartbeat" : 1,
  "unixSocketPath" : "~/.local/share/HelloMusic/hellomusic.sock",
  "lastPlayMode" : "FORWARD",
  "playbackQueue" : [ "9f0c2c6e-...", "1a2b3c4d-..." ],
  "currentIndex" : 0
}
```

| 参数 | 类型 | 默认值 | 生效时机 | 说明 |
| --- | --- | --- | --- | --- |
| `serverPort` | int | `8080` | **需重启** | HTTP 服务端口（控制 `POST /control` + 数据面音频流 / 封面 / 歌词） |
| `broadcastPort` | int | `9999` | 立即 | UDP 广播端口 |
| `broadcastInterval` | int | `30000` | **需重启** | 广播间隔（毫秒） |
| `scanThreads` | int | CPU 核数 | 下次扫描 | 元数据解析线程数，取值 1-64；设为 `1` 退回串行解析 |
| `rescanInterval` | int | `300000` | **需重启** | 曲库自动重扫间隔（毫秒），默认 5 分钟；取值 10000-86400000 |
| `supportedExtensions` | String[] | `["mp3","mkv","flac","wav","m4a","ogg"]` | 下次扫描 | 参与扫描的文件扩展名（不含点号，忽略大小写） |
| `serverName` | String | `"HelloMusic"` | 立即 | 广播中通告的服务器名 |
| `maxQueueSize` | int | `200` | 加载 / 添加时 | 播放队列上限，超出部分丢弃；取值 1-100000 |
| `autoSaveQueue` | 0/1 | `0` | 切歌 / 加载时 | 是否把播放队列落盘，重启后恢复上次现场（0=否，1=是） |
| `musicPaths` | String[] | `["~/Music"]` | 下次扫描 | 扫描并读取音乐文件的目录，可写多个（支持 `~`），路径本身可为目录符号链接；**音乐库目录的唯一来源** |
| `enableFileLog` | 0/1 | `0` | **需重启** | 是否把日志**额外**写入文件（控制台始终保留；0=否，1=是） |
| `logPath` | String | `~/.local/share/HelloMusic/logs` | **需重启** | 日志目录，文件名格式 `日期-启动时间.txt` |
| `enableHeartbeat` | 0/1 | `1` | **需重启** | 是否启用心跳包（见下方说明） |
| `unixSocketPath` | String | `~/.local/share/HelloMusic/hellomusic.sock` | **需重启** | Unix Socket 文件路径，本机客户端通过它连接控制面 |
| `lastPlayMode` | String | `"FORWARD"` | — | 上次播放模式，**由程序自动写回**，不建议手改 |
| `playbackQueue` | String[] | `[]` | 切歌 / 加载时 | 播放队列（歌曲 ID 列表），**由程序自动写回**，不建议手改 |
| `currentIndex` | int | `0` | 切歌 / 加载时 | 当前播放位置（队列下标），**由程序自动写回**，不建议手改 |

### 配置项取值范围

`config.set` 命令会对提交的字段做校验，越界即返回 `400` 状态：

| 参数 | 取值范围 |
| --- | --- |
| `serverPort` / `broadcastPort` | 1 - 65535 |
| `broadcastInterval` | 1000 - 3600000（毫秒） |
| `scanThreads` | 1 - 64 |
| `rescanInterval` | 10000 - 86400000（毫秒） |
| `supportedExtensions` | 至少一个，元素不能是空字符串（不含点号） |
| `serverName` | 不能为空或纯空白 |
| `lastPlayMode` | `FORWARD` / `REVERSE` / `SINGLE` / `SHUFFLE`（忽略大小写） |
| `maxQueueSize` | 1 - 100000 |
| `autoSaveQueue` / `enableFileLog` / `enableHeartbeat` | 0 或 1 |
| `musicPaths` | 至少一个，元素不能是空字符串 |
| `logPath` / `unixSocketPath` | 不能为空或纯空白 |

读取配置文件时同样做一次体检：非法项**回退为默认值**并在启动日志里告警，不会让服务带着坏配置启动。

### 关于「是否启用心跳包」

- `1`（默认）：客户端每 60 秒上报心跳；服务端每前端一个独立会话，超时记为 stale（仅记录日志），超过 3 分钟无响应则删除该会话。
- `0`：不建立持续连接。后端**完全放弃会话治理**（不记录 stale、不删除），且**只有一个会话**——内部为起始会话 `0`，对外返回 `default` 作为会话别名；所有前端共用同一份队列，不支持多会话。

### 修改配置的两种方式

**方式一：直接编辑文件**（改完后重启，或触发一次 `scan` 重新扫描）

```bash
vim hellomusic-config.json
```

**方式二：控制面 `config.set` 命令** —— 局部更新，只提交要改的字段即可

```bash
# 只改服务器名，其余字段保持不变
curl -X POST -H 'Content-Type: application/json' \
     --data '{"cmd":"config.set","serverName":"客厅音乐"}' \
     http://localhost:8080/control
```

- **只覆盖提交了的字段**，未提交或传 `null` 的字段保持原值，因此不必先 `config` 再整体提交；
- **取值非法直接返回 `400`**，并逐字段说明原因，配置文件不会被写坏：

```json
{"ok":false,"status":400,"error":"请求参数校验失败","details":[{"field":"serverPort","message":"端口取值范围为 1-65535"}]}
```

提交合法后**异步**触发一次扫描（增量，只重新解析有变化的文件），命令立即返回、不会被扫描阻塞。

---

## 控制面命令

控制面命令是**一个 JSON 对象**（必含 `"cmd"` 字段，其余字段为该命令参数），有两条等价通道：

- **HTTP**：`POST /control`，请求体与响应体就是该 JSON 对象，失败时 HTTP 状态码与 JSON 里的 `status` 字段一致；
- **Unix Socket（本机）**：JSON 行协议 —— 每请求一行 JSON（以 `\n` 结尾），每响应一行 JSON。

**请求**：

```json
{"cmd":"play","sessionId":"0"}
```

**响应**（两种形态）：

```json
{"ok":true,"data":{ ... }}
{"ok":false,"error":"...","status":400}
```

> `status` 沿用原 REST 的状态码语义：`400` 参数非法、`404` 不存在、`409` 冲突、`500` 服务端错误。

| 项目 | 说明 |
| --- | --- |
| HTTP 通道 | `POST http://<host>:8080/control`，`Content-Type: application/json` |
| Unix Socket 通道 | `nc -U <socketPath>`（或 `socat - UNIX-CONNECT:<socketPath>`），每行一个 JSON 对象 |
| 编码 | UTF-8 |

### 会话

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `connect` | `clientIp` | 创建独立会话并返回 `{sessionId, heartbeatIntervalMs, sessionPauseAfterMs, sessionDeleteAfterMs}`；`sessionId` 为数字 ID（`0` 起始、递增、复用最小空闲）。对端地址 `remoteAddr` 由服务端观测记录（HTTP 为对端 IP、Unix Socket 为 `local`），`clientIp` 仅作客户端自报的展示字段 |
| `heartbeat` | `sessionId` | 客户端每 `heartbeatIntervalMs` 上报一次以保活；会话不存在 → 404 |
| `disconnect` | `sessionId` | 客户端主动**关闭会话**：结束并移除会话（无需等超时），其数字 ID 释放回空闲池供复用 |
| `session.status` | `sessionId` | 查询会话存活状态（最后心跳、距上次心跳毫秒、阈值） |

### 播放模式

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `mode` | `sessionId` | 返回 `{"mode":"FORWARD","displayName":"正向播放"}` |
| `mode.switch` | `sessionId` | 切到下一个模式 |
| `mode.set` | `mode`, `sessionId` | 设置模式；非法值 → 400 |

### 播放控制

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `current` | `sessionId` | 当前歌曲；队列为空 → `{"status":"empty"}` |
| `next` | `sessionId` | 下一首；队列为空 → `{"status":"end"}` |
| `previous` | `sessionId` | 上一首；队列为空 → `{"status":"begin"}` |
| `select` | `musicId`, `sessionId` | 在现有队列中**选播**指定歌曲：定位到该曲，**不会清空队列里的其它歌曲**；歌曲不存在 → 400 |
| `prepare` | `musicId`, `sessionId` | 服务端预载歌曲首段缓冲、定位到该曲，返回 `ready` 快照（歌曲不存在 → 400） |
| `play` | `duration`, `sessionId` | 开始/恢复播放；可选 `duration`（秒）让服务端记录当前歌曲总时长（仅作元数据展示） |
| `pause` | `sessionId` | 暂停播放。位置归客户端，服务端不持有时钟，此处仅返回当前歌曲元数据快照 |
| `seek` | `position`, `sessionId` | 客户端自行跳转到指定秒数；服务端不持有时钟，此处仅返回当前歌曲元数据快照 |
| `progress` | `sessionId` | 拉取当前歌曲元数据快照（songId / title / artist / album / duration / playMode），**不含实时位置**——位置归客户端（audio.currentTime） |

**`progress` 响应结构**（仅在客户端显式请求时返回；服务端任何广播/推送都不携带进度）：

```json
{
  "ok": true,
  "data": {
    "status": "unknown",
    "songId": "9f0c2c6e-...",
    "title": "稻香",
    "artist": "周杰伦",
    "album": "魔杰座",
    "duration": 223,
    "playMode": "正向播放"
  }
}
```

> `progress` 返回的是**歌曲元数据**，不含实时位置。`status` 取 `empty`（队列为空）或 `unknown`（有当前歌曲、但服务端不推算播放位置）；`duration` 为当前歌曲总时长（秒，0 表示未知）。位置归客户端——浏览器用 `audio.currentTime` 自行维护，服务端不持有时钟、不推算「现在播到第几秒」。

### 队列

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `load.all` | `sessionId` | 全部音乐载入队列 |
| `load` | `musicIds`, `sessionId` | 请求体为 ID 数组，载入队列 |
| `load.playlist` | `playlistId`, `sessionId` | 按歌单载入；歌单不存在或过滤后为空 → 400 |
| `queue` | `sessionId` | `{playMode, currentIndex, total, isEmpty, currentSong}` |
| `queue.clear` | `sessionId` | 清空队列 |
| `queue.add` | `musicId`, `sessionId` | 追加单曲（去重）；歌曲不存在 → 400，歌曲已在队列 → 409，队列已满 → 409 |
| `queue.add.batch` | `musicIds`, `sessionId` | 批量追加，返回实际追加数（响应含 `count` 新增 / `invalid` 无效 / `duplicate` 已在队列 / `dropped` 队列满） |

### 音乐库

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `music` | — | 全部音乐（**无分页**，一次返回整个库含元数据） |
| `music.get` | `id` | 单首详情；不存在 → 404 |
| `search` | `q` | 搜索；`q` 为空 → 400 |
| `artist` | `artist` | 按艺术家精确匹配（忽略大小写） |
| `artists` | — | 艺术家 → 歌曲数组 的分组映射 |

**`MusicFile` 响应结构**：

```json
{
  "id": "9f0c2c6e-...",
  "fileName": "周杰伦 - 稻香.mp3",
  "filePath": "/home/user/Music/周杰伦 - 稻香.mp3",
  "fileSize": 8123456,
  "extension": "mp3",
  "lastModified": "2026-08-14T21:03:11",
  "url": "/stream/9f0c2c6e-...",
  "metadata": {
    "title": "稻香",
    "artist": "周杰伦",
    "album": "魔杰座",
    "genre": "Pop",
    "trackNumber": 3,
    "year": 2008,
    "duration": 223,
    "bitrate": 320,
    "comment": ""
  }
}
```

### 歌单

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `playlists` | — | 全部歌单（不含歌曲详情） |
| `playlist.get` | `id` | 歌单详情，含 `songs` 数组；不存在 → 404 |
| `playlist.create` | `name`, `description` | 新建歌单；`name` 为空 → 400 |
| `playlist.update` | `id`, `name`, `description` | 改名 / 改描述；不存在 → 404 |
| `playlist.delete` | `id` | 删除歌单；不存在 → 404 |
| `playlist.add` | `id`, `musicId` | 加歌；歌单或歌曲不存在、或**歌曲已在歌单中** → 404/409 |
| `playlist.add.batch` | `id`, `musicIds` | 批量加歌，返回 `{"added": 3}`（只计真实新增） |
| `playlist.remove` | `id`, `musicId` | 移除歌曲；歌单不存在或歌曲不在歌单中 → 404 |
| `playlist.clear` | `id` | 清空歌单；不存在 → 404 |
| `playlist.reorder` | `id`, `musicIds` | 重排序，请求体为歌曲 ID 数组，**必须与歌单内现有歌曲完全一致**，否则 400 |

### 系统管理

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `stats` | — | `totalFiles`、`scanning`、`lastScan`（毫秒时间戳）、完整 `config` |
| `scan` | — | 触发**异步**扫描并立即返回；若已有扫描在跑则返回 `{"status":"queued"}`（当前扫描结束后立即补跑），否则 `{"status":"started"}` |
| `broadcast` | — | 立即发送一次广播 |
| `config` | — | 当前配置 |
| `config.set` | （配置字段） | **局部更新**配置（只覆盖提交的字段），非法取值 → 400，成功后异步触发扫描 |
| `info` | — | `name`、`version`、`status`、`librarySize` |

### 调用示例

```bash
BASE="curl -s -X POST -H 'Content-Type: application/json' http://localhost:8080/control"

# 搜索
$BASE --data '{"cmd":"search","q":"稻香"}'

# 切换为随机播放
$BASE --data '{"cmd":"mode.set","mode":"SHUFFLE"}'

# 新建歌单并加歌
$BASE --data '{"cmd":"playlist.create","name":"开车歌单","description":"路上听"}'
$BASE --data '{"cmd":"playlist.add","id":"<playlistId>","musicId":"<musicId>"}'

# 按歌单入队并播放下一首
$BASE --data '{"cmd":"load.playlist","playlistId":"<playlistId>"}'
$BASE --data '{"cmd":"next"}'
```

---

## HTTP 端点

对外只有一扇 HTTP 门（JDK 内置 `com.sun.net.httpserver`），承载**控制面（HTTP `POST /control`）**与**只读数据面**两类内容：

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/control` | `POST` | 控制面 HTTP 入口（**默认无鉴权**）：请求体为命令 JSON（见[控制面命令](#控制面命令)），响应体同命令协议，HTTP 状态码镜像 `status` 字段 |
| `/stream/{id}` | `GET` | 流式播放，支持单段 `Range`（`206 Partial Content`）；多段 Range 按 RFC 7233 降级为只返回第一段（不实现 `multipart/byteranges`）；不支持的范围 → 416；歌曲或文件不存在 → 404 |
| `/cover/{id}` | `GET` | 歌曲封面图（内嵌封面优先，回退同目录同名 `jpg/jpeg/png`）；无封面 → 404 |
| `/lyrics/{id}` | `GET` | 歌曲歌词纯文本（内嵌 USLT 优先，回退同目录同名 `.lrc`）；无歌词 → 404 |

数据面三个媒体端点均不支持鉴权，也不参与任何业务状态。音频流在「预载命中」时会带 `X-Preloaded: 1` 响应头直接从内存返回首段，其余部分走文件流式读取。

---

## 网络广播

服务器周期性向局域网发送 UDP 广播，供客户端自动发现，无需手动填写 IP。

| 项目 | 说明 |
| --- | --- |
| 协议 / 端口 | UDP / `9999`（可配置） |
| 目标地址 | `255.255.255.255`，并额外发送一次本机子网广播地址 |
| 间隔 | 30 秒（可配置，改后需重启） |
| 触发方式 | 定时任务；也可用控制面 `broadcast` 命令立即触发 |

**广播负载**（UTF-8 编码的 JSON 字符串）：

```json
{
  "type": "HelloMusic",
  "version": "1.0.0",
  "name": "HelloMusic",
  "port": 8080,
  "librarySize": 1250,
  "timestamp": 1786000000000,
  "stream": "/stream"
}
```

> `librarySize` 每次广播都实时读取当前库大小，可据此判断扫描是否完成。`port` 是**HTTP 网关端口**（控制 + 媒体），`stream` 是音频流路径前缀。

**Python 监听示例**：

```python
import socket
import json

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(('', 9999))

print('监听 UDP 9999 ...')
while True:
    data, addr = sock.recvfrom(2048)
    try:
        info = json.loads(data.decode('utf-8'))
    except json.JSONDecodeError:
        continue
    print(f"发现音乐服务器: {info['name']} "
          f"@ http://{addr[0]}:{info['port']}{info['stream']} "
          f"(曲库 {info['librarySize']} 首)")
```

---

## 播放模式

| 模式 | 显示名 | 行为 |
| --- | --- | --- |
| `FORWARD` | 正向播放 | 顺序向后；到末尾回到第一首 |
| `REVERSE` | 逆向播放 | 顺序向前；到开头回到最后一首 |
| `SINGLE` | 单曲循环 | 始终停留在当前歌曲 |
| `SHUFFLE` | 随机播放 | 随机选下一首；队列只有一首时直接返回该首 |

模式变更会立刻写回配置文件，重启后自动恢复。

随机（SHUFFLE）模式额外维护一个播放历史栈，因此「上一首」能真正回溯到上一首播放过的歌曲（历史用尽前不再重播当前），FORWARD / REVERSE / SINGLE 的「上一首」仍按队列循环位移。

---

## 运行时文件

| 文件 | 生成时机 | 内容 |
| --- | --- | --- |
| `hellomusic-config.json` | 首次启动 | 唯一配置文件（服务器字段 + 用户选项 + 上次播放模式 + 播放队列与位置） |
| `playlists.json` | 首次创建歌单 | 所有歌单及其歌曲 ID 列表、创建/更新时间 |

两者都写在**当前工作目录**，均已在 `.gitignore` 中忽略，不会污染仓库。

两个文件都采用**原子写入**——先写同目录的临时文件，再整体替换目标文件。因此程序被强制结束或机器断电时，
磁盘上要么保留上一次的完整内容、要么是新写入的完整内容，不会留下半截 JSON 而导致配置或**整个歌单文件**被静默丢弃。

此外，运行日志写在 `~/.local/share/HelloMusic/logs/`（由 `logPath` 配置）：

| 路径 | 生成时机 | 内容 |
| --- | --- | --- |
| `logs/日期-启动时间.txt` | 启用 `enableFileLog`（=1）时 | 该次启动的完整运行日志 |

---

## 项目结构

```
HelloMusic/
├── src/main/java/core/
│   ├── HelloMusicApplication.java   # 纯 JDK 启动入口：装配对象图、挂调度器、起控制面 + 数据面
│   ├── ConfigManager.java           # 唯一配置读写（hellomusic-config.json，含服务器字段与用户选项）
│   ├── Json.java                    # 统一 ObjectMapper 工厂（注册 JavaTimeModule）
│   ├── JsonFiles.java               # JSON 原子落盘（临时文件 + 原子替换）
│   ├── MusicScannerService.java     # 库扫描：串行遍历 + 指纹比对（增量），并行解析元数据
│   ├── MetadataExtractor.java       # 元数据提取（jaudiotagger + 文件名兜底），封面/歌词按需提取
│   ├── Mp3DurationParser.java       # MP3 时长兜底解析（CBR 估算 + Xing/VBRI 头部），包级可见
│   ├── MusicFile.java               # 曲目模型；displayTitle()/titleArtist() 是显示名的唯一实现
│   ├── MusicMetadata.java           # 标签模型
│   ├── BroadcastService.java        # UDP 广播
│   ├── LoggingInstaller.java        # 按配置为 logback 挂载文件 appender（启动前安装）
│   ├── CommandDispatcher.java       # 控制面命令路由：唯一入口，与传输层无关
│   ├── UnixSocketServer.java        # 控制面·本机通道：Unix Socket + JSON 行协议
│   ├── HttpGateway.java             # 统一 HTTP 门：控制面 POST /control + 数据面 /stream /cover /lyrics（支持 Range）
│   ├── PreloadService.java          # 歌曲首段预缓冲（流缓存）：prepare 预载、stream 命中共享 LRU
│   ├── Playlist.java                # 歌单模型
│   ├── PlaylistService.java         # 歌单服务（含 JSON 持久化）
│   ├── PlayMode.java                # 播放模式枚举
│   ├── PlaybackSession.java         # 单会话队列与游标（线程安全）
│   ├── ClientSession.java           # 前端会话模型（持有 PlaybackSession + 心跳时间戳 + 客户端 IP）
│   ├── SessionRegistry.java         # 会话注册表：连接/心跳/断开/巡检
│   ├── QueueManager.java            # 队列管理：加载/追加/清空/落盘
│   ├── ProgressMapper.java          # 进度快照组装
│   ├── PlaybackEngine.java          # 播放引擎：play/pause/seek/next/previous（不持有时钟）
│   └── PlaybackService.java         # 多会话管理、模式持久化、连接/心跳/超时治理（薄门面）
├── src/test/java/core/
│   ├── PlaybackSessionTest.java
│   ├── PlaylistTest.java
│   ├── PlaylistJsonTest.java
│   ├── JsonFilesTest.java
│   ├── MetadataExtractorTest.java
│   ├── Mp3DurationParserTest.java
│   ├── ConfigManagerTest.java
│   ├── MusicScannerServiceTest.java
│   ├── PlaybackServiceTest.java
│   └── SessionManagementTest.java
├── .github/workflows/ci.yml         # CI：push / PR 时跑 mvn verify
├── pom.xml
├── .gitignore                       # 忽略 target/、IDE 文件与运行时数据
├── LICENSE                          # GPL-3.0 官方全文
└── README.md
```

> `hellomusic-config.json` 与 `playlists.json` 是运行时才生成到工作目录的，不属于源码树。

---

## 技术栈

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 21 | 语言与运行时（含内置 `com.sun.net.httpserver` 与 Unix Socket 支持） |
| Jackson | 2.15.3 | JSON 序列化（databind + datatype-jsr310，已注册 JavaTimeModule） |
| SLF4J + Logback | 2.0.9 / 1.4.11 | 统一日志门面（全项目唯一日志出口） |
| Bean Validation | Hibernate Validator 8.0.1.Final | 配置字段校验（jakarta.validation-api 3.0.2 + tomcat-embed-el 提供 jakarta.el） |
| Apache Commons IO | 2.15.1 | 路径与扩展名处理 |
| jaudiotagger | 3.0.1 | 音频标签解析（MP3/FLAC/M4A/OGG/WAV） |
| Maven | 3.6+ | 构建与测试 |
| JUnit 5 | 5.10.1 | 单元测试 |

> **不再依赖 Spring / Spring Boot**：控制面用 `ServerSocketChannel`（Unix domain socket），数据面用 `com.sun.net.httpserver`，定时任务用 `ScheduledExecutorService`，依赖注入改为 `HelloMusicApplication` 里的手动装配。打出的 fat jar 由 `maven-shade-plugin` 生成，`java -jar` 直接运行。

---

## 设计约定

这几条是重构后定下的分层与日志规范，新增代码请遵循：

**控制面 / 数据面分离**：业务状态（会话、队列、播放、歌单、配置）只能通过 `CommandDispatcher` 的命令入口修改；数据面 `HttpGateway` 的媒体端点（流 / 封面 / 歌词）是**只读媒体服务**，绝不写业务状态。`CommandDispatcher` 与传输层无关——本机走 Unix Socket（JSON 行协议），远程走 HTTP `POST /control`，两条通道共享同一份对象图。

**分层依赖方向**：`CommandDispatcher → Service → 无`。Service 之间可以互相依赖，但**不得反向依赖命令分发或数据面**——预缓冲逻辑因此独立为 `PreloadService`，控制面（`prepare` 预载）与数据面（`stream` 命中）共享同一单例缓存。

**日志统一走 SLF4J**（`org.slf4j.Logger` + `LoggerFactory`）。业务代码里不应出现 `System.out.println`：启动 banner 由 `HelloMusicApplication` 承担，是有意的控制台呈现。唯一的例外是 `MetadataExtractor`——它需要用 `java.util.logging` 去静音 Jaudiotagger 内部的 JUL 输出，那是第三方库的强制要求。

**歌曲显示名只有一份实现**：`MusicFile.displayTitle()` / `titleArtist()` 统一处理「元数据优先、占位符 `Unknown Title`/`Unknown Artist` 视为缺失、回退文件名」的规则。不要在别处重复拼装「标题 - 艺术家」。

**JSON 序列化只有一份配置**：统一走 `Json.newMapper()`（注册 `JavaTimeModule`、关闭日期时间戳数组化），不要到处 `new ObjectMapper()`。

**播放位置归客户端**：服务器不持有时钟、不推算「现在播到第几秒」。`progress` 返回的是元数据（歌曲、时长、模式），不含实时位置。历史上曾为此引入服务端时钟，在 CLI 移除后成为死代码，已删除。需要位置能力的客户端应自行维护 `audio.currentTime`；若未来需要服务端感知位置，走「上报 + 最后已知快照」，不要复活服务器时钟。

---

## 开发与测试

```bash
mvn clean test                          # 运行单元测试
mvn verify                              # 编译 + 测试 + 打包（与 CI 跑的是同一条命令）
mvn test -Dtest=PlaybackSessionTest     # 只跑一个测试类
java -jar target/HelloMusic.jar         # 运行打包产物
```

单测不依赖任何外部资源：不联网、不读宿主机音乐目录、不往项目目录写文件（需要文件系统的用例一律用 JUnit 的 `@TempDir`），因此在干净的机器上可直接复现。

CI 配置见 `.github/workflows/ci.yml`——推送到 `main`/`master` 或提交 PR 时，用 JDK 21 执行 `mvn -B -ntp verify`，依赖走 `~/.m2` 缓存；构建失败时会把 `target/surefire-reports/` 作为产物上传，便于直接查看报告。

测试位于 `src/test/java/core/`，共 10 个测试类：

| 测试类 | 覆盖点 |
| --- | --- |
| `PlaybackSessionTest` | 四种播放模式的推进与环绕、队列增删清空、防御性拷贝，以及 8 线程 × 2000 次并发压测 |
| `PlaylistTest` | 增删的返回值语义、无实际变更时不刷新 `updatedAt`、`musicIds` 为 null 的兜底、`getMusicIds()` 的只读快照语义，以及读线程 × 4 写线程的并发压测（防 `ConcurrentModificationException`） |
| `JsonFilesTest` | 原子落盘：内容可往返、覆盖是整体替换（旧内容不残留）、不残留临时文件、写入失败时目标文件保持原样 |
| `PlaylistJsonTest` | 歌单 JSON 序列化往返（含 `LocalDateTime`），守住歌单持久化行为 |
| `MetadataExtractorTest` | 标签缺失时按「艺术家 - 标题」解析文件名 |
| `Mp3DurationParserTest` | CBR 估算、Xing(VBR) 头部解析、ID3v2 跳过、异常输入返回 0 |
| `ConfigManagerTest` | `config.set` 的合并语义（未提交字段保持原值）与字段校验约束（含 7 个用户选项） |
| `MusicScannerServiceTest` | 递归扫描与扩展名过滤、**并行与串行结果一致**、非法库路径被跳过、文件名兜底、空目录得到空库；增量行为：**未变更文件复用上次的解析结果**（以对象身份断言）、内容或 mtime 变化则重新解析、被删除的文件从库中消失、后加入的文件被下次重扫收录；符号链接：目录链接、文件链接、库路径本身为目录链接均被收录，循环链接不中断整次扫描 |
| `PlaybackServiceTest` | 选播/点歌不误清队列、幽灵项跳过（next/previous 只查一次曲库）、progress 不含位置/时钟字段（负向守护） |
| `SessionManagementTest` | 连接/心跳/主动关闭会话、数字 ID 递增与最小空闲复用、心跳超时 stale 后删除、起始会话 `0` 永不参与治理 |

---

## 常见问题

**端口被占用起不来？**
改 `hellomusic-config.json` 里的 `serverPort` 后重启（`serverPort` 是 HTTP 服务端口，控制面 + 数据面共用，需重启生效）。

**扫描不到文件？**
依次确认：库路径存在且是目录（见 `hellomusic-config.json` 的 `musicPaths`）→ 扩展名在 `supportedExtensions` 里 → 文件确实可读。日志里 `⚠️ Invalid library path` 表示路径无效。音乐文件经符号链接接入（目录链接或文件链接）也会被正常收录；若出现「检测到符号链接循环」日志，说明存在指向祖先目录的循环链接，已自动跳过、不影响其余文件。

**扫描很慢？**
扫描分三阶段：① 串行遍历目录收集候选文件，② 串行读每个文件的 size/mtime 比对指纹，③ 并行解析需要重新读取的文件。线程数见 `scanThreads`（默认取 CPU 核数，可按机器调大）。

因为扫描是**增量**的，周期性重扫的代价与「变动的文件数」成正比，而不再与库的大小成正比——库里几千首歌一首没动时，重扫只花掉一轮 `stat` 的时间，日志里的「复用 N 个」就是被跳过的部分。首次启动（冷启动）必然是全量解析，这部分开销无法避免。另外元数据解析是逐文件打开读取，库放在网络盘上时瓶颈通常在外层 I/O，此时调大线程数收益有限。

判定的粒度是 size + mtime：在同一 mtime 精度内被改写、且大小恰好不变的文件会被判为「没变」而沿用旧结果（音乐库以整文件替换为主，绝大多数场景不受影响）。缓存只存在内存中，重启进程即回到全量扫描。

**局域网里搜不到广播？**
先放行防火墙的 UDP 9999 **入站**。若网络设备不转发 `255.255.255.255`，本项目已额外向本机子网广播地址补发一次，但仍需客户端与服务端处于同一网段。

**换目录启动后配置/歌单"消失"了？**
配置与歌单是相对**当前工作目录**读写的，换目录等于换了一份空配置。固定目录启动，或把这两个文件一起带过去。

**中文标签乱码或读不出来？**
jaudiotagger 会尝试 UTF-8/GBK 解码；确实没有标签的文件会走文件名兜底（`艺术家 - 标题.ext`）。把文件名规范成 `艺术家 - 标题.mp3` 也能得到正确结果。

**MP3 时长不准？**
带 Xing/Info 或 VBRI 头部的 VBR 文件已按帧精确计算；没有这些头部的文件只能按首帧比特率估算，会有偏差。

**`config.set` 时漏写某个字段，会把那一项清掉吗？**
不会。该命令是**局部更新**：只覆盖提交了的字段，未提交（或传 `null`）的字段保持原值；取值非法会直接返回 `400` 并说明原因，配置文件不会被写坏。详见[配置说明](#配置说明)。

---

## 安全提示

本项目面向**可信局域网内**使用，默认**没有任何鉴权**：

- `config.set` 可以修改音乐库路径与受支持扩展名，配合 `/stream/{id}` 可能被用来读取服务器上的任意文件；
- `music`、`config` 会返回服务器上的**绝对路径**。

因此：**不要把它直接暴露到公网**，也不要在不可信的网络里长期运行。

控制面现在**同时**暴露在 Unix Socket（仅本机进程可连接，socket 文件权限由文件系统控制）与 HTTP `POST /control`（默认 8080，可达局域网）上，因此它与数据面一样具备写权限风险：任何能访问该 HTTP 端口的客户端都能改配置、清队列、删歌单。如需在非本机网络使用，应在网络层（防火墙 / 反向代理）加鉴权；本机进程则仍可经 Unix Socket 绕开网络暴露。

控制面还内置了几道**资源上限**，用于抵御基础的资源耗尽攻击（纵深防御，**不能替代鉴权**）：

- HTTP `POST /control` 请求体上限 256KB，超过返回 `413`；
- Unix Socket 单行请求上限 64KB，超过即断开连接；
- 三处网络 / 后台线程池均为有界（固定并发 + 有界队列），恶意并发连接不会无限扩张线程。

---

## 贡献指南

1. Fork 本项目
2. 创建功能分支（`git checkout -b feature/xxx`）
3. 提交更改（`git commit -m 'feat: xxx'`）
4. 推送到分支（`git push origin feature/xxx`）
5. 打开 Pull Request

---

## 许可证

本项目采用 **GNU General Public License v3.0（GPL-3.0）**，版权持有人（copyright holder）为 **Lu Jiafan**。完整条款见根目录 [LICENSE](LICENSE) 文件（该文件为 GPL-3.0 官方原文，未作任何修改）。

```
HelloMusic - Local music server with Unix socket control plane and HTTP media stream
Copyright (C) 2026 Lu Jiafan

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

简单来说：你可以自由使用、修改和分发本项目，但**分发衍生作品时必须同样以 GPL-3.0 开源并提供源码**。

## 致谢

- [DeepSeek](https://www.deepseek.com/) —— AI 辅助开发
- [Adoptium](https://adoptium.net/) —— Java 发行版
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) —— 音频标签解析

---

如果这个项目对你有帮助，欢迎给个 Star ⭐
