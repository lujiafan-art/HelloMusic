# HelloMusic - 本地音乐服务器

[![Java](https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](http://makeapullrequest.com)
[![AI Generated](https://img.shields.io/badge/AI-Generated-8A2BE2?logo=openai&logoColor=white)](https://github.com)

> 本项目由 AI 辅助生成 - 基于深度求索（DeepSeek）AI 模型，通过自然语言对话完成完整项目开发

一个轻量级的本地音乐服务器，支持 TCP 广播发现、REST API、播放列表、播放控制和命令行界面。让你的音乐在局域网中随时可用。

---

## 目录

- [特性](#特性)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [API文档](#api文档)
- [播放控制](#播放控制)
- [命令行界面](#命令行界面)
- [网络广播](#网络广播)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 特性

| 功能 | 说明 |
| --- | --- |
| 自动扫描 | 扫描指定目录下的音乐文件，支持 MP3、MKV、FLAC、WAV、M4A、OGG |
| 元数据提取 | 自动提取 MP3 文件的 ID3v1 标签（标题、艺术家、专辑、年份、流派） |
| 网络广播 | 通过 UDP 广播在局域网中自动发现服务，无需手动输入 IP |
| REST API | 完整的 RESTful API，支持跨域 (CORS)，可对接任何前端 |
| 播放列表 | 创建、管理、保存播放列表，数据持久化到 JSON 文件 |
| 播放控制 | 正向播放、逆向播放、单曲循环、随机播放，支持切换 |
| 播放队列 | 管理播放队列，支持添加、清空、查看队列状态 |
| 配置持久化 | 自动保存播放模式等配置，重启不丢失 |
| 命令行界面 | 交互式 CLI，方便服务器管理 |
| 流式播放 | 支持音乐流式传输，可直接在浏览器中播放 |
| 自动更新 | 定时扫描音乐库，自动发现新增文件 |

---

## 快速开始

### 前提条件

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Java | 21 | 下载地址：https://adoptium.net/ |
| Maven | 3.6+ | 下载地址：https://maven.apache.org/ |

### 安装步骤
```bash
git clone https://github.com/yourusername/HelloMusic.git
cd HelloMusic
mvn clean package
java -jar target/HelloMusic.jar
```
## 配置说明

首次运行会自动生成 `hellomusic-config.json` 配置文件。
```
{
"musicLibraryPaths": ["/home/username/Music"],
"serverPort": 8080,
"broadcastPort": 9999,
"broadcastInterval": 30000,
"supportedExtensions": ["mp3", "mkv", "flac", "wav", "m4a", "ogg"],
"serverName": "HelloMusic",
"lastPlayMode": "FORWARD"
}
```

### 配置参数说明

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| musicLibraryPaths | String[] | ["~/Music"] | 音乐库目录路径列表 |
| serverPort | int | 8080 | HTTP 服务端口 |
| broadcastPort | int | 9999 | UDP 广播端口 |
| broadcastInterval | int | 30000 | 广播间隔（毫秒） |
| supportedExtensions | String[] | ["mp3","mkv"] | 支持的文件扩展名 |
| serverName | String | "HelloMusic" | 服务器名称 |
| lastPlayMode | String | "FORWARD" | 上次播放模式（自动保存） |

### 修改配置

方式一：直接编辑配置文件
```bash
vim hellomusic-config.json
```

方式二：通过 API 更新
```bash
curl -X PUT http://localhost:8080/api/config -H "Content-Type: application/json" -d '{"musicLibraryPaths":["/new/path/to/music"]}'
```
方式三：通过 CLI 命令
```bash
addpath /new/path/to/music
scan
```
## API文档

### 基础信息

| 项目 | 说明 |
| --- | --- |
| Base URL | http://localhost:8080/api |
| 响应格式 | JSON |
| CORS | 支持所有来源 |

### 音乐管理

| 方法 | 端点 | 描述 |
| --- | --- | --- |
| GET | /api/music | 获取所有音乐 |
| GET | /api/music/{id} | 获取单个音乐 |
| GET | /api/music/search?q={query} | 搜索音乐 |
| GET | /api/music/artist/{artist} | 按艺术家获取 |
| GET | /api/artists | 获取所有艺术家 |
| GET | /api/stream/{id} | 流式播放音乐 |
| POST | /api/scan | 触发扫描 |

### 播放列表管理

| 方法 | 端点 | 描述 |
| --- | --- | --- |
| GET | /api/playlists | 获取所有播放列表 |
| GET | /api/playlists/{id} | 获取播放列表详情 |
| POST | /api/playlists?name= | 创建播放列表 |
| PUT | /api/playlists/{id} | 更新播放列表 |
| DELETE | /api/playlists/{id} | 删除播放列表 |
| POST | /api/playlists/{id}/songs?musicId= | 添加歌曲到播放列表 |
| POST | /api/playlists/{id}/songs/batch | 批量添加歌曲 |
| DELETE | /api/playlists/{id}/songs/{musicId} | 从播放列表移除歌曲 |
| DELETE | /api/playlists/{id}/clear | 清空播放列表 |
| PUT | /api/playlists/{id}/reorder | 重排序播放列表 |

### 播放控制

| 方法 | 端点 | 描述 |
| --- | --- | --- |
| GET | /api/playback/mode | 获取当前播放模式 |
| POST | /api/playback/mode/switch | 切换播放模式 |
| POST | /api/playback/mode?mode=xxx | 设置播放模式 |
| GET | /api/playback/current | 获取当前播放歌曲 |
| POST | /api/playback/next | 下一首 |
| POST | /api/playback/previous | 上一首 |
| POST | /api/playback/load/all | 加载全部音乐到队列 |
| POST | /api/playback/load | 加载指定列表到队列 |
| GET | /api/playback/queue | 获取播放队列信息 |
| DELETE | /api/playback/queue | 清空播放队列 |
| POST | /api/playback/queue/add?musicId= | 添加歌曲到队列 |
| POST | /api/playback/queue/add/batch | 批量添加到队列 |

### 系统管理

| 方法 | 端点 | 描述 |
| --- | --- | --- |
| GET | /api/stats | 获取服务器统计 |
| GET | /api/config | 获取配置 |
| PUT | /api/config | 更新配置 |
| GET | /api/info | 获取服务器信息 |
| POST | /api/broadcast | 发送广播 |

### 路径参数

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 音乐/播放列表的唯一标识符 |
| musicId | String | 音乐文件的唯一标识符 |
| playlistId | String | 播放列表的唯一标识符 |

### 查询参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| q | String | 是 | 搜索关键词 |
| name | String | 是 | 播放列表名称 |
| mode | String | 是 | 播放模式：FORWARD/REVERSE/SINGLE/SHUFFLE |

---

## 播放控制

### 播放模式

| 模式 | 说明 |
| --- | --- |
| FORWARD | 正向播放（顺序播放，到末尾回到开头） |
| REVERSE | 逆向播放（从后往前播放） |
| SINGLE | 单曲循环（重复播放当前歌曲） |
| SHUFFLE | 随机播放（随机选择下一首） |

### 使用示例
查看当前播放模式

mode
切换播放模式

switch
加载全部音乐到播放队列

loadall
下一首

next
上一首

prev
查看播放队列信息

queue
清空播放队列

clearqueue
text


### 通过 API

切换为随机播放

curl -X POST "http://localhost:8080/api/playback/mode?mode=SHUFFLE"
获取当前播放歌曲

curl http://localhost:8080/api/playback/current
下一首

curl -X POST http://localhost:8080/api/playback/next
text


---

## 命令行界面

启动服务器后，可以在终端中使用交互式 CLI。

### 命令列表

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| help | - | 显示帮助信息 |
| list | [count] | 列出音乐文件 |
| search | query | 搜索音乐 |
| artists | - | 列出所有艺术家 |
| stats | - | 显示服务器统计信息 |
| scan | - | 手动触发库扫描 |
| broadcast | - | 手动发送广播 |
| config | - | 显示当前配置 |
| addpath | path | 添加音乐库路径 |
| removepath | index | 移除音乐库路径 |
| playlists | - | 列出所有播放列表 |
| playlist | id | 显示播放列表详情 |
| createplaylist | name | 创建播放列表 |
| deleteplaylist | id | 删除播放列表 |
| addtoplaylist | pid mid | 添加歌曲到播放列表 |
| removefromplaylist | pid mid | 从播放列表移除歌曲 |
| mode | - | 显示当前播放模式 |
| switch | - | 切换播放模式 |
| next | - | 下一首 |
| prev | - | 上一首 |
| loadall | - | 加载全部音乐到队列 |
| queue | - | 显示播放队列信息 |
| clearqueue | - | 清空播放队列 |
| exit | - | 退出服务器 |

### 使用示例

```
help
list 10
search Taylor
artists
stats
scan
addpath /home/user/Music
createplaylist 我的最爱
playlists
addtoplaylist playlistId musicId
loadall
switch
next
queue
exit
```
## 网络广播

服务器定期向局域网发送 UDP 广播，用于服务自动发现。

| 项目 | 说明 |
| --- | --- |
| 协议 | UDP |
| 端口 | 9999 |
| 目标 | 255.255.255.255（广播地址） |
| 间隔 | 30 秒（可配置） |

### 广播数据格式

```bash
{
"type": "HelloMusic",
"version": "1.0.0",
"name": "HelloMusic",
"port": 8080,
"librarySize": 1250,
"timestamp": 1705315200000,
"api": "/api"
}
text


### 客户端监听示例

import socket
import json

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(('', 9999))

while True:
data, addr = sock.recvfrom(1024)
info = json.loads(data.decode())
print(f"发现音乐服务器: {info['name']} at {addr[0]}:{info['port']}")
```
## 项目结构

```
HelloMusic/
├── src/main/java/com/
│ ├── HelloMusicApplication.java
│ ├── ConfigManager.java
│ ├── MusicFile.java
│ ├── MusicMetadata.java
│ ├── MusicScannerService.java
│ ├── BroadcastService.java
│ ├── MusicController.java
│ ├── Playlist.java
│ ├── PlaylistService.java
│ ├── PlaylistController.java
│ ├── PlayMode.java
│ ├── PlaybackSession.java
│ ├── PlaybackService.java
│ ├── PlaybackController.java
│ └── CommandLineInterface.java
├── src/main/resources/
│ ├── application.properties
│ └── banner.txt
├── pom.xml
├── README.md
└── LICENSE
```

## 技术栈

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 21 | 编程语言 |
| Spring Boot | 3.2.0 | Web 框架 |
| Apache Commons IO | 2.15.1 | 文件操作 |
| Jackson | 最新 | JSON 处理 |
| Maven | 3.6+ | 构建工具 |

## 贡献指南

1. Fork 本项目
2. 创建功能分支
3. 提交更改
4. 推送到分支
5. 打开 Pull Request

## 许可证

本项目采用 MIT 许可证。

## 致谢

- DeepSeek - AI 辅助开发
- Spring Boot - 应用框架
- Adoptium - Java 发行版
