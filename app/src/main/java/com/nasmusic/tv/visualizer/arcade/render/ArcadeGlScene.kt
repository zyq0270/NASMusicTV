package com.nasmusic.tv.visualizer.arcade.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.nasmusic.tv.visualizer.arcade.ArcadeInputQueue
import com.nasmusic.tv.visualizer.arcade.ContraSnapshot
import com.nasmusic.tv.visualizer.arcade.GameInput
import com.nasmusic.tv.visualizer.arcade.ContraArcadeGame
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 街机合集的 GLES 渲染场景（方案 §4.6 W7）。
 *
 * ## 缩放口径（§4.6.1，第四轮修订）
 * 逻辑坐标恒 **256×240**（与 sim / 上游像素坐标一一对应）；顶点横向 ×**1.25** ⇒ 等效画幅 320×240。
 * ⛔ 整数倍 k + 4:3 居中已撤销：所有者 2026-11-10 裁定「地面和背景应该横向占满全屏」
 * ⇒ 横纵各自铺满（`scaleX = viewportW/320`、`scaleY = viewportH/240`），
 * 16:9 电视上精灵横向拉伸 ≈1.33 倍、**无左右黑边**（GL_NEAREST 逐像素取点）。
 *
 * ## 一次 draw call
 * 天空、地形、精灵、HUD 全部落在**同一张图集**里（无贴图部分用白块帧），
 * 因此整帧只有一次绑定 + 一次 `glDrawArrays`（G6110 上带宽/状态切换是瓶颈）。
 * ⛔ 精灵与地形瓦片**全部来自上游 ROM 提取素材**（[ContraArt] 映射），本项目一张自绘精灵都没有。
 *
 * ## 固定步
 * 60 步/秒，单帧最多追 3 步后**丢弃余量**（避免切回前台时"追帧雪崩"）。
 */
