# -*- coding: utf-8 -*-
"""单元测试 9 处失败的处理（第 4 批修复）。

其中 3 类是本轮改动**确实改变了契约**、测试期望需要跟着更新；
另 1 类（M50）是与项目实机红线冲突，应当回退代码而不是改测试。
"""
import io
import sys

ROOT = 'app/src/main/java/com/nasmusic/tv/'
TROOT = 'app/src/test/java/com/nasmusic/tv/'
log = []


def patch(path, pairs, asserts=(), forbid=()):
    with io.open(path, 'r', encoding='utf-8', newline='') as f:
        S = f.read()
    eol = '\r\n' if '\r\n' in S else '\n'
    S = S.replace('\r\n', '\n')
    for old, new in pairs:
        n = S.count(old)
        if n != 1:
            print('FAIL %s: anchor count=%d %r' % (path, n, old[:60]))
            sys.exit(1)
        S = S.replace(old, new, 1)
    for a in asserts:
        if a not in S:
            print('FAIL %s: assert missing %r' % (path, a[:60]))
            sys.exit(1)
    for x in forbid:
        if x in S:
            print('FAIL %s: forbidden present %r' % (path, x[:60]))
            sys.exit(1)
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(S.replace('\n', eol))
    log.append('OK %s' % path.split('/')[-1])


# ───────────────────────────── 1. M50 回退 ─────────────────────────────
# TripodFillMechanismGateTest G2 明确禁止 drawLinPoly 里出现 setLocalMatrix /
# concat / scale / translate / rotate —— 这是 2026-10-13 创维 E46 真机逐像素取证的
# 红线（多顶点凹/自交路径 + 画布变换 ⇒ 80 帧 0 像素）。M50 的"复用 shader + 摆矩阵"
# 恰好踩在这条线上 ⇒ 回退，改不动。
patch(ROOT + 'visualizer/renderers/TripodFogRenderer.kt', [
    ("""    /**
     * M50：[drawLinPoly] 专用的**屏幕空间**单位渐变缓存（与 [shaders] 分开存放）。
     *
     * ⛔ 不能直接改 [shaders] 里那个实例的 localMatrix：它被凸路径（LIN_RECT /
     * RAD_ELLIPSE）按「单位空间 + Canvas 变换」复用，一旦留下屏幕空间的矩阵，
     * 那些图元会被二次变换。
     *
     * 复用方式：单位轴 (0,0)→(1,0) 的渐变建一次，之后每帧只 `setLocalMatrix`
     * 把单位轴映射到指令轴 ⇒ 零新建 Shader（原先每帧约 6 个 native 对象）。
     */
    private val polyShaders = arrayOfNulls<LinearGradient>(shaderSlots)

    /** 建 [polyShaders] 时用的色标数组实例（[gradSlot] 变更为新数组 ⇒ 实例不等 ⇒ 重建） */
    private val polyStopRef = arrayOfNulls<IntArray>(shaderSlots)

    /** [drawLinPoly] 的复用矩阵（单位轴 → 指令轴） */
    private val polyMatrix = Matrix()

    private val scrCol = IntArray(6)""",
     """    private val scrCol = IntArray(6)"""),
    ("""     * ⚠️ 原实现代价：`LinearGradient` 按指令的轴逐条新建（每帧腿 3 + 机身 1 + 光柱 ≤2
     * ≈ 6 个 native 对象）。M50 已改为「每槽一个单位空间渐变 + `setLocalMatrix` 摆轴」，
     * 新建只发生在色标变化的帧。
     */""",
     """     * ⚠️ 代价：色标缓存只能复用到数组，`LinearGradient` 必须按指令的轴逐条新建（每帧腿 3 +
     * 机身 1 + 光柱 ≤2 ≈ 6 个 native 对象）。
     *
     * ⛔ **M50（审查建议"复用 shader + setLocalMatrix 摆轴"）已判定不可采纳**：
     * 那正是上面这条真机红线的同一条路（画布/着色器矩阵承载变换），
     * 护栏 `TripodFillMechanismGateTest` G2 会直接变红。宁可每帧 6 个短命对象。
     */"""),
    ("""        buildScreenPath(cnt, plan.cmdV0[i])
        // M50：着色器按槽复用（只在色标实例变化时重建），轴向交给 localMatrix。
        // 原实现每条指令都 `LinearGradient(ax0, ay0, ax1, ay1, …)` ⇒ 每帧约 6 个
        // native 对象，全部落在渲染线程上。
        var sh = polyShaders[ci]
        if (sh == null || polyStopRef[ci] !== bc) {
            sh = LinearGradient(0f, 0f, 1f, 0f, bc, bp, Shader.TileMode.CLAMP)
            polyShaders[ci] = sh
            polyStopRef[ci] = bc
        }
        // 单位轴 (u,0) → (ax0, ay0) + u·len·(cosθ, sinθ)：
        // Android 的 postX 是「左乘」，所以按 Scale → postRotate → postTranslate 拼出 T·R·S。
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (!(len > 1e-6f)) return
        polyMatrix.setScale(len, 1f)
        polyMatrix.postRotate(Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat())
        polyMatrix.postTranslate(ax0, ay0)
        sh.setLocalMatrix(polyMatrix)
        val pt = paintFor(fillPaint, i)
        pt.shader = sh""",
     """        buildScreenPath(cnt, plan.cmdV0[i])
        val pt = paintFor(fillPaint, i)
        pt.shader = LinearGradient(ax0, ay0, ax1, ay1, bc, bp, Shader.TileMode.CLAMP)"""),
], asserts=['pt.shader = LinearGradient(ax0, ay0, ax1, ay1, bc, bp, Shader.TileMode.CLAMP)'],
   forbid=['polyShaders', 'setLocalMatrix(polyMatrix)'])

