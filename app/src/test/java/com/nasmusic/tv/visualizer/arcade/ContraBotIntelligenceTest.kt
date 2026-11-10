package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.bot.ContraBot
import com.nasmusic.tv.visualizer.arcade.render.ContraArt
import com.nasmusic.tv.visualizer.arcade.render.ContraSpriteFrames
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 2026-11-10 电视实测反馈的 4 个问题 ⇒ 第三轮修复门禁。
 *
 * 1. 自动玩太弱智 / 过不了关 ⇒ bot 上瞄 + 追奖励 + 演示不再 64 s 回标题；
 * 2. 人物倒着跑 ⇒ 上游帧默认朝右，只在 `facing < 0` 时翻转；
 * 3. 背景不全面 ⇒ 纯色矩形山换成上游瓦片的三重视差远景；
 * 4. 歌词没有黑背景 ⇒ GL 侧补通栏歌词背板，HUD 让位到底部。
 *
 * ⛔ 门禁写法沿用项目口径：**语句级**字符串扫描，不用全文否定
 * （注释里解释原因就会自伤），并配行为用例证明不是空转。
 */
class ContraBotIntelligenceTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            if (File(dir, "app/src/main/java/com/nasmusic/tv/visualizer/arcade").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到项目根：user.dir = ${System.getProperty("user.dir")}")
    }

    private fun src(rel: String): String =
        File(projectRoot(), rel).readText(charset = Charsets.UTF_8)

    private fun scene(): String =
        src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/render/ArcadeGlScene.kt")

    private fun simSrc(): String =
        src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/sim/ContraSim.kt")

    private fun botSrc(): String =
        src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/bot/ContraBot.kt")

    // ── 问题 1：智能与过关 ──────────────────────────────────────────────

    @Test
    fun `bot 会对头顶目标按上瞄`() {
        val sim = ContraSim(2026L)
        val bot = ContraBot()
        val input = GameInput()
        var upFrames = 0
        for (i in 0 until 30_000) {
            bot.decide(sim, input)
            if (input.up) upFrames++
            sim.step(input)
        }
        assertTrue(
            "bot 从未上瞄 ⇒ 头顶飞行胶囊 / 高台炮台永远打不到（upFrames=$upFrames）",
            upFrames > 0,
        )
    }

    @Test
    fun `bot 能吃到奖励并升到散弹 且不会越吃越差`() {
        val sim = ContraSim(2026L)
        val bot = ContraBot()
        val input = GameInput()
        for (i in 0 until 60_000) {
            bot.decide(sim, input)
            sim.step(input)
            if (sim.weapon == ContraConst.WPN_S) return
        }
        fail(
            "跑 60000 步仍未吃到任何奖励（weapon=${sim.weapon}, score=${sim.score}）⇒ " +
                "检查 E_ITEM 重力 / chasePickup 是否被卡住检测吃掉",
        )
    }

    @Test
    fun `道具会落地 奖励只升不降`() {
        val s = simSrc()
        assertTrue("E_ITEM 必须计算落地行", s.contains("val floorY = groundRowNear(ex + 6)"))
        assertTrue("E_ITEM 必须有重力", s.contains("eY[i] += 2 * ContraConst.FP_ONE"))
        assertTrue(
            "吃到奖励必须升到散弹且不降级",
            s.contains("if (weapon != ContraConst.WPN_S) weapon = ContraConst.WPN_S"),
        )
        assertFalse("不许回到循环轮换武器", s.contains("weapon = (weapon + 1) % ContraConst.WPN_COUNT"))
    }

    @Test
    fun `自动演示不再 64 秒回标题 而是持续推进关卡`() {
        val game = ContraArcadeGame()
        val snap = ContraSnapshot()
        val input = GameInput()
        // 150 帧标题 + 远超旧 DEMO_FRAMES(3840) 的帧数
        repeat(150 + 6000) { game.step(input, snap) }
        assertFalse(
            "旧口径 3840 帧强制回标题并 reset 到第 1 关 ⇒ 真机上永远看不到过关",
            game.currentMode == ContraConst.MODE_TITLE,
        )
        assertEquals(
            "标题等待缩短到 2.5 s",
            150,
            ContraConst.TITLE_FRAMES,
        )
    }

    @Test
    fun `boss 战必须平射 不许上瞄`() {
        val bot = src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/bot/ContraBot.kt")
        val block = bot.substringAfter("if (sim.bossIsActive) {").substringBefore("updateStuck")
        assertTrue("boss 分支未找到（正则失配 = 门禁空转）", block.isNotEmpty())
        org.junit.Assert.assertFalse(
            "boss 是水平目标；上瞄会让子弹全打飞（实测第 7 关 60 万步过不去）",
            block.contains("input.up = true"),
        )
    }

    @Test
    fun `斜上射击在 sim 里有独立弹道`() {
        val s = simSrc()
        assertTrue("上瞄 + 横向移动必须走斜上", s.contains("val diagonalUp = aimUp && vx != 0"))
        assertTrue("斜上时水平分量不能为 0", s.contains("val dirX = if (aimUp && !diagonalUp) 0 else facing"))
        assertTrue("散弹斜上要有抬升偏置", s.contains("val bias = if (aimUp) -2 else 0"))
    }

    /**
     * 第三轮回归护栏：离线诊断 logs_temp/E54Diag3.java 证明，只要下面三条任意一条倒退，
     * bot 就会在第 7 关反复撞死、60 万步都到不了第 8 关（真机表现为「过不了关」）。
     * 修复后现状：52,641 步走完 8 关 / 92 次死亡，且仍保留上瞄与蹲射。
     */
    @Test
    fun `奖励不能越吃越弱 散弹是净增强且上瞄不吞掉平射`() {
        val s = simSrc()
        assertTrue(
            "散弹平射必须收敛成近水平分层扇面（±3 px/帧 的大角度会飞出敌兵碰撞盒）",
            s.contains("val vxFp = dirX * 5 * ContraConst.FP_ONE"),
        )
        assertTrue("散弹冷却必须是 12 帧", s.contains("fireCooldown = 12"))
        assertFalse(
            "⛔ 冷却 16 帧 + 大角度散弹 = 平射 DPS 低于普通枪，实测第 7 关 60 万步过不去",
            s.contains("fireCooldown = 16"),
        )
        assertTrue(
            "上瞄必须给单发武器补一发水平弹，否则地面小兵完全清不掉",
            s.contains("facing * 6 * ContraConst.FP_ONE, 0"),
        )
        assertTrue(
            "蹲射必须半速推进",
            s.contains("val speed = if (crouch) ContraConst.RUN_SPEED / 2 else ContraConst.RUN_SPEED"),
        )
        assertFalse(
            "⛔ crouch ⇒ speed = 0 会让 bot 永久蹲在原地",
            s.contains("val speed = if (crouch) 0 else"),
        )
    }

    /**
     * 第四轮护栏：离线明细（logs_temp/E54Diag5.java）证明，弹幕触发起跳会让枪口抬高、
     * 水平弹从贴地敌人头顶飞过 ⇒ hp 敌人永远打不掉 ⇒ 「一直在跳、一直在死」。
     * 修正后：60 万步死亡 1235→512、平均存活 7.2s→18.6s、通关 5.2 万步 → 1.46 万步。
     */
    @Test
    fun `贴脸清敌不许起跳 威胁纵窗不得扩到高处`() {
        val b = botSrc()
        assertTrue(
            "有贴脸威胁时必须禁跳（跳起来枪口抬高，水平弹从贴地敌人头顶飞过 ⇒ hp 敌人永远打不掉）",
            b.contains("threat < 0 && incomingBullet(sim)"),
        )
        assertFalse("⛔ threat 纵窗扩到 44 已被实测证伪（contact 死亡 307→697）", b.contains("dy > 44"))
        assertTrue("威胁纵窗必须保持 [−10,26]", b.contains("if (dy > 26 || dy < -10) continue"))
    }

    @Test
    fun `画面横纵各自铺满 不许恢复居中黑边`() {
        val s = scene()
        assertTrue("横向必须铺满（真机左右黑边被裁定不要）",
            s.contains("scaleX = viewportW / (ContraConst.LOGICAL_W * 1.25f)"))
        assertTrue("纵向必须铺满", s.contains("scaleY = viewportH / ContraConst.LOGICAL_H.toFloat()"))
        assertFalse("⛔ 4:3 居中的 offsetX/offsetY 必须撤掉", s.contains("private var offsetX"))
        assertFalse("⛔ 不许恢复按屏高取整数倍的缩放",
            s.contains("viewportH / ContraConst.LOGICAL_H.toFloat()).toInt()"))
    }

    // ── 问题 2：朝向 ────────────────────────────────────────────────────

    @Test
    fun `玩家只在朝左时翻转 上游帧默认朝右`() {
        val s = scene()
        assertTrue(
            "上游 sprite_0d/0e/0f 平射帧枪口朝右 ⇒ 必须 flipX = facing < 0",
            s.contains("flipX = snapshot.playerFacing < 0"),
        )
        assertFalse(
            "⛔ `> 0` 是第二轮误判（真机：倒着跑、人脸向后）",
            s.contains("flipX = snapshot.playerFacing > 0"),
        )
    }

    // ── 问题 3：背景 ────────────────────────────────────────────────────

    @Test
    fun `远景全部由上游瓦片拼成 不再是纯色矩形山`() {
        val s = scene()
        assertTrue("必须有新的背景层", s.contains("private fun drawBackdrop"))
        assertFalse("旧的纯色矩形视差层必须撤掉", s.contains("private fun drawParallax"))
        val refs = Regex("ContraArt\\.BG_[A-Z_]+").findAll(s).map { it.value }.toSet()
        assertTrue("背景层至少用 5 种上游瓦片槽位（实际 ${refs.size}）", refs.size >= 5)
        val names = ContraSpriteFrames.names()
        for (n in listOf(
            ContraArt.BG_HILL, ContraArt.BG_CANOPY, ContraArt.BG_CANOPY_ALT,
            ContraArt.BG_TRUNK, ContraArt.BG_BRUSH, ContraArt.BG_FALL,
            ContraArt.BG_BASE, ContraArt.BG_BASE_ALT,
        )) {
            assertTrue("背景槽位 $n 必须是上游帧表里真实存在的瓦片", n in names)
        }
    }

    // ── 问题 4：歌词黑背景 ──────────────────────────────────────────────

    @Test
    fun `GL 侧有通栏歌词背板 且 HUD 让位到底部`() {
        val s = scene()
        assertTrue("必须有独立歌词背板", s.contains("private fun drawLyricScrim"))
        assertTrue("背板必须是可见的深黑（不是 0.35 淡框）", s.contains("0xCC000000"))
        assertTrue("背板要在画面组装里被真正调用", s.contains("drawLyricScrim(white)"))
        assertTrue("HUD 必须移到画面底部，别和歌词挤在一起", s.contains("quad(0f, 226f"))
        assertFalse("顶部 HUD 半透明条必须撤掉", s.contains("quad(0f, 0f, ContraConst.LOGICAL_W.toFloat(), 12f"))
    }
}
