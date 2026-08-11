# MultiLyrics — Salt Player for Windows 多平台歌词插件

为 [Salt Player for Windows](https://store.steampowered.com/app/3009140) 创意工坊开发的在线歌词搜索插件，基于 [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)。

播放本地音乐时，自动按曲目标签（标题 / 艺术家 / 专辑）从多个主流音乐平台搜索并加载逐字 / 翻译歌词，作为 SPW 默认歌词加载逻辑的补充或替代。

## 支持平台

| 来源 | 歌词格式 | 翻译 | 罗马音 | 备注 |
|------|----------|------|--------|------|
| Apple Music (AMLL) | TTML 逐字 | ✔ | ✔ | 基于 AMLL TTML DB |
| QQ 音乐 | QRC 逐字 | ✔ | ✔ | 需要注意接口可用性 |
| 网易云音乐 | YRC 逐字 | ✔ | ✔ | 含逐字与翻译 |
| 酷狗音乐 | KRC 逐字 | ✔ | ✔ | 需解密 KRC |
| 酷我音乐 | LRC 行级 | — | — | 行级同步 |
| Spotify (lrclib) | LRC 行级 | ✔ | — | 通过 lrclib 聚合库 |

按优先级依次搜索，命中后即返回。可通过配置界面单独启用 / 禁用各来源。

## 功能特性

- **多平台聚合搜索**：依次尝试 Apple Music → QQ → 网易云 → 酷狗 → 酷我 → Spotify，命中即停。
- **智能匹配**：基于标题 / 艺术家 / 专辑的相似度评分，自动选择最佳候选。
- **逐字歌词**：支持 QRC / YRC / KRC / TTML 等逐字格式，编码为 SPW 增强型 LRC。
- **翻译与罗马音**：可按需附加翻译 / 罗马音副歌词行。
- **本地缓存**：内存 LRU + 磁盘持久化，30 天 TTL，避免重复联网。
- **优先 / 兜底模式**：可在 SPW 默认歌词之前接管（优先），或仅在默认逻辑未命中时兜底。
- **不依赖 `java.net.http`**：使用 `HttpURLConnection`，兼容 SPW 精简运行时。

## 构建方法

### 环境要求

- JDK 21（与 SPW 运行时一致）
- Gradle 8.x（项目自带 wrapper，也可使用系统 Gradle）
- 网络可访问 Maven Central / JitPack（或配置阿里云镜像）

### 编译打包

```bash
# 编译 Kotlin 源码
gradle compileKotlin

# 打包 SPW 创意工坊插件 ZIP
gradle plugin
```

产物位于 `build/plugins/MultiLyrics-<version>.zip`。

### 验证运行时兼容性

```bash
gradle verifyRuntimeCompatibility
```

该任务会扫描编译产物，拒绝引用 `java.net.http`（SPW 运行时未包含该模块）。

## 安装方法

1. 将 `MultiLyrics-<version>.zip` 复制到 SPW 创意工坊插件目录：
   - Windows: `%LOCALAPPDATA%\Salt Player for Windows\workshop\plugins\`
2. 重启 Salt Player for Windows。
3. 在 SPW 设置 → 创意工坊中确认插件已启用。
4. 进入插件配置页调整来源与选项。

## 配置项

| 配置 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| 启用在线歌词搜索 | 开关 | 开 | 总开关，关闭后不返回任何歌词 |
| 优先模式 | 开关 | 关 | 开启后在 SPW 默认歌词之前接管；关闭则作为兜底 |
| 显示加载提示 | 开关 | 关 | 加载成功时弹出 Toast 提示歌词来源 |
| 单次搜索超时 | 滑块 | 8 秒 | 3–20 秒可调 |
| Apple Music (AMLL) | 开关 | 开 | AMLL TTML 逐字歌词 |
| QQ 音乐 | 开关 | 开 | QRC 逐字歌词 |
| 网易云音乐 | 开关 | 开 | YRC 逐字歌词 |
| 酷狗音乐 | 开关 | 开 | KRC 逐字歌词 |
| 酷我音乐 | 开关 | 开 | LRC 行级歌词 |
| Spotify (lrclib) | 开关 | 开 | lrclib 聚合歌词 |
| 显示翻译 | 开关 | 开 | 附加翻译副歌词行 |
| 显示罗马音 | 开关 | 关 | 附加罗马音副歌词行 |
| 清除搜索缓存 | 按钮 | — | 清除本次会话的内存搜索记忆 |

> 翻译 / 罗马音副歌词需在 SPW 主设置中开启“翻译显示 / 歌词翻译”才会显示。

## 架构概览

```
com.spw.multilyrics
├── MultiLyricsPlugin           # 插件入口（SpwPlugin）
├── MultiLyricsExtension        # PlaybackExtensionPoint 实现
├── PluginRuntime               # 运行时装配与歌词加载编排
├── PluginSettings              # 配置读取
├── domain/
│   ├── Models.kt               # LyricsDocument / LyricLine / LyricWord
│   ├── LyricsSource.kt         # 来源枚举与优先级
│   ├── TrackQuery.kt           # 搜索请求与候选模型
│   ├── MatchEngine.kt          # 相似度匹配引擎
│   └── TextNormalizer.kt       # 文本归一化（去版本号、拆艺术家等）
├── provider/
│   ├── LyricsProvider.kt       # 来源抽象接口
│   ├── HttpClient.kt           # 基于 HttpURLConnection 的 HTTP 客户端
│   ├── AppleMusicProvider.kt   # AMLL TTML DB
│   ├── QqMusicProvider.kt      # QQ 音乐（QRC）
│   ├── NeteaseProvider.kt      # 网易云（YRC）
│   ├── KugouProvider.kt        # 酷狗（KRC）
│   ├── KuwoProvider.kt         # 酷我（LRC）
│   └── SpotifyProvider.kt      # lrclib 聚合
├── codec/
│   ├── LyricCodec.kt           # 编解码接口
│   ├── LrcCodec.kt             # LRC 解析
│   ├── YrcCodec.kt             # 网易云 YRC
│   ├── QrcCodec.kt             # QQ QRC
│   ├── KrcCodec.kt             # 酷狗 KRC（含解密）
│   ├── TtmlCodec.kt            # Apple Music TTML
│   ├── SpwLyricsEncoder.kt     # 统一编码为 SPW 增强型 LRC
│   └── SecondaryLyricsAligner.kt # 翻译/罗马音对齐
├── search/
│   └── LyricsResolver.kt       # 多来源协调与决策
└── storage/
    └── LyricsCache.kt          # 内存 LRU + 磁盘缓存
```

### 歌词加载流程

1. SPW 播放歌曲时触发 `PlaybackExtensionPoint.onBeforeLoadLyrics` / `onAfterLoadLyrics`。
2. `PluginRuntime` 将 `MediaItem` 转为 `TrackQuery`，先查本地缓存。
3. 缓存未命中时，`LyricsResolver` 按来源优先级依次搜索。
4. 每个来源返回候选列表，`MatchEngine` 按标题 / 艺术家 / 专辑相似度抉择。
5. 命中后拉取歌词原文，经对应 `Codec` 解析为 `LyricsDocument`。
6. `SpwLyricsEncoder` 编码为 SPW 增强型 LRC 文本返回给 SPW。
7. 结果写入磁盘缓存（30 天 TTL）。

## 缓存位置

- Windows: `%LOCALAPPDATA%\MultiLyrics\cache\`
- 其他: `~/.multilyrics/cache/`（按 user.home 推断）

缓存为 JSON 格式，可手动删除以强制重新搜索。

## 致谢

本插件参考了以下开源项目：

- [spw-amll-connector](https://github.com/lzlzl007/spw-amll-connector) — AMLL 本地播放桥接
- [SPW-Lyric-Plugin](https://github.com/Casper-003/SPW-Lyric-Plugin) — SPW 在线歌词插件
- [spw-online-lyrics](https://github.com/XueKirby/spw-online-lyrics) — 网易云在线歌词
- [SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics) — 多来源歌词插件
- [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) — SPW 创意工坊 API

## 许可证

见 [LICENSE](LICENSE)。
