package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.render.ContraSpriteFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 上游素材门禁（⛔ 所有者 2026-11-10 裁定：**全部用上游真图，不自绘**）。
 *
 * 钉住五件事：
 * 1. 232 张精灵 + 256 块瓦片 + 1 白块**一帧不少**（生成器漏文件会立刻红）；
 * 2. 每帧矩形都在 1024×1024 图集内且尺寸 > 0；
 * 3. `ContraArt` 里引用的每个上游帧名都真的存在于帧表；
 * 4. 渲染器里**不许再出现自绘帧名**（`gen_*` / 裸 `tile_*` 字面量）；
 * 5. APK 的 `assets/e54_arcade/` 里**只有一张烘焙图集**，不许把 232 张 ROM 原件散着塞进去。
 */
class UpstreamSpriteAtlasTest {

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

    @Test
    fun `上游精灵与瓦片一帧不少`() {
        val names = ContraSpriteFrames.names()
        assertEquals("精灵帧数（nes-contra-us 的 232 张 PNG）", 232, ContraSpriteFrames.SPRITE_COUNT)
        assertEquals("瓦片帧数（tiles_c.h 的 256 块）", 256, ContraSpriteFrames.TILE_COUNT)
        assertEquals("总帧数 = 232 + 256 + 白块", 489, ContraSpriteFrames.count())
        assertEquals("逐帧计数必须与表一致", ContraSpriteFrames.count(), names.size)
        val sprites = names.filter { it.startsWith("sprite_") }
        val tiles = names.filter { it.startsWith("tile_") }
        assertEquals(232, sprites.size)
        assertEquals(256, tiles.size)
        assertTrue("白块帧必须在", names.contains("white"))
        assertTrue("玩家跑动帧必须在", names.contains("sprite_02"))
        assertTrue("BOSS 帧必须在", names.contains("sprite_6b"))
        assertTrue("地形瓦片必须在（现行地表帧）", names.contains("tile_6b"))
        assertTrue("地下填充帧必须在", names.contains("tile_d0"))
        assertTrue("实心块帧必须在", names.contains("tile_b8"))
    }

    @Test
    fun `每帧矩形都在图集内且尺寸有效`() {
        val s = ContraSpriteFrames.SIZE
        for (name in ContraSpriteFrames.names()) {
            val r = ContraSpriteFrames.rect(name)!!
            assertTrue("$name 宽必须 > 0", r.w > 0)
            assertTrue("$name 高必须 > 0", r.h > 0)
            assertTrue("$name 越出图集右边界", r.x + r.w <= s)
            assertTrue("$name 越出图集下边界", r.y + r.h <= s)
        }
        assertTrue(
            "文字预留区必须在图集内且够高",
            ContraSpriteFrames.TEXT_START_Y < s - 24,
        )
    }

    @Test
    fun `ContraArt 引用的上游帧名都存在`() {
        val names = ContraSpriteFrames.names()
        val art = src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/render/ContraArt.kt")
        val refs = Regex(""""((?:sprite|tile)_[0-9a-f]{2})"""")
            .findAll(art)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("ContraArt 里应能扫到上游帧名（正则失配 = 门禁空转）", refs.size >= 20)
        val missing = refs.filter { it !in names }
        assertTrue("ContraArt 引用了不存在的上游帧：$missing", missing.isEmpty())
    }

    @Test
    fun `渲染器里不再有自绘帧名`() {
        val scene = src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/render/ArcadeGlScene.kt")
        val legacy = Regex(""""gen_[a-z_0-9]+"""").findAll(scene).map { it.value }.toList()
        assertTrue("渲染器仍引用自绘帧：$legacy", legacy.isEmpty())
        assertTrue("渲染器必须走 ContraArt 映射", scene.contains("ContraArt.PLAYER_FRAMES"))
        assertTrue("地形必须走上游瓦片", scene.contains("ContraArt.TILE_TOP"))
    }

    @Test
    fun `assets 里只有一张烘焙图集 不塞 ROM 原件`() {
        val dir = File(projectRoot(), "app/src/main/assets/e54_arcade")
        assertTrue("图集目录不存在", dir.isDirectory)
        val files = dir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals("只允许 contra_atlas.png 一个文件", listOf("contra_atlas.png"), files)
        val png = File(dir, "contra_atlas.png").readBytes()
        assertEquals("PNG 魔数", 0x89.toByte(), png[0])
        val w = ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or
            ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)
        val h = ((png[20].toInt() and 0xFF) shl 24) or ((png[21].toInt() and 0xFF) shl 16) or
            ((png[22].toInt() and 0xFF) shl 8) or (png[23].toInt() and 0xFF)
        assertEquals("图集宽", ContraSpriteFrames.SIZE, w)
        assertEquals("图集高", ContraSpriteFrames.SIZE, h)
    }

    @Test
    fun `文字帧 key 生成规则稳定`() {
        val atlas = src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/render/ArcadeAtlas.kt")
        assertTrue(
            "textKey 必须把空格换成下划线（渲染层用 txt_STAGE_n / txt_AUTO_DEMO 取帧）",
            atlas.contains("\"txt_\" + text.replace(' ', '_')"),
        )
        for (s in listOf("ARCADE", "COLLECTION", "AUTO DEMO", "GAME OVER", "LEVEL CLEAR", "LIVES", "SCORE")) {
            assertTrue("TEXT_FRAMES 缺少 $s", atlas.contains("add(\"$s\")"))
        }
    }
}
