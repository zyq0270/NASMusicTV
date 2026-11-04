# -*- coding: utf-8 -*-
"""**从源码生成** docs/visualizer-effects-list.md。

数据来源：
  · 枚举 / 门控 / LEGACY_MAP  → `AppSettings.kt`（当前工作树）
  · 「已移除」表的序号与显示名 → git HEAD 里的旧文档（人写的部分，保留）

⛔ 不手抄效果条目；⛔ 已移除表的**序号/显示名**沿用旧文档，缺项从 LEGACY_MAP 补出并标注。

用法：python scripts/gen_effects_doc.py
"""
import io
import re
import subprocess

SRC = 'app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt'
DOC = 'docs/visualizer-effects-list.md'
t = io.open(SRC, encoding='utf-8').read()

# ── 1) 枚举 ────────────────────────────────────────────────────────────────
i = t.find('enum class VisualizerTheme')
j = t.find('\n    companion object', i)
if j < 0:
    j = t.find('\n    }', i)
flat = re.sub(r'\s*\n\s*', ' ', t[i:j])
entries = []
for m in re.finditer(
        r'([A-Z][A-Z_0-9]*)\(\s*R\.string\.(\w+)\s*,\s*"([^"]*)"\s*,\s*Tier\.(\w+)\s*,\s*"(\d+)"'
        r'(?:\s*,\s*needsParticleBudget\s*=\s*(\w+))?\s*\)', flat):
    name, res, disp, tier, no, npb = m.groups()
    entries.append({'no': int(no), 'name': name, 'disp': disp, 'tier': tier, 'npb': npb == 'true'})
entries.sort(key=lambda e: e['no'])

# ── 2) 编译期开关 ─────────────────────────────────────────────────────────
gated = re.findall(r'filterNot \{ it == (\w+) \}', t)
gated_set = set(gated)

# ── 3) LEGACY_MAP ────────────────────────────────────────────────────────
k = t.find('LEGACY_MAP = mapOf(')
l = t.find('\n    )', k)
legacy = [m.group(1) for m in re.finditer(r'"([A-Z_0-9]+)"\s+to\s+(\w+)', t[k:l])]

# ── 4) 旧文档的「已移除」表（保留序号 + 显示名）────────────────────────────
old = subprocess.run(['git', 'show', 'HEAD:' + DOC], capture_output=True, text=True,
                     encoding='utf-8', errors='replace').stdout
# ⛔ 只取「## 已移除效果」小节里的表行（否则会把三张效果表也吸进来）
sec = old.split('## 已移除效果', 1)[-1]
removed_rows = []
for m in re.finditer(r'^\| (E\d+) \| ([A-Z_0-9]+) \| ([^|]+?) \|$', sec, re.M):
    removed_rows.append((m.group(1), m.group(2), m.group(3).strip()))
old_names = {n for _, n, _ in removed_rows}
extra = [n for n in legacy if n not in old_names]
print('枚举条目 %d；已移除表 %d 条；LEGACY_MAP %d 条；补出的早期移除 %d 个: %s'
      % (len(entries), len(removed_rows), len(legacy), len(extra), extra))

# ── 5) 生成 ───────────────────────────────────────────────────────────────
by_tier = {'BASIC': [], 'ADV': [], 'ULTRA': []}
for e in entries:
    by_tier[e['tier']].append(e)


def table(items):
    out = []
    for e in items:
        mark = ' ⚠️' if e['name'] in gated_set else ''
        out.append('| E%02d | %s | %s%s |' % (e['no'], e['name'], e['disp'], mark))
    return '\n'.join(out)


removed_table = '\n'.join('| %s | %s | %s |' % r for r in removed_rows)
if extra:
    removed_table += '\n' + '\n'.join('| — | %s | （早期移除，序号未登记） |' % n for n in extra)