# ───────────────────── 2. M55：只在时长非正时兜底 ─────────────────────
patch(ROOT + 'visualizer/renderers/lyricsStage/LyricsStagePayload.kt', [
    ("""        // M55：相邻行时间戳相同或倒序（解析器兜底、坏 LRC）会让 endTime <= startTime
        // ⇒ 行时长为 0/负 ⇒ 逐字揭示的进度分母为 0，第一帧就判「整行唱完」，
        // 高亮直接跳过这一行。这里统一兜到最短行时长，保证单调。
        val endTime = maxOf(endTimeMs / 1000.0, startTime + MIN_LINE_DURATION_SEC)""",
     """        // M55：相邻行时间戳相同或倒序（解析器兜底、坏 LRC）会让 endTime <= startTime
        // ⇒ 行时长为 0/负 ⇒ 逐字揭示的进度分母为 0，第一帧就判「整行唱完」，
        // 高亮直接跳过这一行。这里兜到最短行时长，保证严格单调。
        // ⚠️ 只在**非正时长**时兜底：50ms 的真实短行必须保持 50ms，否则
        // `renderHints.timingClass` 会被抬出 micro 档（`LyricsStageBridgeTest` 钉着这条）。
        val endTime =
            if (endTimeMs > line.time) endTimeMs / 1000.0 else startTime + MIN_LINE_DURATION_SEC"""),
], asserts=['if (endTimeMs > line.time) endTimeMs / 1000.0 else startTime + MIN_LINE_DURATION_SEC'])

