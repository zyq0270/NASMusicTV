# -*- coding: utf-8 -*-
"""v2.39.16：版本升号 + CHANGELOG + 技术总览第 10 节联动。"""
import io
import sys

ROOT = 'D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/'


def rewrite(path, pairs, asserts=()):
    p = ROOT + path
    with io.open(p, 'r', encoding='utf-8', newline='') as f:
        S = f.read()
    eol = '\r\n' if '\r\n' in S else '\n'
    S = S.replace('\r\n', '\n')
    for old, new in pairs:
        n = S.count(old)
        if n != 1:
            print('FAIL %s: count=%d %r' % (path, n, old[:60]))
            sys.exit(1)
        S = S.replace(old, new, 1)
    for a in asserts:
        if a not in S:
            print('FAIL %s: missing %r' % (path, a[:60]))
            sys.exit(1)
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(S.replace('\n', eol))
    print('OK', path)


# ─────────────────────────── 1. 版本号 ───────────────────────────
rewrite('app/build.gradle.kts', [
    ('        versionCode = 200\n        versionName = "2.39.15"',
     '        versionCode = 201\n        versionName = "2.39.16"'),
], asserts=['versionName = "2.39.16"'])

# ─────────────────────────── 2. CHANGELOG ───────────────────────────
CHANGELOG_ENTRY = """## \\[v2.39.16] - 2026-11-09

> 全库代码审查（154 项发现：Critical 1 / High 13 / Medium 94 / Low 46）一次性落地。
> 逐条对照见 `docs/archive/NASMusicTV-代码审查报告-2026-11-09.html`。

### Fixed

- **Critical**：相册转场在 API < 26 上走 `clipPath` 会把硬件渲染进程打崩 —— 枚举层统一降级到 `CROSSFADE`，并新增门禁 `PhotoTransitionClipGateTest` 守住红线（改回 `clipPath` 立即变红）
- 固定转场档位绕过注册表导致黑屏：`effective()` 统一为两级降级（shader → clipPath）
- 相册墙扫描协程的异常被静默吞掉、失败后无法重试（`needScan` 永久丢失）
- 歌词换行把「上一首最后一行 → 下一首第一行」误判成同一行（整曲身份改用单调代数）
- 逐字歌词按 `String.length` 切分会把中文标点 / emoji / 组合字符从中间劈开（改按 code point 切分）
- 行时间戳相同或倒序时行时长为 0，逐字高亮第一帧就跳过整行（兜底最短行时长）
- B 站 MV 直链未做主机白名单：`playurl` 响应被投毒时可把播放器指向任意地址（含 `file://` 与内网）
- 百度网盘目录索引并发构建时重复整树遍历；MV 缓存导入不再无限量入表
- 模型下载的连接不关闭、进度回调每 64KB 抖一次 UI、取消后不复用
- 播放服务换封面时旧 `Bitmap` 不回收（native 内存泄漏）
- 备份恢复路径的数值字段（封面模糊半径 / 暗化 / 字号 / EQ 频段）遇 NaN / Inf 会让整份导入失败
- 百度 OAuth 的 `expires_in` 异常值与非字符串 `refresh_token` 会让刷新永久失败
- 飞牛登录日志打印 token 前缀、Jellyfin GBK 回退日志打印完整 URL（改为代数 / 脱敏）
- CI 打包工作流的密钥脱敏只覆盖 `storePassword`，`cryptoPassphrase` 会被明文打印
- 自定义弹窗未注册 BACK 处理器，遥控器返回键穿透到上一级导航
- `verticalScroll` 内嵌无界 `LazyColumn` 直接崩（日志设置页）；列表项补齐稳定 `key`
- 封面轮播在组合期调用外部回调（收敛到 `LaunchedEffect`）
- 均衡器 / 圆环 / 雷达网格等渲染器每帧新建 `Path` / `Stroke` / 数组；环形与回波线宽改为量化分桶预建

### Changed

- 播放页进度改为 `State` 下沉：每秒 tick 只重组进度条小节，不再重组整张播放页
- 设置页的模型下载进度四条流改为按分区条件订阅（不在「歌词 / K 歌」分区时不建流）
- 流派统计超过 8 类时合并为「其他（N 个流派）」，不再把整页撑长

"""
rewrite('CHANGELOG.md', [
    ('## \\[v2.39.15] - 2026-11-09', CHANGELOG_ENTRY + '## \\[v2.39.15] - 2026-11-09'),
], asserts=['## \\[v2.39.16] - 2026-11-09'])

# ─────────────────── 3. 技术总览 10.374 ───────────────────
DOC = """

### 10.374 v2.39.16 —— 全库代码审查落地（154 项：Critical 1 / High 13 / Medium 94 / Low 46）（2026-11-09）

**范围**：`app/src/main` 全量（91k 行 / 397 文件）+ CI 工作流 + 资源文件。

**方法**：三阶段——① 按子系统并行深审（切互不重叠的文件清单，避免重复读）；② 对全部
Critical/High + 关键 Medium 派**独立怀疑式复核**（砍掉约 1/3 的误报，34 → 13）；
③ 逐条落成代码改动，最后统一编译 + 单测 + lint 验证。逐条对照见
`docs/archive/NASMusicTV-代码审查报告-2026-11-09.html`。

**三条"审查建议被否决"的记录**（避免下次重犯）：

| 建议 | 结论 | 原因 |
|---|---|---|
| M50：`drawLinPoly` 复用 shader + `setLocalMatrix` 摆轴 | ⛔ **不采纳，保持逐帧新建** | 与 `TripodFillMechanismGateTest` G2 的红线同路：创维 E46（API 22）真机逐像素实测「多顶点凹/自交路径 + 矩阵变换」80 帧 0 像素。宁可每帧 6 个短命对象 |
| M55：行时长兜底改成 `max(实际, 0.15s)` | ⛔ 改为**仅在时长非正时**兜底 | 一刀切会抬高高频短行的 `timingClass`，`micro` 档永远取不到（`LyricsStageBridgeTest` 钉住 50ms 短行） |
| M16：B 站直链白名单按"白名单外一律拒绝"实现 | ✅ 采纳，但**测试同步换真实 CDN 主机** | 原测试用 `example.com` 造数，白名单一上线即红；负样本另加两条（外部域 / 内网地址） |

**新增/更新的门禁**：

- `PhotoTransitionClipGateTest`（新）：全仓 `clipPath` 白名单扫描 + `CLIP_BASED` 与实现同步校验 + 降级行为校验（7 个用例）。`DesertSmokeBakes` 的 `cv.clipPath` 因作用在 **Bitmap 的软件 Canvas** 上（不经 hwui）被显式豁免并写明理由
- `LyricsLineChangeExitTest`（新）：切歌/歌词重载时的换行状态机（整曲代数）
- `PhotoTransitionPoolTest`（改）：池大小断言改为**由 `CLIP_BASED` 推导**，新增 clip 类时不再失真
- `TripodFillMechanismGateTest`（既有，本轮成为 M50 的否决依据）

**验证**：`assembleDebug` ✅ / `testDebugUnitTest` 2906 用例全绿 ✅（新增 9 个用例）/ `lintDebug` ✅。

- 版本：**v2.39.16 / versionCode 201**
"""
rewrite('docs/technical-overview.md', [
    ('- 未改：版本号 —— 仍是 **v2.39.15 / versionCode 200**；已交付 APK 不重编',
     '- 未改：版本号 —— 仍是 **v2.39.15 / versionCode 200**；已交付 APK 不重编' + DOC),
], asserts=['### 10.374 v2.39.16'])

print('ALL OK')
