# 社区介绍帖草稿（2026-10-23）

> 用途：把 NASMusicTV 介绍给真实目标用户。三个版本分别对应三类社区，角度不同，勿一稿多投。
> 通用纪律：自曝是作者（各社区对 self-promotion 的硬性要求）；正文不直接求 star；发帖后 24h 内盯评论回复。

---

## 版本 A：Jellyfin 官方论坛 / r/jellyfin（英文）

**适用**：forum.jellyfin.org、reddit.com/r/jellyfin
**角度**：第三方 TV 客户端 + 歌词体验，Jellyfin 用户最关心的接入细节

**标题备选**：
- NAS Music TV — open-source Android TV client for Jellyfin with per-character karaoke lyrics
- I built an Android TV music client for Jellyfin (karaoke lyrics, photo wall, phone remote) — feedback welcome

**正文**：

Hi everyone, I'm the developer of NAS Music TV, an open-source Android music player built primarily for the TV, and I'd like to share it with the Jellyfin community.

It started from a simple need: I wanted a TV client that treats my Jellyfin library as a first-class citizen — with a proper remote-control experience and lyrics that are actually pleasant to read from the couch.

What it does with Jellyfin:

- Browses your Jellyfin library (albums / artists / genres / decades) with HLS streaming and CUE single-file album support
- **Per-character karaoke lyrics** — lyrics highlight word by word in time with the music, the way a karaoke machine does. This is the feature I spent the most time on
- **Photo wall from your Jellyfin picture library** — full-screen slideshow with 43 transitions, optional portraits-only (on-device face detection)
- Phone remote: scan a QR code shown on the karaoke/MV page to browse the queue and add songs from your phone
- 30+ full-screen visualizer effects, including a 3D Earth edition rendered fully offline (WebView + WebGL)
- K-song mode (vocal elimination, pitch shift, speed), Bilibili MV search, weather radio

It's one APK for both TV and phone (runtime device detection), runs on Android 5.1+ — so it works on older TVs too, with heavy effects auto-downgraded. Navidrome / Subsonic backends are supported alongside Jellyfin, and it also handles online music, Baidu Netdisk, and local files if you want those.

Screenshots and download (APK on the Releases page): https://github.com/hxzhang2000/NASMusicTV

I'd love feedback from Jellyfin users — especially anything about library browsing and playback that feels off compared to the web client. Happy to answer questions here.

---

## 版本 B：r/selfhosted（英文）

**适用**：reddit.com/r/selfhosted（注意该版 self-promotion 规则：必须自曝身份、控制频率）
**角度**：自有曲库 + 电视场景，"客户端不是服务端"要说清楚，避免误解

**标题备选**：
- NAS Music TV — Android TV client for your self-hosted music library (Jellyfin/Navidrome/Subsonic), with karaoke lyrics
- Built an Android TV music client for my self-hosted library — feedback welcome

**正文**：

Disclosure up front: I'm the developer.

NAS Music TV is an open-source Android music player for the TV, built around self-hosted libraries. It's a **client, not a server** — it connects to the Jellyfin / Navidrome / Subsonic instances you already run (plus two more NAS backends popular in CN communities), so your library stays exactly where it is.

Why another client: most music clients treat the TV as an afterthought. This one is designed for the 10-foot experience — full D-Pad focus system, HDMI-CEC media keys, a foreground notification, and lyrics you can read from the couch:

- **Per-character karaoke lyrics** (the headline feature — synced word-by-word highlighting)
- Photo wall slideshow pulling from your Jellyfin picture library, with on-device portraits-only filtering
- Phone remote via QR code: add songs to the queue without hunting for the on-screen keyboard
- 30+ offline visualizer effects, K-song mode with vocal elimination and pitch shift, weather radio that matches your library to the actual weather
- Same APK on phone/tablet; Android 5.1+ so older TVs work, with heavy effects auto-downgraded
- Optional extras if you want them: online search, Baidu Netdisk, local files — all independently toggleable, nothing phones home

Screenshots + APK download: https://github.com/hxzhang2000/NASMusicTV

Curious what r/selfhosted would want from a TV music client that isn't there yet — feature requests very welcome.

---

## 版本 C：V2EX「分享创造」（中文）

**适用**：v2ex.com/f/create（节点：分享创造）
**角度**：同好聚会、口语化、突出电视场景与老设备兼容；V2EX 用户吃「细节打磨」这套

**标题**：
[分享创造] NASMusicTV —— 开源 Android TV 音乐播放器，逐字卡拉 OK 歌词 / K 歌 / MTV / 天气电台

**正文**：

如题。NASMusicTV 是我断断续续写了一年多的开源项目，一个主要给电视用的 Android 音乐播放器，手机平板同一个 APK 也能用（运行时自动识别设备类型）。

做它的出发点很简单：想在电视上像点歌台一样看歌词，于是做了**逐字卡拉 OK 歌词**——跟着节奏一个字一个字亮，大屏适配。这个功能是花时间最多的，也是我自己每天用得最多的。

其它比较有特色的点：

- **音乐源全聚合**：Jellyfin / Navidrome / Subsonic / 道理鱼 / 飞牛五种 NAS 后端 + 网络音乐 + 百度网盘 + 本地音乐，跨源并行搜索合并去重
- **K 歌**：人声消除伴奏、原唱/伴奏切换、升降调、变速
- **MTV**：播放页一键进全屏 MV（B 站搜索），切歌自动预搜
- **天气电台**：按实时天气（晴/雨/雪/风/阴/夜晚）从曲库匹配心情电台，没有 NAS 也能用
- **手机遥控**：K 歌 / MTV 全屏页显示二维码，手机扫码看队列、搜索加歌，不用在电视上戳键盘
- 30+ 套全屏可视化特效（含 three-globe 3D 地球版，WebView + WebGL 完全离线渲染）
- minSdk 22，Android 5.1 的老电视也能装，高负载特效自动降级

界面截图和下载（Releases 页 APK）：https://github.com/hxzhang2000/NASMusicTV

欢迎拍砖，尤其想要 TV 端交互和歌词体验的反馈。

---

## 发布顺序与节奏建议

1. **先发 Jellyfin 社区**（论坛或 r/jellyfin 二选一）——受众最精准，Jellyfin 用户就是核心用户群；r/selfhosted 对自荐帖敏感，错开几天再发
2. **V2EX 随时可发**，中文社区没有 self-promotion 规则负担
3. 每帖发完**至少 48 小时内回复所有评论**——早期评论的互动质量直接决定帖子寿命和后续自然流量
4. 有反馈后按反馈迭代，再把更新写进 CHANGELOG，下一轮宣传用「根据社区反馈改了 XX」的名义二次触达，比冷启动有效得多
5. ⛔ 不要三个社区同一天发同一内容；⛔ 不要在正文里求 star（ Reddit/V2EX 都会扣分，star 是自然结果）
