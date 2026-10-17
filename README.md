# HelloMusic - 本地音乐服务器

[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://makeapullrequest.com)
[![AI Generated](https://img.shields.io/badge/AI-Generated-8A2BE2?logo=openai&logoColor=white)](https://github.com)

> 本项目由 AI 辅助生成 —— 基于深度求索（DeepSeek）AI 模型，通过自然语言对话完成完整项目开发。

扫描本机的音乐目录，提取标签与时长，在局域网里用 UDP 广播自我通告，并对外提供一套 REST API 与一个交互式 CLI。没有数据库、没有前端构建、没有外部服务依赖，`java -jar` 一条命令就能跑起来。

---

## 目录

- [特性](#特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [命令行界面](#命令行界面)
- [REST API](#rest-api)
- [网络广播](#网络广播)
- [播放模式](#播放模式)
- [网页播放器](#网页播放器)
- [运行时文件](#运行时文件)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [开发与测试](#开发与测试)
- [常见问题](#常见问题)
- [安全提示](#安全提示)
- [贡献指南](#贡献指南)
- [许可证](#许可证)
- [致谢](#致谢)

---

## 特性

| 功能 | 说明 |
| --- | --- |
| 自动扫描 | 递归扫描配置的音乐库目录，按扩展名过滤（默认 MP3、FLAC、WAV、M4A、OGG、MKV） |
| 增量扫描 | 按「绝对路径 + 文件大小 + 修改时间」判断文件是否变过：没变的直接复用上次解析结果，只有新增或改动过的文件才会被重新读取 |
| 并行解析 | 目录遍历与指纹比对串行、元数据解析并行：线程数默认取 CPU 核数，可用 `scanThreads` 调整（设为 `1` 退回串行） |
| 稳定 ID | 用文件绝对路径生成 UUID（`nameUUIDFromBytes`），重启或重新扫描后 ID 不变 |
| 元数据提取 | 基于 jaudiotagger 读取标题、艺术家、专辑、流派、音轨号、年份，支持中文标签（UTF-8/GBK） |
| 元数据兜底 | 无标签或解析失败时回退为「艺术家 - 标题」文件名解析，字段填充 `Unknown Title/Artist/Album/Genre` |
| 时长解析 | 优先取标签时长；MP3 额外提供纯 Java 解析兜底，支持 CBR 估算与 Xing/Info、VBRI（VBR）头部 |
| 流式播放 | `/api/stream/{id}` 支持 HTTP Range，可拖动进度条，返回 `206 Partial Content` |
| 网络广播 | UDP 广播自我通告，同时发往 `255.255.255.255` 与本机子网广播地址 |
| REST API | 音乐库、歌单、播放控制、系统管理四组接口，JSON 响应 |
| 播放列表 | 创建/改名/删除歌单，增删歌曲、批量添加、重排序，持久化到 `playlists.json` |
| 播放控制 | 正向、逆向、单曲循环、随机四种模式，可切换并自动持久化 |
| 播放队列 | 全部音乐入队、按歌单入队、单曲/批量追加、清空、查看队列状态 |
| 配置持久化 | 配置与上次播放模式写入 `hellomusic-config.json`，重启不丢 |
| 交互式 CLI | 27 个命令，覆盖扫描、歌单、播放控制与配置管理 |
| 定时刷新 | 启动时同步扫描一次，之后每 5 分钟自动重扫；重扫是增量的，只会处理新增与改动过的文件 |
| 播放进度时钟 | 服务端持有**权威播放时钟**（按自身墙钟推算当前秒数），新增 `/play`、`/pause`、`/seek`、`/progress` 四个接口 |
| 网页播放器 | 内置 `static/index.html`：曲库列表 + 播放/暂停/上下首 + 可点击拖动的进度条 + 实时秒数显示 |
| SHUFFLE 历史 | 随机模式维护播放历史栈，使"上一首"真正回溯（而非重播当前） |
| 客户端同步对账 | 服务端每 5 分钟校验客户端是否同步播放进度；未同步则记录 WARN 日志。服务端不主动推送任何进度 |

---

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21+ | `pom.xml` 中 `maven.compiler.release=21`，低版本无法编译 |
| Maven | 3.6+ | 构建与测试 |

---

## 快速开始

```bash
git clone https://github.com/lujiafan-art/HelloMusic.git
cd HelloMusic
mvn clean package
java -jar target/HelloMusic.jar
```

启动后终端会打印横幅，并进入 CLI（提示符 `🎵>`）：

```
╔═══════════════════════════════════════════╗
║       HelloMusic Server Started           ║
║                                           ║
║  API: http://localhost:8080/api           ║
║  CLI: Type 'help' in console              ║
║  Broadcast: UDP 9999                      ║
╚═══════════════════════════════════════════╝
```

**首次启动会自动完成三件事**：

1. 在当前工作目录生成 `hellomusic-config.json`，音乐库默认指向 `~/Music`；
2. 立即全量扫描一次音乐库（同步执行，库很大时启动会慢一些，日志会打印文件数与耗时；此后的重扫都是增量的，只处理有变化的文件）；
3. 拉起 HTTP 服务（默认 8080）与 UDP 广播（默认 9999 / 每 30 秒一次）。

> ⚠️ **务必固定在同一目录启动。** 配置与歌单文件是相对**当前工作目录**（不是 classpath）读写的。在 A 目录启动过、又切到 B 目录启动，看起来就像"配置和歌单全丢了"。

### 在浏览器里播放

拿到歌曲 ID 后直接丢给 `<audio>` 即可，`Range` 已支持，进度条可以拖：

```html
<audio controls src="http://localhost:8080/api/stream/替换成你的歌曲ID"></audio>
```

> 此外，项目内置了一个开箱即用的网页播放器 `src/main/resources/static/index.html`，启动后直接访问 `http://localhost:8080/` 即可使用：自带曲库列表、播放/暂停/上下首与可点击拖动的进度条。进度条以**服务端时钟为权威来源**，播放/暂停走与服务端的握手（恢复播放先取回权威时间再回传确认），并按 5 分钟周期与 `GET /api/playback/progress` 对账（详见[网页播放器](#网页播放器)）。

---

## 配置说明

配置文件为当前工作目录下的 `hellomusic-config.json`，首次启动自动生成：

```json
{
  "musicLibraryPaths" : [ "/home/username/Music" ],
  "serverPort" : 8080,
  "broadcastPort" : 9999,
  "broadcastInterval" : 30000,
  "scanThreads" : 8,
  "supportedExtensions" : [ "mp3", "mkv", "flac", "wav", "m4a", "ogg" ],
  "serverName" : "HelloMusic",
  "lastPlayMode" : "FORWARD"
}
```

| 参数 | 类型 | 默认值 | 生效时机 | 说明 |
| --- | --- | --- | --- | --- |
| `musicLibraryPaths` | String[] | `[ "<用户主目录>/Music" ]` | 下次扫描 | 音乐库目录列表，**建议用绝对路径** |
| `serverPort` | int | `8080` | **需重启** | HTTP 服务端口 |
| `broadcastPort` | int | `9999` | 立即 | UDP 广播端口 |
| `broadcastInterval` | int | `30000` | **需重启** | 广播间隔（毫秒） |
| `scanThreads` | int | CPU 核数 | 下次扫描 | 元数据解析线程数，取值 1-64；设为 `1` 退回串行解析 |
| `supportedExtensions` | String[] | `["mp3","mkv","flac","wav","m4a","ogg"]` | 下次扫描 | 参与扫描的文件扩展名（不含点号，忽略大小写） |
| `serverName` | String | `"HelloMusic"` | 立即 | 广播中通告的服务器名 |
| `lastPlayMode` | String | `"FORWARD"` | — | 上次播放模式，**由程序自动写回**，不建议手改 |

### 配置项取值范围

`PUT /api/config` 会对提交的字段做校验，越界即返回 `400`：

| 参数 | 取值范围 |
| --- | --- |
| `serverPort` / `broadcastPort` | 1 - 65535 |
| `broadcastInterval` | 1000 - 3600000（毫秒） |
| `scanThreads` | 1 - 64 |
| `musicLibraryPaths` | 至少一个，元素不能是空字符串 |
| `supportedExtensions` | 至少一个，元素不能是空字符串（不含点号） |
| `serverName` | 不能为空或纯空白 |
| `lastPlayMode` | `FORWARD` / `REVERSE` / `SINGLE` / `SHUFFLE`（忽略大小写） |

读取配置文件时同样做一次体检：非法项**回退为默认值**并在启动日志里告警，不会让服务带着坏配置启动。

### 修改配置的三种方式

**方式一：直接编辑文件**（改完后重启，或用 `scan` 重新扫描）

```bash
vim hellomusic-config.json
```

**方式二：CLI 命令** —— 改音乐库路径的推荐做法，会自动触发扫描

```
addpath /media/nas/Music
removepath 0
```

**方式三：REST API** —— 局部更新，只提交要改的字段即可

```bash
# 只改服务器名，其余字段保持不变
curl -X PUT http://localhost:8080/api/config \
     -H "Content-Type: application/json" \
     -d '{"serverName":"客厅音乐"}'
```

- **只覆盖提交了的字段**，未提交或传 `null` 的字段保持原值，因此不必先 `GET` 再整体提交；
- **取值非法直接返回 `400`**，并逐字段说明原因，配置文件不会被写坏：

```bash
curl -X PUT http://localhost:8080/api/config \
     -H "Content-Type: application/json" \
     -d '{"serverPort":0}'
# {"error":"请求参数校验失败","details":[{"field":"serverPort","message":"端口取值范围为 1-65535"}]}
```

提交合法后**异步**触发一次扫描（增量，只重新解析有变化的文件），接口立即返回、不会被扫描阻塞。


---

## 命令行界面

服务启动后在同一个终端里使用，命令大小写不敏感。

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `help` / `?` | — | 显示帮助 |
| `list` | `[count]` | 列出音乐文件，默认显示 20 条，输出含时长与歌曲 ID |
| `search` | `query` | 按文件名 / 标题 / 艺术家 / 专辑搜索，**支持带空格的查询** |
| `artists` | — | 按艺术家分组列出（每位最多预览 3 首） |
| `stats` | — | 库大小、扫描状态、端口、歌单数、当前模式、队列长度 |
| `scan` | — | 立即扫描音乐库（**同步执行**，大库会阻塞 CLI；增量，只重新解析有变化的文件） |
| `broadcast` | — | 立即发送一次 UDP 广播 |
| `config` | — | 打印当前配置与库路径 |
| `addpath` | `path` | 添加音乐库目录，成功后自动扫描 |
| `removepath` | `index` | 按序号移除目录，**序号从 0 开始**，用 `config` 查看 |
| `playlists` | — | 列出所有歌单及其 ID |
| `playlist` | `id` | 歌单详情（含歌曲列表） |
| `createplaylist` | `name [description]` | 新建歌单，`name` 不能含空格 |
| `deleteplaylist` | `id` | 删除歌单 |
| `addtoplaylist` | `pid mid` | 把歌曲加入歌单 |
| `removefromplaylist` | `pid mid` | 从歌单移除歌曲 |
| `mode` | — | 显示当前播放模式 |
| `switch` | — | 切到下一个播放模式 |
| `setmode` | `FORWARD\|REVERSE\|SINGLE\|SHUFFLE` | 直接设置播放模式 |
| `current` | — | 显示当前播放歌曲 |
| `next` | — | 下一首 |
| `prev` | — | 上一首 |
| `loadall` | — | 全部音乐载入播放队列 |
| `loadplaylist` | `id` | 歌单载入播放队列（自动剔除已失效的歌曲） |
| `queue` | — | 播放队列信息（模式 / 总数 / 位置 / 当前歌曲） |
| `clearqueue` | — | 清空播放队列 |
| `exit` / `quit` | — | 退出程序 |

### 示例

```
🎵> config
🎵> addpath /media/nas/Music
🎵> list 10
🎵> search 周杰伦
🎵> createplaylist 我的最爱
🎵> playlists
🎵> addtoplaylist 1f2e... 9a8b...
🎵> loadplaylist 1f2e...
🎵> setmode SHUFFLE
🎵> next
🎵> queue
🎵> exit
```

---

## REST API

| 项目 | 说明 |
| --- | --- |
| Base URL | `http://localhost:8080/api` |
| 响应格式 | `application/json;charset=UTF-8` |
| CORS | 允许所有来源（见[安全提示](#安全提示)） |
| 错误约定 | 参数非法返回 400，资源不存在返回 404；部分接口用 200 + `{"status":"..."}` 表达空状态 |

### 音乐库

| 方法 | 端点 | 说明 |
| --- | --- | --- |
| GET | `/api/music` | 全部音乐（**无分页**，一次返回整个库含元数据） |
| GET | `/api/music/{id}` | 单首详情；不存在 → 404 |
| GET | `/api/music/search?q=` | 搜索；`q` 为空 → 400 |
| GET | `/api/music/artist/{artist}` | 按艺术家精确匹配（忽略大小写） |
| GET | `/api/artists` | 艺术家 → 歌曲数组 的分组映射 |
| GET | `/api/stream/{id}` | 流式播放，支持 `Range`；不支持的范围 → 416 |
| POST | `/api/scan` | 触发**异步**扫描，立即返回 `{"status":"Scan started"}` |

**`MusicFile` 响应结构**：

```json
{
  "id": "9f0c2c6e-...",
  "fileName": "周杰伦 - 稻香.mp3",
  "filePath": "/home/user/Music/周杰伦 - 稻香.mp3",
  "fileSize": 8123456,
  "extension": "mp3",
  "lastModified": "2026-08-14T21:03:11",
  "url": "/api/stream/9f0c2c6e-...",
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

### 播放列表

| 方法 | 端点 | 说明 |
| --- | --- | --- |
| GET | `/api/playlists` | 全部歌单（不含歌曲详情） |
| GET | `/api/playlists/{id}` | 歌单详情，含 `songs` 数组；不存在 → 404 |
| POST | `/api/playlists?name=&description=` | 新建歌单；`name` 为空 → 400 |
| PUT | `/api/playlists/{id}?name=&description=` | 改名 / 改描述；不存在 → 404 |
| DELETE | `/api/playlists/{id}` | 删除歌单；不存在 → 404 |
| POST | `/api/playlists/{id}/songs?musicId=` | 加歌；歌单或歌曲不存在、或**歌曲已在歌单中** → 400 |
| POST | `/api/playlists/{id}/songs/batch` | 请求体为 ID 数组，返回 `{"added": 3}`（只计真实新增） |
| DELETE | `/api/playlists/{id}/songs/{musicId}` | 移除歌曲；歌单不存在或歌曲不在歌单中 → 404 |
| DELETE | `/api/playlists/{id}/clear` | 清空歌单；不存在 → 404 |
| PUT | `/api/playlists/{id}/reorder` | 请求体为歌曲 ID 数组，**必须与歌单内现有歌曲完全一致**，否则 400 |

### 播放控制

> 服务端目前只有一个 `default` 播放会话 —— **所有客户端共享同一个队列与播放位置**。

| 方法 | 端点 | 说明 |
| --- | --- | --- |
| GET | `/api/playback/mode` | 返回 `{"mode":"FORWARD","displayName":"正向播放"}` |
| POST | `/api/playback/mode/switch` | 切到下一个模式 |
| POST | `/api/playback/mode?mode=SHUFFLE` | 设置模式；非法值 → 400 |
| GET | `/api/playback/current` | 当前歌曲；队列为空 → `{"status":"empty"}` |
| POST | `/api/playback/next` | 下一首；队列为空 → `{"status":"end"}` |
| POST | `/api/playback/previous` | 上一首；队列为空 → `{"status":"begin"}` |
| POST | `/api/playback/load/all` | 全部音乐载入队列 |
| POST | `/api/playback/load` | 请求体为 ID 数组，载入队列 |
| POST | `/api/playback/load/playlist/{id}` | 按歌单载入；歌单不存在或过滤后为空 → 400 |
| GET | `/api/playback/queue` | `{playMode, currentIndex, total, isEmpty, currentSong}` |
| DELETE | `/api/playback/queue` | 清空队列 |
| POST | `/api/playback/queue/add?musicId=` | 追加单曲；歌曲不存在 → 400 |
| POST | `/api/playback/queue/add/batch` | 请求体为 ID 数组，返回实际追加数 |
| POST | `/api/playback/play` | 开始/恢复播放；可选 `?duration=秒` 让服务端同步当前歌曲总时长。响应含权威 `position` 与 `requireSync:true`，**客户端必须随后回传 `POST /api/playback/sync/ack`** 才算真正同步（握手） |
| POST | `/api/playback/pause` | 暂停播放，进度冻结在当前位置（同时告知服务端，服务端据此记为一次同步） |
| POST | `/api/playback/seek?position=秒` | 跳转到指定秒数；越界自动收敛到 `[0, duration]`（客户端发起的位置推送，服务端记为一次同步） |
| GET | `/api/playback/progress` | 拉取当前播放进度快照（服务端权威时钟，**纯拉取**，本身不计入同步确认） |
| POST | `/api/playback/sync/ack` | 显式回传「已同步」确认（**这是唯一让服务端记为「已同步」的方式**）；返回 `{"status":"synced"}` |

**`/progress` 响应结构**（仅在客户端显式请求时返回；服务端任何广播/推送都不携带进度）：

```json
{
  "status": "playing",
  "songId": "9f0c2c6e-...",
  "title": "稻香",
  "artist": "周杰伦",
  "album": "魔杰座",
  "position": 42.3,
  "duration": 223,
  "isPlaying": true,
  "percent": 18.97,
  "playMode": "正向播放",
  "lastSyncedAt": 1786000420000,
  "syncDueAt": 1786000720000
}
```

> `position` 为**自当前歌曲开始播放起**的偏移秒数（0 起，到 `duration` 止），不是绝对墙钟时间；`duration` 为歌曲总秒数；`percent` 为进度百分比；`status` 取 `playing` / `paused` / `empty`；`lastSyncedAt` 为客户端上次同步的 epoch 毫秒，`syncDueAt` 为客户端最迟应再次同步的 epoch 毫秒。
>
> **同步对账（重要）**：服务端是进度唯一权威来源，但**绝不主动推送**进度。同步确认**只能由客户端显式 `POST /api/playback/sync/ack` 完成**（拉取 `/progress` 本身不计入确认）。握手约定如下：
> - **恢复播放**：客户端先 `POST /play` → 服务端返回权威 `position` → 客户端据此对齐本地播放位置，并**必须回传 `POST /sync/ack`** 才算真正同步；
> - **暂停**：客户端 `POST /pause` 告知服务端（服务端据此固化进度，并记为一次同步）；
> - **周期对账**：每隔 5 分钟，服务端检查：若某**活跃播放**会话超过 5 分钟未收到 `/sync/ack`，则记录一条 WARN 日志（示例：`[播放同步] 客户端已超过 5 分钟未与服务端同步播放进度（最后同步: …），会话: default`）。已暂停的会话、纯 CLI 播放不追责。网页播放器默认满足该约定；其它客户端须在 5 分钟窗口内至少回传一次 `/sync/ack`。
>
> （注：`POST /play` 的响应额外携带 `"requireSync": true`，即明确提示客户端「已收到服务端权威时间，必须回传 `/sync/ack`」。）

### 系统管理

| 方法 | 端点 | 说明 |
| --- | --- | --- |
| GET | `/api/stats` | `totalFiles`、`scanning`、`lastScan`（毫秒时间戳）、完整 `config` |
| GET | `/api/config` | 当前配置 |
| PUT | `/api/config` | **局部更新**配置（只覆盖提交的字段），非法取值 → 400，成功后异步触发扫描（见[配置说明](#配置说明)） |
| GET | `/api/info` | `name`、`version`、`status`、`librarySize` |
| POST | `/api/broadcast` | 立即发送一次广播 |
| GET | `/actuator/health` | Spring Boot Actuator 健康检查（默认仅暴露 health） |

### 调用示例

```bash
# 搜索
curl "http://localhost:8080/api/music/search?q=稻香"

# 切换为随机播放
curl -X POST "http://localhost:8080/api/playback/mode?mode=SHUFFLE"

# 新建歌单并加歌
curl -X POST "http://localhost:8080/api/playlists?name=开车歌单&description=路上听"
curl -X POST "http://localhost:8080/api/playlists/{id}/songs?musicId={musicId}"

# 按歌单入队并播放下一首
curl -X POST "http://localhost:8080/api/playback/load/playlist/{id}"
curl -X POST "http://localhost:8080/api/playback/next"
```

---

## 网络广播

服务器周期性向局域网发送 UDP 广播，供客户端自动发现，无需手动填写 IP。

| 项目 | 说明 |
| --- | --- |
| 协议 / 端口 | UDP / `9999`（可配置） |
| 目标地址 | `255.255.255.255`，并额外发送一次本机子网广播地址 |
| 间隔 | 30 秒（可配置，改后需重启） |
| 触发方式 | 定时任务；也可用 CLI `broadcast` 或 `POST /api/broadcast` 立即触发 |

**广播负载**（UTF-8 编码的 JSON 字符串）：

```json
{
  "type": "HelloMusic",
  "version": "1.0.0",
  "name": "HelloMusic",
  "port": 8080,
  "librarySize": 1250,
  "timestamp": 1786000000000,
  "api": "/api"
}
```

> `librarySize` 每次广播都实时读取当前库大小，可据此判断扫描是否完成。

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
          f"@ http://{addr[0]}:{info['port']}{info['api']} "
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

## 网页播放器

启动后访问 `http://localhost:8080/`（HTTP 根路径）即可打开内置网页播放器，无需任何构建步骤——它就是一个静态 `index.html`，由 Spring Boot 直接托管。

**包含的功能**：

- 曲库列表（标题 / 艺术家 / 专辑 / 时长），点击即播放；
- 播放 / 暂停、上一首 / 下一首；
- 可点击拖动的进度条，实时显示「分:秒 / 分:秒」；
- 进度条显示以**服务端权威时钟**为准：本地用 `<audio>.currentTime` 平滑渲染。播放/暂停遵循与服务端的握手——**恢复播放时先 `POST /play` 拿回权威时间、对齐本地位置后再回传 `POST /sync/ack`；暂停时 `POST /pause` 告知服务端**；此外每 5 分钟强制拉取一次 `GET /api/playback/progress` 并回传 `POST /sync/ack` 做周期对账。切歌、播放/暂停即使来自 CLI 或其它客户端，网页也会在下一个同步周期自动跟上。

**设计约定（重要）**：
- 服务端是进度的唯一权威来源，但**只在客户端显式请求时**才返回进度——绝不主动推送。没有 WebSocket / SSE；UDP 广播包只携带服务器发现信息（`name` / `port` / `librarySize` 等），**不含任何播放进度**。
- **同步对账**：服务端每隔 5 分钟检查一次客户端是否仍在同步；若某**活跃播放**会话超过 5 分钟未收到客户端的 `POST /sync/ack` 确认（注意：拉取 `/progress` 本身不计入确认，确认须显式回传 `/sync/ack`），服务端记录一条 WARN 日志。即「除非客户端主动请求，否则服务端不发送进度；但客户端必须周期性回传同步确认，否则服务端会察觉并记日志」。要让网页之外的客户端显示进度，须自行在 5 分钟窗口内至少回传一次 `/sync/ack`。

---

## 运行时文件

| 文件 | 生成时机 | 内容 |
| --- | --- | --- |
| `hellomusic-config.json` | 首次启动 | 服务器配置（端口、库路径、扩展名、上次播放模式） |
| `playlists.json` | 首次创建歌单 | 所有歌单及其歌曲 ID 列表、创建/更新时间 |

两者都写在**当前工作目录**，均已在 `.gitignore` 中忽略，不会污染仓库。

两个文件都采用**原子写入**——先写同目录的临时文件，再整体替换目标文件。因此程序被强制结束或机器断电时，
磁盘上要么保留上一次的完整内容、要么是新写入的完整内容，不会留下半截 JSON 而导致配置或**整个歌单文件**被静默丢弃。

---

## 项目结构

```
HelloMusic/
├── src/main/java/core/
│   ├── HelloMusicApplication.java   # 启动入口：端口桥接、启动横幅
│   ├── ConfigManager.java           # 配置读写（hellomusic-config.json）
│   ├── MusicScannerService.java     # 库扫描：串行遍历 + 指纹比对（增量），并行解析元数据
│   ├── MetadataExtractor.java       # 元数据提取（jaudiotagger + 文件名兜底）
│   ├── Mp3DurationParser.java       # MP3 时长：CBR 估算 + Xing/VBRI 头部
│   ├── MusicFile.java               # 曲目模型
│   ├── MusicMetadata.java           # 标签模型
│   ├── BroadcastService.java        # UDP 广播
│   ├── MusicController.java         # /api 音乐库与系统接口
│   ├── ApiExceptionHandler.java     # 请求体校验失败 → 结构化 400
│   ├── JsonFiles.java               # JSON 原子落盘（临时文件 + 原子替换）
│   ├── Playlist.java                # 歌单模型
│   ├── PlaylistService.java         # 歌单服务（含 JSON 持久化）
│   ├── PlaylistController.java      # /api/playlists 接口
│   ├── PlayMode.java                # 播放模式枚举
│   ├── PlaybackSession.java         # 单会话队列与游标（线程安全）
│   ├── PlaybackService.java         # 多会话管理、模式持久化
│   ├── PlaybackController.java      # /api/playback 接口
│   └── CommandLineInterface.java    # 交互式 CLI
├── src/test/java/core/
│   ├── PlaybackSessionTest.java
│   ├── PlaylistTest.java
│   ├── PlaylistJsonTest.java
│   ├── JsonFilesTest.java
│   ├── MetadataExtractorTest.java
│   ├── Mp3DurationParserTest.java
│   ├── ConfigManagerTest.java
│   ├── MusicControllerConfigEndpointTest.java
│   └── MusicScannerServiceTest.java
├── src/main/resources/static/
│   └── index.html                   # 内置网页播放器（启动后访问 http://localhost:8080/）
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
| Java | 21 | 语言与运行时 |
| Spring Boot | 3.2.0 | Web 框架、依赖注入、定时任务 |
| Spring Boot Web | 随 Boot | 内嵌 Tomcat、REST 接口 |
| Spring Boot Actuator | 随 Boot | 健康检查等运维端点 |
| Bean Validation | 随 Boot | 配置接口的字段校验（Hibernate Validator） |
| Apache Commons IO | 2.15.1 | 路径与扩展名处理 |
| jaudiotagger | 3.0.1 | 音频标签解析（MP3/FLAC/M4A/OGG/WAV） |
| Jackson | 随 Boot | JSON 序列化（已注册 JavaTimeModule） |
| Maven | 3.6+ | 构建与测试 |
| JUnit 5 | 随 Boot | 单元测试 |

---

## 开发与测试

```bash
mvn clean test                          # 运行单元测试
mvn verify                              # 编译 + 测试 + 打包（与 CI 跑的是同一条命令）
mvn test -Dtest=PlaybackSessionTest     # 只跑一个测试类
mvn spring-boot:run                     # 开发模式直接启动
```

单测不依赖任何外部资源：不联网、不读宿主机音乐目录、不往项目目录写文件（需要文件系统的用例一律用 JUnit 的 `@TempDir`），因此在干净的机器上可直接复现。

CI 配置见 `.github/workflows/ci.yml`——推送到 `main`/`master` 或提交 PR 时，用 JDK 21 执行 `mvn -B -ntp verify`，依赖走 `~/.m2` 缓存；构建失败时会把 `target/surefire-reports/` 作为产物上传，便于直接查看报告。

测试位于 `src/test/java/core/`，共 9 个测试类：

| 测试类 | 覆盖点 |
| --- | --- |
| `PlaybackSessionTest` | 四种播放模式的推进与环绕、队列增删清空、防御性拷贝，以及 8 线程 × 2000 次并发压测 |
| `PlaylistTest` | 增删的返回值语义、无实际变更时不刷新 `updatedAt`、`musicIds` 为 null 的兜底、`getMusicIds()` 的只读快照语义，以及读线程 × 4 写线程的并发压测（防 `ConcurrentModificationException`） |
| `JsonFilesTest` | 原子落盘：内容可往返、覆盖是整体替换（旧内容不残留）、不残留临时文件、写入失败时目标文件保持原样 |
| `PlaylistJsonTest` | 歌单 JSON 序列化往返（含 `LocalDateTime`），守住歌单持久化行为 |
| `MetadataExtractorTest` | 标签缺失时按「艺术家 - 标题」解析文件名 |
| `Mp3DurationParserTest` | CBR 估算、Xing(VBR) 头部解析、ID3v2 跳过、异常输入返回 0 |
| `ConfigManagerTest` | `PUT /api/config` 的合并语义（未提交字段保持原值）与字段校验约束 |
| `MusicControllerConfigEndpointTest` | 该接口的接线：非法取值返回 400 与出错字段名、提交的 patch 原样下发、成功后异步触发扫描 |
| `MusicScannerServiceTest` | 递归扫描与扩展名过滤、**并行与串行结果一致**、非法库路径被跳过、文件名兜底、空目录得到空库；增量行为：**未变更文件复用上次的解析结果**（以对象身份断言）、内容或 mtime 变化则重新解析、被删除的文件从库中消失、后加入的文件被下次重扫收录 |

---

## 常见问题

**端口被占用起不来？**
命令行参数优先级高于配置文件，可以临时覆盖：`java -jar target/HelloMusic.jar --server.port=8081`。

**扫描不到文件？**
依次确认：库路径存在且是目录（`config` 命令查看）→ 扩展名在 `supportedExtensions` 里 → 文件确实可读。日志里 `⚠️ Invalid library path` 表示路径无效。

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

**`PUT /api/config` 时漏写某个字段，会把那一项清掉吗？**
不会。该接口是**局部更新**：只覆盖提交了的字段，未提交（或传 `null`）的字段保持原值；取值非法会直接返回 `400` 并说明原因，配置文件不会被写坏。详见[配置说明](#配置说明)。

---

## 安全提示

本项目面向**可信局域网内**使用，默认**没有任何鉴权**，且所有接口都允许跨域（`Access-Control-Allow-Origin: *`）：

- `PUT /api/config` 可以修改音乐库路径与受支持扩展名，配合 `/api/stream/{id}` 可能被用来读取服务器上的任意文件；
- `/api/music`、`/api/config` 会返回服务器上的**绝对路径**。

因此：**不要把它直接暴露到公网**，也不要在不可信的网络里长期运行。如需公网访问，请自行在反向代理层加上鉴权、按来源收敛 CORS，并限制可访问的库路径。

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
HelloMusic - Local music server with UDP broadcast and REST API
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
- [Spring Boot](https://spring.io/projects/spring-boot) —— 应用框架
- [Adoptium](https://adoptium.net/) —— Java 发行版
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) —— 音频标签解析

---

如果这个项目对你有帮助，欢迎给个 Star ⭐
