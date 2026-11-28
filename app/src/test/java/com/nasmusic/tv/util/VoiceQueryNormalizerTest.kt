package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueryNormalizerTest {

    @Test
    fun `golden command cases normalize to stable search queries`() {
        val cases = listOf(
            "播放周杰伦的稻香" to "周杰伦 稻香",
            "我想听晴天" to "晴天",
            "play 稻香" to "稻香",
            "zjl dx" to "zjl dx",
            "　播放　周杰伦　的　稻香　" to "周杰伦 稻香",
            "播放，周杰伦的《稻香》！" to "周杰伦 稻香",
            "请播放 我想听 晴天" to "我想听 晴天",
            "播放播放稻香" to "播放稻香",
            "来一首 海阔天空" to "海阔天空",
            "放一首「晴天」" to "晴天",
            "听一下 七里香" to "七里香",
            "listen to 晴天" to "晴天",
            "Please play 稻香" to "稻香",
            "I want to listen to 晴天" to "晴天",
            "Let me hear 稻香" to "稻香",
            "‘play’ ‘稻香’" to "稻香",
            "\"play\" \"稻香\"" to "稻香",
            "我想听「夜曲」" to "夜曲",
            "   " to "",
            "！？。，" to "",
        )

        cases.forEach { (raw, expected) ->
            assertEquals("raw=$raw", expected, VoiceQueryNormalizer.normalize(raw))
        }
        assertEquals(20, cases.size)
    }

    @Test
    fun `bare ting and play are kept in search queries while spaced commands still work`() {
        val golden = listOf(
            "听海" to "听海",
            "听说" to "听说",
            "听" to "听",
            "play" to "play",
            "player" to "player",
            "听我说谢谢你" to "听我说谢谢你",
        )
        golden.forEach { (raw, expected) ->
            assertEquals("raw=$raw", expected, VoiceQueryNormalizer.normalize(raw))
        }
        assertNull(VoiceQueryNormalizer.normalizeDetailed("听海").matchedPrefix)
        assertNull(VoiceQueryNormalizer.normalizeDetailed("player").matchedPrefix)

        // 裸前缀后跟空白的命令语义保留
        listOf(
            "听 稻香" to "稻香",
            "听一下 七里香" to "七里香",
            "我想听晴天" to "晴天",
            "play 稻香" to "稻香",
            "播放稻香" to "稻香",
        ).forEach { (raw, expected) ->
            assertEquals("raw=$raw", expected, VoiceQueryNormalizer.normalize(raw))
        }
    }

    @Test
    fun `detailed result preserves raw input and reports only first matched prefix`() {
        val result = VoiceQueryNormalizer.normalizeDetailed("播放播放稻香")
        assertEquals("播放稻香", result.query)
        assertEquals("播放", result.matchedPrefix)
        assertEquals("播放播放稻香", result.raw)
    }

    @Test
    fun `ordinary artist title containing de is not split`() {
        assertEquals("爱的故事", VoiceQueryNormalizer.normalize("爱的故事"))
        val result = VoiceQueryNormalizer.normalizeDetailed("爱的故事")
        assertNull(result.matchedPrefix)
    }

    @Test
    fun `query is truncated to eighty characters after normalization`() {
        val raw = "a".repeat(100)
        val normalized = VoiceQueryNormalizer.normalize(raw)
        assertEquals(VoiceQueryNormalizer.MAX_QUERY_LENGTH, normalized.length)
        assertEquals(80, normalized.length)
        assertTrue(normalized.all { it == 'a' })
    }

    @Test
    fun `padded command is truncated after prefix removal`() {
        val normalized = VoiceQueryNormalizer.normalize("play " + "b".repeat(90))
        assertEquals(80, normalized.length)
        assertTrue(normalized.all { it == 'b' })
    }
}