# ───────────── 3. 转场池：C46 的 clipPath 降级确实减少了池大小 ─────────────
POOL = TROOT + 'visualizer/photo/PhotoTransitionPoolTest.kt'
patch(POOL, [
    ("""        assertTrue("新平台池里应有 LIQUIFY", PhotoTransitionId.LIQUIFY.ordinal in modern)
        assertTrue("新平台池里也应有 NOISE_DISSOLVE", PhotoTransitionId.NOISE_DISSOLVE.ordinal in modern)
        assertTrue(
            "两条路的池大小应恰好差 1（老平台把 LIQUIFY 去重掉了）：old=${old.size} modern=${modern.size}",
            modern.size - old.size == 1,
        )
        assertTrue(
            "老平台池应是新平台池去掉 LIQUIFY 后的子集",
            old == modern - PhotoTransitionId.LIQUIFY.ordinal,
        )""",
     """        assertTrue("新平台池里应有 LIQUIFY", PhotoTransitionId.LIQUIFY.ordinal in modern)
        assertTrue("新平台池里也应有 NOISE_DISSOLVE", PhotoTransitionId.NOISE_DISSOLVE.ordinal in modern)
        // 老平台（sdk 22）有**两类**降级，二者都表现为池内去重：
        // ① shader 降级：LIQUIFY → degradeTo；
        // ② clipPath 降级（C46）：CLIP_BASED 全部 → CROSSFADE。
        val collapsed = collapsedOnOldPlatform()
        assertTrue(
            "两条路的池大小应恰好差 ${collapsed.size}（老平台把降级项去重掉了）：old=${old.size} modern=${modern.size}",
            modern.size - old.size == collapsed.size,
        )
        assertTrue(
            "老平台池应是新平台池去掉降级项后的子集",
            old == modern - collapsed,
        )"""),
    ("""    /** 池大小可精确核算 ⇒ 「多了 / 少了」都能被发现 */
    @Test
    fun `pool size matches the rules exactly`() {
        // 76 项 − 5 音频反应 = 71；老平台再减去被去重的 LIQUIFY = 70
        assertTrue(
            "老平台池应为 70 项（76 − 5 音频 − 1 降级去重），实际 ${PhotoTransitionId.randomPool(all, 22).size}",
            PhotoTransitionId.randomPool(all, 22).size == 70,
        )
        assertTrue(
            "新平台池应为 71 项（76 − 5 音频），实际 ${PhotoTransitionId.randomPool(all, 33).size}",
            PhotoTransitionId.randomPool(all, 33).size == 71,
        )
    }""",
     """    /** 池大小可精确核算 ⇒ 「多了 / 少了」都能被发现 */
    @Test
    fun `pool size matches the rules exactly`() {
        // 76 项 − 5 音频反应 = 71；老平台在此基础上再折叠降级项（LIQUIFY + clip 类）= 62
        val modern = PhotoTransitionId.randomPool(all, 33)
        assertTrue(
            "新平台池应为 71 项（76 − 5 音频），实际 ${modern.size}",
            modern.size == 71,
        )
        val collapsed = collapsedOnOldPlatform().count { it in modern }
        assertTrue(
            "老平台池应为 ${71 - collapsed} 项（71 − 降级去重 $collapsed），实际 ${PhotoTransitionId.randomPool(all, 22).size}",
            PhotoTransitionId.randomPool(all, 22).size == 71 - collapsed,
        )
    }"""),
    ("""    @Test
    fun `phase overload follows the phase plan`() {
        val p0 = PhotoTransitionId.randomPool(PhotoTransitionId.Phase.P0, sdkInt = 22)
        assertTrue("P0 池应为 15 项（P0 无音频反应类），实际 ${p0.size}", p0.size == 15)

        val p1 = PhotoTransitionId.randomPool(PhotoTransitionId.Phase.P1, sdkInt = 22)
        assertTrue("P1 池应为 41 项（43 − 2 音频反应），实际 ${p1.size}", p1.size == 41)

        val p2 = PhotoTransitionId.randomPool(PhotoTransitionId.Phase.P2, sdkInt = 22)
        assertTrue("P2 池应与完整枚举一致（70），实际 ${p2.size}", p2.size == 70)
    }""",
     """    @Test
    fun `phase overload follows the phase plan`() {
        // 新平台（sdk 33）不触发任何降级 ⇒ 池大小 = 分期项数 − 音频反应类。
        // 老平台（sdk 22）在此基础上再折叠 LIQUIFY 与 clip 类（C46 ⇒ CROSSFADE）。
        val collapsed = collapsedOnOldPlatform()
        for ((phase, expectedModern) in listOf(
            PhotoTransitionId.Phase.P0 to 15,  // P0 里没有音频反应类
            PhotoTransitionId.Phase.P1 to 41,  // 43 − 2 音频反应类
            PhotoTransitionId.Phase.P2 to 71,  // 76 − 5 音频反应类
        )) {
            val modern = PhotoTransitionId.randomPool(phase, sdkInt = 33)
            assertTrue(
                "$phase 新平台池应为 $expectedModern 项，实际 ${modern.size}",
                modern.size == expectedModern,
            )
            val expectedOld = expectedModern - collapsed.count { it in modern }
            val old = PhotoTransitionId.randomPool(phase, sdkInt = 22)
            assertTrue(
                "$phase 老平台池应为 $expectedOld 项（降级去重），实际 ${old.size}",
                old.size == expectedOld,
            )
        }
    }"""),
    ("""    /** 分期越靠后池越大（单调）—— 防止「加了分期但池没扩」 */""",
     """    /**
     * 老平台（`sdkInt < 26`）会把被降级的项折叠掉，池里因此少了这些 ordinal：
     * [PhotoTransitionId.LIQUIFY]（shader 降级）+ 全部 [PhotoTransitionId.CLIP_BASED]
     * （clipPath 降级到 CROSSFADE）。⛔ 写死在断言里会在新增 clip 类时立刻失真。
     */
    private fun collapsedOnOldPlatform(): Set<Int> =
        (setOf(PhotoTransitionId.LIQUIFY) + PhotoTransitionId.CLIP_BASED)
            .map { it.ordinal }
            .toSet()

    /** 分期越靠后池越大（单调）—— 防止「加了分期但池没扩」 */"""),
], asserts=['private fun collapsedOnOldPlatform(): Set<Int>'])

