package com.nasmusic.tv.visualizer.arcade.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

/**
 * 运行时图集：**直接载入上游 ROM 提取精灵/瓦片**，⛔ 本项目一张自绘精灵都没有。
 *
 * ## 素材链（⛔ 只允许这一条）
 * `vermiceli/nes-contra-us` 的 232 张精灵 PNG + `MikeWuPing/UEFI_Contra` 的 256 块 level 1 瓦片
 * ⇒ `scripts/e54_pack_sprites.py` 打包成 `assets/e54_arcade/contra_atlas.png`
 * ⇒ 本类载入并按 [ContraSpriteFrames] 的帧表登记 uv。
 *
 * 上游原件放在 gitignored 的 `app/src/e54-upstream/`；**只有上面这一张烘焙后的图集进 APK**。
 * ⚠️ 这些是 NES ROM 提取图，版权归原始权利人所有 ⇒ **仅用于私有分支本地包，绝不进公开包**。
 *
 * ## 为什么不是纯运行期烘焙
 * 上一版把字符画烘成位图（零外部资产）。所有者 2026-11-10 裁定改用上游真图
 * ⇒ 改为「构建期打包图集 + 运行期只补 HUD 文字帧」：图集是**二进制原件**，
 * 代码里没法忠实表达 8×8 四级色板，硬转字符画只会丢色。
 *
 * ## 采样口径
 * `GL_NEAREST` + 关 mipmap（像素图放大必须硬边）。文字帧画在图集底部预留区，
 * 与精灵共用**一张纹理** ⇒ 全场景仍是**一次** `glDrawArrays`。
 */
class ArcadeAtlas(private val appContext: Context) {

    /** 一帧在图集里的位置与尺寸（uv 已归一化） */
    class Frame(
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val w: Int,
        val h: Int,
    )

    private val frames = HashMap<String, Frame>(ContraSpriteFrames.count() + TEXT_FRAMES.size)

    /** 纹理 id（0 = 未上传） */
    var textureId: Int = 0
        private set

    /** 生成好的位图（上传后可释放，保留供调试） */
    var bitmap: Bitmap? = null
        private set

    var baked: Boolean = false
        private set

    fun frame(name: String): Frame? = frames[name]

    /** 白块帧：无贴图的矩形（天空 / HUD 底条）共用 ⇒ **整帧只需一次 draw call** */
    fun whiteFrame(): Frame = frames.getValue(WHITE)

    /**
     * 载入图集位图并登记帧表（**不触碰 GL** ⇒ 可在任意线程调用）。幂等。
     */
    fun bake() {
        if (baked) return
        val size = ContraSpriteFrames.SIZE
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val src = appContext.assets.open(ASSET_PATH).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("上游精灵图集缺失：$ASSET_PATH")
        if (src.width != size || src.height != size) {
            throw IllegalStateException("图集尺寸不符：${src.width}×${src.height}，应为 $size×$size")
        }
        // ⚠️ 逐像素拷贝而不是 Canvas.drawBitmap：1:1 不能被抗锯齿/过滤污染
        val pixels = IntArray(size * size)
        src.getPixels(pixels, 0, size, 0, 0, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)

        val s = size.toFloat()
        for (name in ContraSpriteFrames.names()) {
            val r = ContraSpriteFrames.rect(name) ?: continue
            frames[name] = Frame(
                u0 = r.x / s,
                v0 = r.y / s,
                u1 = (r.x + r.w) / s,
                v1 = (r.y + r.h) / s,
                w = r.w,
                h = r.h,
            )
        }
        // 白块 uv 全取中心 ⇒ 插值不会采到邻居
        val wr = ContraSpriteFrames.rect(WHITE)
            ?: throw IllegalStateException("图集缺少白块帧")
        val wcx = (wr.x + wr.w * 0.5f) / s
        val wcy = (wr.y + wr.h * 0.5f) / s
        frames[WHITE] = Frame(wcx, wcy, wcx, wcy, 2, 2)

        // HUD 文字帧（Canvas 画进同一张位图；关抗锯齿 ⇒ 像素风硬边）
        val paint = Paint().apply {
            isAntiAlias = false
            isDither = false
            typeface = Typeface.MONOSPACE
            textSize = 9f
            color = Color.WHITE
        }
        var cursorX = 0
        var cursorY = ContraSpriteFrames.TEXT_START_Y
        var rowH = 0
        val canvas = Canvas(bmp)
        for (text in TEXT_FRAMES) {
            val key = textKey(text)
            val tw = paint.measureText(text).toInt() + 2
            val th = 12
            if (cursorX + tw > size) {
                cursorX = 0
                cursorY += rowH
                rowH = 0
            }
            if (cursorY + th > size) break
            canvas.drawText(text, (cursorX + 1).toFloat(), (cursorY + 9).toFloat(), paint)
            frames[key] = Frame(
                cursorX / s, cursorY / s, (cursorX + tw) / s, (cursorY + th) / s, tw, th,
            )
            cursorX += tw
            rowH = maxOf(rowH, th)
        }

        bitmap = bmp
        baked = true
        Log.i(TAG, "图集已载入：${ContraSpriteFrames.count()} 帧上游素材 + ${TEXT_FRAMES.size} 帧 HUD 文字")
    }

    /** 上传成 GL 纹理（**必须在 GL 线程 / 有当前上下文时调用**） */
    fun upload() {
        bake()
        val bmp = bitmap ?: return
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // 像素图：最近邻 + 不生成 mipmap
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        Log.i(TAG, "图集已上传：${frames.size} 帧 / ${ContraSpriteFrames.SIZE}×${ContraSpriteFrames.SIZE}")
    }

    /** 释放 GL 资源（GL 线程） */
    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    companion object {
        private const val TAG = "ArcadeAtlas"

        /** 由 `scripts/e54_pack_sprites.py` 打进 APK 的唯一素材文件 */
        const val ASSET_PATH = "e54_arcade/contra_atlas.png"

        const val WHITE = "white"

        /** 标题 / HUD 文字（**统一在本类烘**，渲染层只按 key 取帧） */
        val TEXT_FRAMES: List<String> = buildList {
            add("ARCADE")
            add("COLLECTION")
            add("AUTO DEMO")
            add("GAME OVER")
            add("LEVEL CLEAR")
            add("LIVES")
            add("SCORE")
            for (i in 1..8) add("STAGE $i")
            for (i in 0..9) add("$i")
        }

        fun textKey(text: String): String = "txt_" + text.replace(' ', '_')
    }
}