doc = '''# 可视化效果列表

> **版本**：v2.39.10（versionCode 195）
> **更新日期**：2026-11-02
> **来源**：`app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` — `VisualizerTheme` 枚举
> ⛔ 效果条目由 `scripts/gen_effects_doc.py` **从源码生成** ⇒ 改枚举后重跑即可，不要手抄。

共 **%d** 个效果，分三档。档位只决定「在哪种画质下可选」：

- **BASIC** —— 三档画质全可用。
- **ADV** —— 逐项门控：看该效果**是否真的读取** `ctx.quality.maxParticles`（枚举第 6 参 `needsParticleBudget`），
  不按 tier 一刀切 ⇒ 全部 ADV 效果里**只有 `E14 节拍烟花`** 真的需要粒子预算 ⇒ 其余三档全可用。
- **ULTRA** —— `supports()` 要求 `allowFramebuffer`，该字段只有 `HIGH` 为 `true` ⇒ MEDIUM / LOW 一律不提供。

> ⚠️ **本地私有效果**（表中带 ⚠️）：只存在于**本地开发分支**（`feat/e51-private`），**不随公开版发布** ——
> 公开构建里由**编译期开关**从可选列表中整个去掉（见文末「编译期开关」）⇒ 公开包**看不到也切不到**。

增删历史、实现细节、真机验收记录见 `docs/technical-overview.md` §10。

## BASIC 档（%d 套）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
%s

## ADV 档（%d 套）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
%s

## ULTRA 档（%d 套）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
%s

> `E41 世界` 是 WebView + WebGL 的 3D 地球（完全离线）。归 ULTRA 的理由与 E18 不同：2026-10-06 真机故障
> ——**电视端 WebView 只画首帧**，MEDIUM / LOW 用户会看到一块静止的地球，所有者裁决改为仅最高画质档提供（§10.215）。

## 门控的三个特殊项（容易漏）

1. **E39 照片墙是唯一有「列表级」门控的效果**：`VisualizerTheme.selectable(photoWallAvailable)` 在三个照片来源
   开关全关时把 `PHOTO_WALL` 从列表里过滤掉（指示器不显示、左右键也切不到）。其余效果恒在列表里（受画质档约束）。
2. **E14 节拍烟花**：ADV 档里唯一 `needsParticleBudget = true` 的效果 ⇒ `LOW.maxParticles = 0` 时被
   `supports()` 挡下。⚠️ `E41 世界` 同样标了 `needsParticleBudget = true`，但它在 ULTRA 档
   （已按 `allowFramebuffer` 被 HIGH 独占）⇒ 实际只有 E14 受粒子预算影响。
3. **E18 / E41**：见上「ULTRA」口径。⛔ 没有任何「按设备能力隐藏效果」的过滤逻辑（`availableThemes()` 只看画质档 +
   照片来源）⇒ E41 在不支持 WebView 上屏的设备上仍会出现于 HIGH 档。

## 编译期开关（本地私有效果不进公开包）

`VisualizerTheme.publishable()` 在**编译期**按 `BuildConfig` 把本地私有效果从**所有可选列表**里去掉：

| 效果 | 开关 | 公开版行为 |
|---|---|---|
%s

⇒ 这是「**没有这个效果**」而不是「有但坏了」：工厂类 `availableThemes()` 完全委托可选列表，
指示器、左右键、`VisualizerViewModel.step()` 共用同一份 ⇒ 公开版根本切不到。
⚠️ 光堵这一处不够 —— 备份 JSON 里存过效果名的话 `fromKey` 仍会按枚举名解析成功（开关不影响声明），
`VisualizerRendererFactory.create` 还有第二道回落。

## 已移除效果（v2.37.6 移除 11 个；v2.38.2 再移除 9 个；另有 %d 个更早的移除）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
%s

> 老用户存过的已移除主题键经 `AppSettings.kt` 的 `LEGACY_MAP` 自动回落到默认 `CIRCULAR_RING`（圆形频谱环）。
> ⚠️ 清单靠人工维护：`VisualizerThemeTest` 不核对已删名册是否全部进表 ⇒ **删效果时必须手工补 `LEGACY_MAP`**。
> 已移除序号位保留空号，`ordinalLabel` 唯一性不受影响。
''' % (
    len(entries),
    len(by_tier['BASIC']), table(by_tier['BASIC']),
    len(by_tier['ADV']), table(by_tier['ADV']),
    len(by_tier['ULTRA']), table(by_tier['ULTRA']),
    '\n'.join('| ⚠️ %s | `BuildConfig.%s_ENABLED` | 公开版里**不存在** |' % (g, g) for g in gated),
    len(extra), removed_table,
)

io.open(DOC, 'w', encoding='utf-8', newline='\n').write(doc)
print('已生成', DOC, len(doc), 'B')
print('  BASIC %d / ADV %d / ULTRA %d / 已移除 %d(+%d)'
      % (len(by_tier['BASIC']), len(by_tier['ADV']), len(by_tier['ULTRA']), len(removed_rows), len(extra)))