class ArcadeGlScene(
    private val appContext: android.content.Context,
    private val game: ContraArcadeGame,
    private val snapshot: ContraSnapshot,
    private val queue: ArcadeInputQueue,
) : GLSurfaceView.Renderer {

    private val atlas = ArcadeAtlas(appContext)

    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var aColor = 0
    private var uViewport = 0
    private var uTex = 0

    private var vbo = 0
    private var quadCursor = 0

    private val verts = FloatArray(MAX_QUADS * VERTS_PER_QUAD * FLOATS_PER_VERT)
    private lateinit var vertexBuffer: FloatBuffer

    private var viewportW = 1
    private var viewportH = 1

    /** 逻辑 → 屏幕的缩放：横纵各自铺满（⛔ 不要恢复 4:3 居中 —— 真机左右黑边被裁定不要） */
    private var scaleX = 1f
    private var scaleY = 1f

    // ── 固定步 ──
    private var lastNanos = 0L
    private var accumulator = 0.0
    private val input = GameInput()

    // ── FPS（渲染层自报，⛔ 不是 Choreographer 口径）──
    private var fpsFrames = 0

    @Volatile
    private var fpsValue = 0f

    fun currentFps(): Float = fpsValue

    // ── GLSurfaceView.Renderer ───────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUV")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uViewport = GLES20.glGetUniformLocation(program, "uViewport")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]

        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        atlas.upload()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        Log.i(TAG, "GL 场景就绪（program=$program）")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = maxOf(1, width)
        viewportH = maxOf(1, height)
        GLES20.glViewport(0, 0, viewportW, viewportH)
        // 横纵各自铺满：16:9 电视上不再有左右黑边（第四轮所有者裁定）。
        // 逻辑 256×240，顶点横向 ×1.25 ⇒ 逻辑宽 320；精灵会横向拉伸 ≈1.33 倍。
        scaleX = viewportW / (ContraConst.LOGICAL_W * 1.25f)
        scaleY = viewportH / ContraConst.LOGICAL_H.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        advanceSim()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        buildQuads()
        flush()
        measureFps()
    }

    // ── 固定步推进 ──────────────────────────────────────────────────────────

    private fun advanceSim() {
        val now = System.nanoTime()
        if (lastNanos == 0L) {
            lastNanos = now
            return
        }
        val dt = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        accumulator += dt.coerceAtMost(0.25)
        val stepSec = 1.0 / ContraConst.STEP_HZ
        var steps = 0
        while (accumulator >= stepSec && steps < MAX_CATCH_UP_STEPS) {
            tickOnce()
            accumulator -= stepSec
            steps++
        }
        if (steps >= MAX_CATCH_UP_STEPS) accumulator = 0.0
    }

    private fun tickOnce() {
        input.clear()
        if (queue.drainStart()) input.start = true
        game.step(input, snapshot)
    }

    private fun measureFps() {
        if (lastFpsStamp == 0L) {
            lastFpsStamp = System.nanoTime()
            fpsFrames = 0
            return
        }
        fpsFrames++
        val now = System.nanoTime()
        val dt = (now - lastFpsStamp) / 1_000_000_000f
        if (dt >= 1f) {
            fpsValue = fpsFrames / dt
            fpsFrames = 0
            lastFpsStamp = now
        }
    }

    private var lastFpsStamp = 0L

    // ── 顶点批次 ────────────────────────────────────────────────────────────

    private fun resetBatch() {
        quadCursor = 0
    }

    /**
     * 追加一个四边形（**逻辑坐标**）。
     * @param color 0xAARRGGBB（alpha 参与混合）
     */
    private fun quad(
        x: Float, y: Float, w: Float, h: Float,
        frame: ArcadeAtlas.Frame?, color: Int,
        flipX: Boolean = false,
    ) {
        if (frame == null || quadCursor >= MAX_QUADS) return
        val sx = x * 1.25f * scaleX
        val sy = y * scaleY
        val sw = w * 1.25f * scaleX
        val sh = h * scaleY

        val a = ((color ushr 24) and 0xFF) / 255f
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val u0 = if (flipX) frame.u1 else frame.u0
        val u1 = if (flipX) frame.u0 else frame.u1

        var o = quadCursor * VERTS_PER_QUAD * FLOATS_PER_VERT
        val v = verts
        // 三角形 1：左上、左下、右上
        put(v, o, sx, sy, u0, frame.v0, r, g, b, a); o += FLOATS_PER_VERT
        put(v, o, sx, sy + sh, u0, frame.v1, r, g, b, a); o += FLOATS_PER_VERT
        put(v, o, sx + sw, sy, u1, frame.v0, r, g, b, a); o += FLOATS_PER_VERT
        // 三角形 2：右上、左下、右下
        put(v, o, sx + sw, sy, u1, frame.v0, r, g, b, a); o += FLOATS_PER_VERT
        put(v, o, sx, sy + sh, u0, frame.v1, r, g, b, a); o += FLOATS_PER_VERT
        put(v, o, sx + sw, sy + sh, u1, frame.v1, r, g, b, a); o += FLOATS_PER_VERT
        quadCursor++
    }

    private fun put(
        v: FloatArray, o: Int,
        x: Float, y: Float, u: Float, vv: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        v[o] = x
        v[o + 1] = y
        v[o + 2] = u
        v[o + 3] = vv
        v[o + 4] = r
        v[o + 5] = g
        v[o + 6] = b
        v[o + 7] = a
    }

    private fun flush() {
        if (quadCursor == 0 || program == 0) return
        val count = quadCursor * VERTS_PER_QUAD * FLOATS_PER_VERT
        vertexBuffer.clear()
        vertexBuffer.put(verts, 0, count)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uViewport, viewportW.toFloat(), viewportH.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlas.textureId)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, count * 4, vertexBuffer, GLES20.GL_DYNAMIC_DRAW)

        val stride = FLOATS_PER_VERT * 4
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, 8)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, 16)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, quadCursor * VERTS_PER_QUAD)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // ── 画面组装 ────────────────────────────────────────────────────────────

    private fun buildQuads() {
        resetBatch()
        val white = atlas.whiteFrame()

        drawSky(white)
        val horizon = horizonY()
        drawBackdrop(white, horizon)
        drawTerrain()
        drawEnemies()
        drawBoss()
        drawBullets()
        drawPlayer()
        drawHud()
        drawLyricScrim(white)
        drawOverlays()
    }

    private fun drawSky(white: ArcadeAtlas.Frame) {
        // 纵向 4 段渐变（顶点色内插 ⇒ 段内平滑，段间色差极小）
        val segments = 4
        val h = ContraConst.LOGICAL_H / segments
        for (i in 0 until segments) {
            val t = i / (segments - 1).toFloat()
            val c = lerpColor(snapshot.skyTop, snapshot.skyBottom, t)
            quad(0f, (i * h).toFloat(), ContraConst.LOGICAL_W.toFloat(), h.toFloat(), white, c)
        }
        // ⛔ 不放「白条云」：离线 mock（logs_temp/e54_r3_scene_mock.py）实测无论
        //    0.19 还是 0.35 alpha，都会变成两条突兀的灰白矩形；Contra level 1 的天空
        //    本来就是干净的深蓝渐变 ⇒ 原著感由下面的上游瓦片丛林 / 瀑布提供，不靠假云。
    }

    /** 可见区域内地形的最高地面行（像素 y）—— 视差剪影的下缘 */
    private fun horizonY(): Int {
        val cols = snapshot.terrainCols
        if (cols <= 0) return ContraConst.LOGICAL_H - 48
        val startCol = (snapshot.camX / ContraConst.TILE).coerceAtLeast(0)
        val endCol = ((snapshot.camX + ContraConst.LOGICAL_W) / ContraConst.TILE).coerceAtMost(cols - 1)
        var best = ContraConst.ROWS - 1
        for (c in startCol..endCol) {
            for (r in 0 until ContraConst.ROWS) {
                val code = snapshot.terrain[c * ContraConst.ROWS + r].toInt()
                if (code == ContraConst.C_FLOOR || code == ContraConst.C_SOLID) {
                    if (r < best) best = r
                    break
                }
            }
        }
        return best * ContraConst.TILE
    }

    /**
     * 原著风远景：⛔ **不再用纯色矩形当山**（第二轮的真实反馈「背景不全面、跟原著不一样」）。
     * 全部改用上游 `tiles_c.h` 的 256 块 level-1 瓦片拼三重视差：
     * 远山(0.18×) → 树干/树冠(0.45×) → 近景灌木(1×)，另加固定屏位的瀑布。
     * 第 5 关起把树冠换成同一套瓦片里的网格块 ⇒ 基地关的金属墙质感。
     * 颜色只做「乘色压暗」（片元是 texture2D * vColor），⛔ 不把真图洗成纯色板。
     */
    private fun drawBackdrop(white: ArcadeAtlas.Frame, horizon: Int) {
        val camX = snapshot.camX
        val lvl = snapshot.level
        val metal = lvl >= 5

        // ① 远山：细碎噪点瓦片染暗，最慢视差
        val hill = atlas.frame(ContraArt.BG_HILL)
        val farShift = (camX * 0.18f).toInt() % 16
        for (i in -1 until 18) {
            val x = i * 16 - farShift
            val h = 10 + ((i * 7 + lvl * 11) % 14)
            quad(x.toFloat(), (horizon - h - 6).toFloat(), 16f, h.toFloat(), hill, 0xFF4E624E.toInt())
        }

        // ② 中景：棕色竖纹树干 + 浓密叶纹树冠（基地关 ⇒ 网格金属墙）
        val trunk = atlas.frame(ContraArt.BG_TRUNK)
        val canopy = atlas.frame(if (metal) ContraArt.BG_BASE else ContraArt.BG_CANOPY)
        val canopyAlt = atlas.frame(if (metal) ContraArt.BG_BASE_ALT else ContraArt.BG_CANOPY_ALT)
        val midShift = (camX * 0.45f).toInt() % 32
        for (i in -1 until 10) {
            val x = i * 32 - midShift
            val top = horizon - 44 - ((i * 13 + lvl * 7) % 20)
            for (k in 0 until 2) {
                quad((x + 8).toFloat(), (top + 16 + k * 16).toFloat(), 16f, 16f,
                    trunk, 0xFF8C8068.toInt())
            }
            quad(x.toFloat(), top.toFloat(), 16f, 16f, canopy, 0xFF8FAE8F.toInt())
            quad((x + 16).toFloat(), top.toFloat(), 16f, 16f,
                if ((i and 1) == 0) canopyAlt else canopy, 0xFF8FAE8F.toInt())
            quad((x + 4).toFloat(), (top - 12).toFloat(), 24f, 12f, canopy, 0xFF7E9C7E.toInt())
        }

        // ③ 瀑布：第 3 / 8 屏固定位置，白色竖纹瓦片染蓝，随 tick 左右摆一格
        val fall = atlas.frame(ContraArt.BG_FALL)
        val wobble = (snapshot.levelTick shr 3) and 1
        for (sc in intArrayOf(3, 8)) {
            val sx = sc * ContraConst.LOGICAL_W + 88 - camX
            if (sx < -16 || sx > ContraConst.LOGICAL_W + 16) continue
            for (k in 0 until 6) {
                quad(sx.toFloat() + wobble, (horizon - 56 + k * 10).toFloat(), 12f, 10f,
                    fall, if ((k and 1) == 0) 0xFF9FD0FF.toInt() else 0xFF78B0F0.toInt())
            }
        }

        // ④ 近景灌木：贴着地平线铺一排，视差最快
        val brush = atlas.frame(ContraArt.BG_BRUSH)
        val nearShift = camX % 16
        for (i in -1 until 18) {
            quad((i * 16 - nearShift).toFloat(), (horizon - 12).toFloat(), 16f, 12f,
                brush, 0xFFA8C49A.toInt())
        }
        quad(0f, horizon.toFloat(), ContraConst.LOGICAL_W.toFloat(), 1.5f, white,
            darken(snapshot.accent, 0.6f))
    }

    /**
     * 顶部歌词背板（真机反馈「歌词没有黑背景」）。
     *
     * 舞台的 `LyricTopBar` 只有 `Color.Black.copy(alpha = 0.35f)` 且只包住文字；
     * 本效果是 View 型 GL，`VisualizerStage` 会**整层不画深色底**（否则会把
     * SurfaceView 的洞填死），GL 自己又清成纯黑 ⇒ 那块 0.35 黑叠上去等于没有。
     * ⇒ 在这里补一条与舞台解耦的通栏黑带：只影响街机效果，⛔ 不动舞台、
     * ⛔ 不新增逐主题分支（`VisualizerStageThemeAgnosticGateTest` 判红）。
     * HUD 文字因此下移到画面底部，避免和歌词抢同一块区域。
     */
    private fun drawLyricScrim(white: ArcadeAtlas.Frame) {
        quad(0f, 0f, ContraConst.LOGICAL_W.toFloat(), 30f, white, 0xCC000000.toInt())
        quad(0f, 30f, ContraConst.LOGICAL_W.toFloat(), 5f, white, 0x66000000.toInt())
    }

    private fun drawTerrain() {
        val cols = snapshot.terrainCols
        if (cols <= 0) return
        val startCol = (snapshot.camX / ContraConst.TILE).coerceAtLeast(0)
        val endCol = ((snapshot.camX + ContraConst.LOGICAL_W) / ContraConst.TILE + 1).coerceAtMost(cols - 1)
        val rows = ContraConst.ROWS
        for (c in startCol..endCol) {
            val baseX = (c * ContraConst.TILE - snapshot.camX).toFloat()
            for (r in 0 until rows) {
                val code = snapshot.terrain[c * rows + r].toInt()
                if (code == ContraConst.C_EMPTY) continue
                val y = (r * ContraConst.TILE).toFloat()
                val above = if (r > 0) snapshot.terrain[c * rows + (r - 1)].toInt() else ContraConst.C_EMPTY
                when (code) {
                    ContraConst.C_FLOOR -> {
                        if (above == ContraConst.C_EMPTY) {
                            quad(baseX, y, 16f, 16f, atlas.frame(ContraArt.TILE_TOP), lerpColor(0xFFFFFFFF.toInt(), snapshot.groundTop, 0.25f))
                        } else {
                            quad(baseX, y, 16f, 16f, atlas.frame(ContraArt.TILE_DIRT), lerpColor(0xFFFFFFFF.toInt(), snapshot.ground, 0.25f))
                        }
                    }
                    ContraConst.C_SOLID -> {
                        quad(baseX, y, 16f, 16f, atlas.frame(ContraArt.TILE_BLOCK), lerpColor(0xFFFFFFFF.toInt(), snapshot.block, 0.25f))
                    }
                    ContraConst.C_WATER -> {
                        val wobble = ((snapshot.levelTick shr 3) + r) and 1
                        quad(
                            baseX, y, 16f, 16f, atlas.frame(ContraArt.TILE_WATER),
                            if (wobble == 0) 0xFF7FB6FF.toInt() else 0xFF5A8FE8.toInt(),
                        )
                    }
                }
            }
        }
    }

    private fun drawEnemies() {
        val camX = snapshot.camX
        for (i in 0 until snapshot.enemyCount) {
            val type = snapshot.enemyType[i]
            val name = when (type) {
                ContraConst.E_SOLDIER ->
                    if (snapshot.enemyFrame[i] == 0) ContraArt.SOLDIER_A else ContraArt.SOLDIER_B
                ContraConst.E_SNIPER -> ContraArt.SNIPER
                ContraConst.E_TURRET -> ContraArt.TURRET
                ContraConst.E_CAPSULE -> ContraArt.CAPSULE
                ContraConst.E_PILLBOX -> ContraArt.PILLBOX
                ContraConst.E_ITEM -> ContraArt.ITEM
                else -> ContraArt.SOLDIER_A
            }
            val f = atlas.frame(name) ?: continue
            val x = (snapshot.enemyX[i] - camX).toFloat()
            val y = (snapshot.enemyY[i] + enemyBoxHeight(type) - f.h).toFloat()
            quad(x - 2f, y, f.w.toFloat(), f.h.toFloat(), f, 0xFFFFFFFF.toInt())
        }
    }

    private fun enemyBoxHeight(type: Int): Int = when (type) {
        ContraConst.E_CAPSULE -> 12
        ContraConst.E_PILLBOX -> 14
        ContraConst.E_ITEM -> 12
        else -> 16
    }

    private fun drawBoss() {
        if (!snapshot.bossActive) return
        val f = atlas.frame(ContraArt.BOSS) ?: return
        val x = (snapshot.bossX - snapshot.camX).toFloat()
        val y = (snapshot.bossY + 24 - f.h).toFloat()
        val tint = if (snapshot.bossHurt > 0) 0xFFFF9090.toInt() else 0xFFFFFFFF.toInt()
        quad(x, y, f.w.toFloat(), f.h.toFloat(), f, tint)
        // 血条（boss 房内可见）
        val w = 60f
        val ratio = if (snapshot.bossMaxHp > 0) {
            snapshot.bossHp.toFloat() / snapshot.bossMaxHp
        } else 0f
        quad(8f, 8f, w, 3f, atlas.whiteFrame(), 0xAA000000.toInt())
        quad(8f, 8f, w * ratio.coerceIn(0f, 1f), 3f, atlas.whiteFrame(), snapshot.accent)
    }

    private fun drawBullets() {
        val camX = snapshot.camX
        for (i in 0 until snapshot.bulletCount) {
            val kind = snapshot.bulletKind[i]
            val name = when (kind) {
                ContraConst.WPN_F -> ContraArt.BULLET_F
                ContraConst.WPN_L -> ContraArt.BULLET_L
                ContraConst.WPN_B -> ContraArt.BULLET_B
                else -> ContraArt.BULLET_R
            }
            val f = atlas.frame(name) ?: continue
            quad(
                (snapshot.bulletX[i] - camX - f.w / 2).toFloat(),
                (snapshot.bulletY[i] - f.h / 2).toFloat(),
                f.w.toFloat(), f.h.toFloat(), f, 0xFFFFFFFF.toInt(),
            )
        }
        val eb = atlas.frame(ContraArt.BULLET_E) ?: return
        for (i in 0 until snapshot.enemyBulletCount) {
            quad(
                (snapshot.enemyBulletX[i] - camX - eb.w / 2).toFloat(),
                (snapshot.enemyBulletY[i] - eb.h / 2).toFloat(),
                eb.w.toFloat(), eb.h.toFloat(), eb, 0xFFFFFFFF.toInt(),
            )
        }
    }

    private fun drawPlayer() {
        if (!snapshot.playerAlive && snapshot.playerInvuln > 0) return
        // 无敌闪烁：每 4 帧亮 3 帧
        if (snapshot.playerInvuln > 0 && ((snapshot.playerInvuln shr 2) and 1) == 0) return
        val names = PLAYER_FRAMES
        val idx = snapshot.playerFrame.coerceIn(0, names.size - 1)
        val f = atlas.frame(names[idx]) ?: return
        val x = (snapshot.playerX - snapshot.camX).toFloat()
        val y = (snapshot.playerY + ContraConst.PLAYER_H - f.h).toFloat()
        quad(
            x + ContraConst.PLAYER_W / 2f - f.w / 2f, y,
            f.w.toFloat(), f.h.toFloat(), f, 0xFFFFFFFF.toInt(),
            flipX = snapshot.playerFacing < 0,
        )
    }

    private fun drawHud() {
        val w = atlas.whiteFrame()
        // ⚠️ 顶部 0..35 已让给歌词背板（drawLyricScrim）⇒ HUD 移到画面底部
        quad(0f, 226f, ContraConst.LOGICAL_W.toFloat(), 14f, w, 0x99000000.toInt())
        val y = 229f
        val stage = "txt_STAGE_${snapshot.level}"
        atlas.frame(stage)?.let { quad(4f, y, it.w.toFloat(), it.h.toFloat(), it, snapshot.accent) }

        atlas.frame("txt_LIVES")?.let { quad(96f, y, it.w.toFloat(), it.h.toFloat(), it, 0xFFFFFFFF.toInt()) }
        atlas.frame("txt_${snapshot.lives.coerceIn(0, 9)}")?.let {
            quad(96f + 32f, y, it.w.toFloat(), it.h.toFloat(), it, 0xFFFFFFFF.toInt())
        }

        atlas.frame("txt_SCORE")?.let { quad(180f, y, it.w.toFloat(), it.h.toFloat(), it, 0xFFFFFFFF.toInt()) }
        drawNumber(snapshot.score, 214f, y, 0xFFFFFFFF.toInt())
    }

    private fun drawNumber(value: Int, x: Float, y: Float, color: Int) {
        var v = value.coerceAtLeast(0)
        val digits = IntArray(6)
        var n = 0
        if (v == 0) {
            digits[0] = 0
            n = 1
        }
        while (v > 0 && n < 6) {
            digits[n] = v % 10
            v /= 10
            n++
        }
        var cx = x
        for (i in n - 1 downTo 0) {
            val f = atlas.frame("txt_${digits[i]}") ?: continue
            quad(cx, y, f.w.toFloat(), f.h.toFloat(), f, color)
            cx += 6f
        }
    }

    private fun drawOverlays() {
        val w = atlas.whiteFrame()
        when (snapshot.mode) {
            ContraConst.MODE_TITLE -> {
                quad(0f, 40f, ContraConst.LOGICAL_W.toFloat(), 96f, w, 0xB0000000.toInt())
                atlas.frame("txt_ARCADE")?.let {
                    quad(128f - it.w.toFloat(), 52f, it.w * 2f, it.h * 2f, it, snapshot.accent)
                }
                atlas.frame("txt_COLLECTION")?.let {
                    quad(128f - it.w.toFloat(), 80f, it.w * 2f, it.h * 2f, it, 0xFFFFFFFF.toInt())
                }
                atlas.frame("txt_STAGE_${snapshot.level}")?.let {
                    quad(128f - it.w.toFloat(), 108f, it.w * 1.5f, it.h * 1.5f, it, 0xFFFFFFFF.toInt())
                }
                // 闪烁（1 s 一个周期的一半亮）
                if ((snapshot.tick / 30) % 2 == 0L) {
                    atlas.frame("txt_AUTO_DEMO")?.let {
                        quad(128f - it.w.toFloat(), 126f, it.w.toFloat(), it.h.toFloat(), it, snapshot.accent)
                    }
                }
            }
            ContraConst.MODE_CLEAR -> {
                quad(0f, 96f, ContraConst.LOGICAL_W.toFloat(), 24f, w, 0xB0000000.toInt())
                atlas.frame("txt_LEVEL_CLEAR")?.let {
                    quad(128f - it.w.toFloat(), 100f, it.w.toFloat(), it.h.toFloat(), it, snapshot.accent)
                }
            }
        }
    }

    // ── 颜色小工具（避免 Color 类与装箱） ───────────────────────────────────

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = lerpChannel(from ushr 24 and 0xFF, to ushr 24 and 0xFF, t)
        val r = lerpChannel(from ushr 16 and 0xFF, to ushr 16 and 0xFF, t)
        val g = lerpChannel(from ushr 8 and 0xFF, to ushr 8 and 0xFF, t)
        val b = lerpChannel(from and 0xFF, to and 0xFF, t)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerpChannel(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun darken(color: Int, factor: Float): Int {
        val a = color ushr 24 and 0xFF
        val r = ((color ushr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((color ushr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ── 着色器 ──────────────────────────────────────────────────────────────

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "link 失败：${GLES20.glGetProgramInfoLog(p)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "shader 编译失败：${GLES20.glGetShaderInfoLog(s)}")
        }
        return s
    }

    companion object {
        private const val TAG = "ArcadeGlScene"

        private const val MAX_QUADS = 1024
        private const val VERTS_PER_QUAD = 6
        private const val FLOATS_PER_VERT = 8
        private const val MAX_CATCH_UP_STEPS = 3

        /** 玩家贴图帧顺序（与 [com.nasmusic.tv.visualizer.arcade.sim.ContraSim] 的帧号一一对应） */
        val PLAYER_FRAMES: Array<String> = ContraArt.PLAYER_FRAMES

        /** GLES 2.0 口径（attribute / varying）⇒ 老机（G6110 / API 22）也稳过 */
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            attribute vec4 aColor;
            uniform vec2 uViewport;
            varying vec2 vUV;
            varying vec4 vColor;
            void main() {
                vUV = aUV;
                vColor = aColor;
                gl_Position = vec4(aPos.x / uViewport.x * 2.0 - 1.0,
                                   1.0 - aPos.y / uViewport.y * 2.0,
                                   0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUV;
            varying vec4 vColor;
            void main() {
                gl_FragColor = texture2D(uTex, vUV) * vColor;
            }
        """
    }
}
