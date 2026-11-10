# E55「街机合集」素材归属登记（NOTICE）

> 本文件按 `docs/archive/e54-contra-porting-plan.md` §3.3「许可与归属登记」要求，登记**随 APK 分发的图形素材**出处。
> ⚠️ 事实必须如实记录：⛔ 不把「所有者裁决豁免」写成「许可合规」，⛔ 不删除版权事实。

## 1. 素材清单（v2.39.18 起随公开 APK 分发）

| 类别 | 数量 | 原始体积 | 来源仓库 | 路径 | 归属 |
|---|---:|---:|---|---|---|
| 精灵 PNG | **232 张**（+1 GIF 未用） | 48.9 KB | `vermiceli/nes-contra-us` | `docs/sprite_library/contra_sprites/` | NES ROM 提取图，**版权归原始权利人所有** |
| 关卡瓦片 | **256 块 8×8** | `tiles_c.h` 183,756 B | `MikeWuPing/UEFI_Contra` | `src/tiles_c.h` 的 `gNesTiles[256][64]` | Contra NES level 1 pattern table，ROM 提取 |
| HUD 文字帧 | 20 帧 | 运行期 Canvas 绘制 | 本项目 | `ArcadeAtlas.TEXT_FRAMES` | 本项目自写（**不是精灵**，只是数字/字母） |

精灵帧名与 `nes-contra-us` 的 `src/bank1.asm` 中 `sprite_ptr_tbl_0/1` 标签一一对应；
每帧语义（player walking / soldier running / boss eye …）照抄该表的注释，登记在
`app/src/main/java/com/nasmusic/tv/visualizer/arcade/render/ContraArt.kt` 的类 KDoc。

## 2. 许可事实（逐条，⛔ 不合并改写）

- `vermiceli/nes-contra-us`：GitHub API 实测 `license = null`、`/license` 返回 404 ⇒ **无开源许可证**。
  该仓 `docs/sprite_library/README.md` 自述这些图 "were extracted from the ROM for ease of viewing"，
  且**不参与游戏代码运行**。
- `MikeWuPing/UEFI_Contra`：**仓库内无 LICENSE 文件**（README「开源许可」章节自称 MIT）；
  `tiles_c.h` 首行注释自述 "Contra NES tiles - level 1 pattern table" ⇒ ROM 图形字节。
- 《Contra》为 **Konami 商业 IP**；上游 README 第 195 行自述「NES 反汇编参考代码及精灵图片……版权归原始权利人所有」。
- ⇒ 法律口径仍是 **all-rights-reserved**；这些素材**不随任何 MIT 声明转移授权**。
- 所有者 **2026-11-08 裁决**：「完全可以继续移植后开源」⇒ 不阻塞；**2026-11-10 追加裁决**：
  「你自绘的肯定不好看，赶快从开源仓库中都把精灵移植过来，就用上游的，全部都拿过来，不要自绘」；
  **同日第三次裁决**：「魂斗罗应该是公开的，在 main 分支下的」⇒ 实现、图集与本 NOTICE 全部入 `main`，
  公开构建**默认带**该效果（`arcade.enabled` 缺省 true ⇒ 公开版可选效果 26 → 27 套）。
  ⚠️ 这是**裁决豁免**，不是「许可合规」。⛔ 不再复议，也⛔ 不得在文档中把两者混写。

## 3. 分发边界（⛔ 最高优先级）

| 位置 | 是否入仓 | 是否进 APK | 说明 |
|---|---|---|---|
| `app/src/e54-upstream/nes-contra-us/`（232 PNG + bank1.asm + README） | **否**（`.gitignore`） | 否 | 本机对账基线，main 源集之外 |
| `app/src/e54-upstream/uefi_contra/`（含 `tiles_c.h`） | **否**（`.gitignore`） | 否 | 本机移植对账基线 |
| `app/src/main/assets/e54_arcade/contra_atlas.png` | **是**（`main`，v2.39.18 起） | **是** | 489 帧烘焙图集，**唯一进包的素材文件** |
| `scripts/e54_pack_sprites.py` | **是** | 否 | 生成器（依赖 Pillow），上游原件一变就重跑 |

- ⛔ **上游原件仍然不入仓、不可再分发**：`app/src/e54-upstream/` 保持 `.gitignore`，
  只在私有分支本机作「移植对账基线」；公开仓里**只有上面那张烘焙图集**。
- ⭐ **v2.39.18 起，本图集随公开 APK 分发**（所有者 2026-11-10 第三次裁决「魂斗罗应该是公开的，
  在 main 分支下的」）⇒ `arcade.enabled` 缺省 true ⇒ 公开版**可选效果 = 27 套**（= 29 − E51 − E52）。
- ⛔ 不许把 232 张 PNG 散着塞进 `app/src/main/assets/`（门禁
  `UpstreamSpriteAtlasTest.assets 里只有一张烘焙图集 不塞 ROM 原件` 判红）。
- ⚠️ 法律事实不因公开分发而改变：**all-rights-reserved 的素材进入了公开分发**，
  依据是**所有者的裁决豁免**，不是上游授权。⛔ 本文件不构成任何授权；
  若日后上游或权利人提出异议，改回可合规素材（或关掉 `arcade.enabled`）是既定退路。

## 4. 生成器与可复算性

```
python -m pip install pillow
python scripts/e54_pack_sprites.py
```

产物：`app/src/main/assets/e54_arcade/contra_atlas.png`（1024×1024 RGBA，55,839 B）
与 `app/src/main/java/.../render/ContraSpriteFrames.kt`（489 行帧表，GENERATED，⛔ 不要手改）。
门禁 `UpstreamSpriteAtlasTest` 自证「232 + 256 + 白块一帧不少」、帧矩形不越界、渲染器不再引用自绘帧名。

⚠️ **公开仓里跑不了这个生成器**：它的输入是 `app/src/e54-upstream/` 下的上游原件，
而那部分 **⛔ 不入仓**（见 §3）⇒ 克隆公开仓后只能拿到**烘焙产物**，不能重新生成。
需要重跑时，在**本机私有分支**上（有上游原件的那台机器）跑，再把产物同步回 `main`。