# ───────── 4. B 站直链白名单（M16）：测试改用真实 CDN 主机 ─────────
MV = TROOT + 'backend/network/mv/BilibiliMvServiceTest.kt'
with io.open(MV, 'r', encoding='utf-8', newline='') as f:
    raw = f.read()
eol = '\r\n' if '\r\n' in raw else '\n'
S = raw.replace('\r\n', '\n')
for old, new, cnt in [
    ('https://example.com/video_part1.mp4', 'https://upos-sz-mirrorcos.bilivideo.com/video_part1.mp4', 2),
    ('https://example.com/video_part2.mp4', 'https://upos-sz-mirrorcos.bilivideo.com/video_part2.mp4', 1),
    ('https://example.com/dash_video.m4s', 'https://upos-hz-mirrorakam.akamaized.net/dash_video.m4s', 2),
    ('https://example.com/valid.mp4', 'https://i0.hdslb.com/valid.mp4', 2),
]:
    if S.count(old) != cnt:
        print('FAIL MV: %r count=%d expect=%d' % (old, S.count(old), cnt))
        sys.exit(1)
    S = S.replace(old, new)

INSERT_AFTER = """        assertEquals("https://i0.hdslb.com/valid.mp4", service.extractPlayUrl(body))
    }
"""
NEW_TEST = INSERT_AFTER + """
    @Test
    fun `extractPlayUrl 拒绝非 B 站 CDN 的直链`() {
        // M16：直链最终交给 ExoPlayer 直接联网，而 playurl 响应来自第三方代理端点 ——
        // 没有白名单时，一个被投毒/劫持的响应可以把播放器指向任意地址（含内网与 file://）。
        val body = \"\"\"
            {"code":0,"data":{"durl":[
                {"url":"https://example.com/evil.mp4","size":1},
                {"url":"http://169.254.169.254/latest/meta-data","size":1}
            ]}}
        \"\"\".trimIndent()
        assertNull(service.extractPlayUrl(body))
    }

    @Test
    fun `extractPlayUrl 跳过不可信直链取下一个可信的`() {
        val body = \"\"\"
            {"code":0,"data":{"durl":[
                {"url":"https://evil.example.net/a.mp4","size":1},
                {"url":"https://upos-sz-mirrorcos.bilivideo.com/good.mp4","size":2}
            ]}}
        \"\"\".trimIndent()
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/good.mp4", service.extractPlayUrl(body))
    }
"""
assert S.count(INSERT_AFTER) == 1
S = S.replace(INSERT_AFTER, NEW_TEST, 1)
assert '拒绝非 B 站 CDN 的直链' in S and 'example.com' in S  # 负样本仍用 example.com
with io.open(MV, 'w', encoding='utf-8', newline='') as f:
    f.write(S.replace('\n', eol))
log.append('OK BilibiliMvServiceTest.kt (+2 tests)')

print('\n'.join(log))
print('ALL OK')
