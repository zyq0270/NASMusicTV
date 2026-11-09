# 全库代码审查 · v2.39.16 落地证据（2026-11-09 定稿）

> **归档日期**：2026-11-10（第二十四轮）
> **定稿版本**：v2.39.16 / versionCode 201
> **审查报告正文**：[`../../NASMusicTV-代码审查报告-2026-11-09.html`](../../NASMusicTV-代码审查报告-2026-11-09.html)（154 条：Critical 1 / High 13 / Medium 94 / Low 46）
> **落地记录**：[`../../../technical-overview.md`](../../../technical-overview.md) §10.374
> **版本说明**：[`../../../../CHANGELOG.md`](../../../../CHANGELOG.md) `[v2.39.16]`

本目录只是**证据与可复现材料**：报告本身的结论以上面三个文件为准。
`logs_temp/`（gitignored）里的原件全部保留未动，本目录是**副本**。

## 1. 这轮改了什么

| 项 | 结果 |
|---|---|
| 审查条目 | 154 条全部处理（`verdict` 分布：未送复核的初稿 89 / 复核确认 32 / 复核后收窄 18 / 复核阶段新增 15） |
| 改动文件 | 138 个（+4550 / −991），含 3 个新增源文件、2 个新增测试文件、1 个删除文件 |
| 新增门禁 | `PhotoTransitionClipGateTest`（7 用例）、`LyricsLineChangeExitTest` |
| 验证 | `assembleDebug` ✅ · `testDebugUnitTest` **2906 用例全绿** ✅ · `lintDebug` ✅ · `assembleRelease` ✅ |
| 交付包 | `output/apk/NASMusicTV-release-v2-39-16.apk`（44,413,340 字节，SHA256 `4e5d3de0e125…`） |
| 提交 | `de2f233`（main，本地提交未 push） |

## 2. 三条「审查建议没有照做」的决策（复盘重点）

| 建议 | 处置 | 依据 |
|---|---|---|
| M50 `drawLinPoly` 复用 shader + `setLocalMatrix` | ⛔ **否决，保持逐帧新建** | 与 `TripodFillMechanismGateTest` G2 同一实机红线：创维 E46 / API 22 逐像素实测「多顶点凹/自交路径 + 矩阵变换」80 帧 0 像素 |
| M55 行时长一律 `max(实际, 0.15s)` | ⚠️ **收窄**为「仅 `endTime <= startTime` 时兜底」 | 一刀切会把 50ms 真实短行的 `timingClass` 抬出 `micro` 档（`LyricsStageBridgeTest` 钉住） |
| M16 B 站直链白名单 | ✅ 采纳，**测试同步换真实 CDN 主机** | 原造数用 `example.com`，白名单一上线即红；另补「外部域 / 内网地址」两条负样本 |

## 3. 交付包指纹

```
NASMusicTV-release-v2-39-16.apk
  44,413,340 字节   2026-11-10 00:10
  SHA256 4e5d3de0e12525322684af2f1b6a65fadee1255ec3fdfbbc56a327d66a9e1811
```

同版本号只出过这一包，无「新旧包混淆」风险；装回设备建议 `adb install -r` 或先卸载。

## 4. 落地补丁（按执行顺序，共 30 个）

每个脚本都是**幂等断言式**的字符串替换：先断言锚点唯一、再替换、最后断言新代码在文件里，
失败即 `exit 1`。文件名即执行顺序（22:26 → 23:46）。

| 文件 | 字节 |
|---|---|
| `patch_m34_2026-11-09.py` | 3,923 |
| `patch_n14_2026-11-09.py` | 2,652 |
| `patch_r2_2026-11-09.py` | 17,183 |
| `patch_m46_m52.py` | 4,191 |
| `patch_render_batch2.py` | 14,107 |
| `patch_batchA.py` | 22,125 |
| `patch_batchA2.py` | 4,658 |
| `patch_batchB.py` | 12,208 |
| `patch_batchB2.py` | 11,406 |
| `patch_batchD.py` | 15,494 |
| `patch_batchD2.py` | 7,156 |
| `patch_m37.py` | 14,593 |
| `patch_m38_m39.py` | 8,949 |
| `patch_m42.py` | 6,063 |
| `patch_m44_m45.py` | 8,762 |
| `patch_low1.py` | 16,175 |
| `patch_low2.py` | 6,847 |
| `patch_l17.py` | 9,885 |
| `patch_low3.py` | 12,300 |
| `patch_low4.py` | 18,910 |
| `patch_low4b.py` | 12,812 |
| `patch_low5.py` | 17,303 |
| `patch_low5b.py` | 11,509 |
| `patch_low5c.py` | 6,635 |
| `patch_low5d.py` | 2,884 |
| `patch_fix_companion.py` | 2,429 |
| `patch_fixbuild1.py` | 11,976 |
| `patch_fixbuild2.py` | 1,461 |
| `patch_fixbuild3.py` | 14,804 |
| `patch_version_23916.py` | 6,914 |

> 全部位于 `patches/`。

> 说明：`patch_fixbuild1/2/3` 是**编译/单测反馈**触发的修正（13 个 Kotlin 错误 + 9 处测试契约），
> `patch_fix_companion` 修的是「一个类两个 `companion object`」，
> `patch_version_23916` 是版本号与 CHANGELOG / technical-overview 联动。
> M34 / N14 / R2 等少数条目当时用编辑器直接改，未留脚本（对应 finding 见工单 JSON）。

## 5. 审查报告生成与口径修正工具（共 5 个）

| 文件 | 字节 |
|---|---|
| `gen_review_report_2026-11-09.py` | 108,962 |
| `patch_review_report_2026-11-09.py` | 2,714 |
| `patch_stats_2026-11-09.py` | 2,729 |
| `patch_review_audit_2026-11-09.py` | 3,838 |
| `patch_loc_2026-11-09.py` | 1,395 |

> 全部位于 `report-tools/`。

> `gen_review_report_*` 是单文件 HTML 报告生成器（108 KB，含全部 finding 正文，输出到
> `docs/archive/NASMusicTV-代码审查报告-2026-11-09.html`）。
> 其余四个**不是命令行工具**，而是**一次性**的生成器源码文本补丁（跑一次即已并入生成器）：
> `patch_stats_*` 修统计口径（复核项分布误用全库计数）、`patch_loc_*` 修路径断行、
> `patch_review_audit_*` 修报告自审发现的 6 处缺陷（含被尖括号吃掉的 P0 条目）、
> `patch_review_report_*` 修结构与严重度筛选器。**重跑它们会因锚点已不存在而报断言失败**，
> 保留仅为追溯「报告为什么长这样」。

## 6. 报告自审截图（共 6 张，无头 Edge 分屏）

| 文件 | 字节 |
|---|---|
| `audit_off2600.png` | 261,759 |
| `audit_off6000.png` | 302,258 |
| `audit_road3.png` | 231,017 |
| `audit_road4.png` | 265,037 |
| `audit2_off2600.png` | 265,436 |
| `audit2_off6000.png` | 306,235 |

> 全部位于 `audit/`。

> 用 `--headless=new --screenshot` + 注入 `html{margin-top:-Npx}` 分屏截取（锚点跳转在
> headless 下白屏，负边距法可靠），用于核对粘性目录、严重度筛选、长表格与配色。

## 7. 工单数据

| 文件 | 字节 | 内容 |
|---|---|---|
| `fix-worklist.json` | 68,358 | 154 条 finding 的结构化元数据（`id` / `sev` / `verdict` / `loc` / `title` / `detail` / `sub`），报告与补丁脚本的共同数据源 |

## 8. 复现方式

```bash
# 1) 重新生成报告（需项目根有 fix_worklist.json 的副本 + 源码树）
#    ⚠️ 生成器里已内含 4 个口径/版式补丁，不要重跑 report-tools/ 下的一次性补丁
python logs_temp/gen_review_report_2026-11-09.py        # → docs/archive/NASMusicTV-代码审查报告-2026-11-09.html
# 2) 验证（本机耗时基线：Kotlin 全量 ~7min / 单测 ~8.5min / lint ~13min / release ~11min）
./gradlew.bat testDebugUnitTest --offline "-Pkotlin.compiler.execution.strategy=in-process"
./gradlew.bat lintDebug --offline
./gradlew.bat assembleRelease --offline "-Pkotlin.compiler.execution.strategy=in-process"
```

> 统计口径的核对方式：报告执行摘要里的四组数字（初稿分布 / 复核分布 / 全库终稿 / 新增合并）
> 必须能互相对上；改完生成器要**重跑一遍再人眼比一遍**，否则摘要会自相矛盾（本轮踩过一次）。
