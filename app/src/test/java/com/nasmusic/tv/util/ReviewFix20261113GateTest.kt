package com.nasmusic.tv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **门禁：2026-11-13 全库代码审查整改不得回退**
 *
 * 背景：`docs/archive/NASMusicTV-代码审查报告-2026-11-13.html`（59 条终稿发现）已按
 * 9 个互不重叠批次全部落地。这里用源码字符串扫描钉住其中 20 处**最容易在后续改动里
 * 被无意撤掉**的关键修复，覆盖 P0 两条、安全边界、数据完整性、日志分级与转场终态。
 *
 * ⛔ 断言一律**语句级 / 函数体级**：项目里多次踩过「全文否定被注释里的解释自伤」
 * （天空门禁第五轮、E52 定稿），所以不用 `!src.contains("…")` 这种宽泛判负。
 * JVM 单测没有 Android / GL 上下文，所有结论都来自源码文本。
 */
class ReviewFix20261113GateTest {

    // ────────────────── ① P0：生命周期与安全边界 ──────────────────

    @Test
    fun `S02-01 HQ release 不得 cancel app 级单例 scope`() {
        val body = bodyOf(
            mainSource("player/HqSeparationOrchestrator.kt"),
            "fun release()",
        )
        assertTrue("找不到 HqSeparationOrchestrator.release() 方法体", body != null)
        val b = body!!
        assertFalse(
            "release() 不得 cancel 单例持有的 val scope：PlaybackService.onCreate 不重建 orchestrator，" +
                "cancel 后同进程重建会让 HQ 人声分离永久残废",
            b.contains("scope.cancel()"),
        )
        assertTrue("仍需取消在途的分离任务", b.contains("separationJob?.cancel()"))
    }

    @Test
    fun `S05-02 LX 脚本出站逐跳校验且回传前复核最终 URL`() {
        val src = mainSource("backend/network/lx/LxScriptHost.kt")
        assertTrue("注入 client 必须派生出 network interceptor（OkHttp 默认 followRedirects=true）",
            src.contains(".addNetworkInterceptor { chain ->"))
        assertTrue("interceptor 内必须逐跳用严格守卫", src.contains("NetAddressGuard.rejectReason(target.toString())"))
        assertTrue("响应回传脚本前必须复核最终 URL", src.contains("NetAddressGuard.rejectReason(it.request.url.toString())"))
    }

    @Test
    fun `S05-01 LX 脚本仓库下载同样逐跳校验`() {
        val src = mainSource("backend/network/lx/LxScriptRepo.kt")
        assertTrue("download() 用的 client 必须加 network interceptor",
            src.contains(".addNetworkInterceptor { chain ->"))
        assertTrue("逐跳校验用严格 rejectReason，不放宽到 ForPlayback",
            src.contains("NetAddressGuard.rejectReason(target.toString())"))
    }

    @Test
    fun `S05-07 V4-N01 V4-N02 播放直链统一过地址守卫`() {
        val meting = mainSource("backend/network/MetingApiService.kt")
        val enhancer = mainSource("backend/network/lx/LxPlayUrlEnhancer.kt")
        assertTrue("Meting 的 200 JSON url 分支必须过守卫", meting.contains("NetAddressGuard.rejectReason(candidate.toString())"))
        assertTrue("Meting 最终返回的 url 必须过 ForPlayback 守卫",
            meting.contains("val reason = NetAddressGuard.rejectReasonForPlayback(url)"))
        assertTrue("洛雪 enhancer 返回的播放直链必须过守卫",
            enhancer.contains("NetAddressGuard.rejectReasonForPlayback(url)"))
    }

    // ────────────────── ② 数据完整性 ──────────────────

    @Test
    fun `S01-01 模型上传先写 tmp 校验通过才原子替换`() {
        val body = bodyOf(mainSource("net/ModelTransferServer.kt"), "private fun handleUpload(")
        assertTrue("找不到 handleUpload 方法体", body != null)
        val b = body!!
        assertTrue("上传目标必须是 .tmp，而不是正式模型文件",
            b.contains("File(modelFile.parentFile, \"\$MODEL_FILENAME.tmp\")"))
        assertTrue("校验通过后才 renameTo 正式文件", b.contains("target.renameTo(modelFile)"))
        assertFalse("不得直接删除正式模型文件（失败上传会不可逆删掉已装模型）",
            b.contains("modelFile.delete()"))
        assertTrue("只允许清理残留的临时文件（delete 作用于 target 即 tmp）",
            b.contains("if (target.exists()) target.delete()"))
    }

    @Test
    fun `V2-N01 模型上传失败响应必须断连`() {
        val src = mainSource("net/ModelTransferServer.kt")
        val count = src.split(".noKeepAlive()").size - 1
        assertTrue(
            "拒绝 / 校验失败 / 异常四个分支都要 .noKeepAlive()（实际 $count 处），" +
                "否则残留 multipart 请求体会串到下一次请求",
            count >= 4,
        )
    }

    @Test
    fun `S01-02 multipart 流式上传必须有体量上限`() {
        val src = mainSource("net/MultipartBoundaryStreamer.kt")
        assertTrue("stream() 必须接受 maxBytes 参数", src.contains("fun stream(input: InputStream, output: OutputStream, maxBytes: Long"))
        assertTrue("累计写入超阈值必须抛 IOException", src.contains("throw IOException(\"multipart body exceeds limit:"))
    }

    @Test
    fun `V3-N02 下载恢复不得把半截文件反推 COMPLETED`() {
        val src = mainSource("backend/download/SongDownloadManager.kt")
        assertTrue("必须要求 DB 有有效 fileSize(>0) 且磁盘长度精确一致",
            src.contains("entity.fileSize > 0L && it.length() == entity.fileSize"))
        assertFalse("不得再以 fileSize<=0 放宽校验（对未完成任务恒真，护栏全废）",
            src.contains("entity.fileSize <= 0L ||"))
    }

    @Test
    fun `S04-03 封面缓存按真实写入时间排序落盘`() {
        val src = mainSource("backend/local/CoverUrlPersistentCache.kt")
        assertTrue("save() 必须按 writtenAt 升序，否则重启后淘汰顺序退化为哈希桶序",
            src.contains("sortedBy { writtenAt[it.key] ?: 0L }"))
        assertFalse("不得再直接写 ConcurrentHashMap.toMap()", src.contains("gson.toJson(cache.toMap())"))
    }

    @Test
    fun `S03-04 V3-N01 飞牛 total 脏值不得折叠成 0`() {
        val src = mainSource("backend/impl/FeiniuAdapter.kt")
        assertTrue("必须有区别于 intOf 的可空 total 解析", src.contains("private fun totalOf(obj: JsonObject?): Int?"))
        assertTrue("fetchAllPages 必须用 totalOf 且把 0 当作未知", src.contains("totalOf(data)?.takeIf { it > 0 }"))
        assertTrue("fetchTotal 必须共用同一解析", src.contains("totalOf(data)\n"))
        assertFalse("不得再走「字段存在即 intOf」", src.contains("if (data.get(\"total\") == null) -1 else intOf(data, \"total\")"))
    }

    @Test
    fun `S03-05 Jellyfin 歌曲解析逐条隔离且用安全访问器`() {
        val src = mainSource("backend/impl/JellyfinAdapter.kt")
        assertTrue("单条脏数据必须 runCatching 隔离，别让整批静默变空列表",
            src.contains("runCatching { parseSongUnsafe(obj, albumIdOverride) }"))
        assertTrue("字符串字段走安全访问器", src.contains("private fun JsonObject.str(name: String): String?"))
        assertTrue("数组字段走安全访问器", src.contains("private fun JsonObject.arr(name: String): JsonArray?"))
        assertTrue("解析体内不得再用 getAsJsonArray(\"Artists\") 强制访问",
            !src.substringAfter("private fun parseSongUnsafe(").substringBefore("private suspend fun authenticateByName(")
                .contains("getAsJsonArray(\"Artists\")"))
    }

    // ────────────────── ③ 取消语义 / 日志分级 ──────────────────

    @Test
    fun `V5-N01 BackendRegistry 内层 catch 不得吞取消`() {
        val src = mainSource("backend/BackendRegistry.kt")
        assertTrue("initialize() 的 catch 必须首句重抛 CancellationException",
            src.contains("e.rethrowIfCancellation()"))
    }

    @Test
    fun `S07-02 S07-03 S07-04 S01-03 取消语义护栏覆盖`() {
        for (path in listOf(
            "ui/viewmodel/ServerViewModel.kt",
            "ui/viewmodel/SearchViewModel.kt",
            "ui/viewmodel/NetworkMusicViewModel.kt",
            "util/RetryUtil.kt",
        )) {
            assertTrue("$path 的 catch 必须首句重抛取消信号",
                mainSource(path).contains("e.rethrowIfCancellation()"))
        }
    }

    @Test
    fun `S11-04 世代作废不得吞掉 OOM 坏图等真失败`() {
        val body = bodyOf(
            mainSource("visualizer/photo/PhotoBuffer.kt"),
            "private fun isDecodeCancelled(t: Throwable)",
        )
        assertTrue("找不到 isDecodeCancelled 方法体", body != null)
        val b = body!!
        assertTrue("只用异常链判定取消", b.contains("cur is InterruptedException || cur is CancellationException"))
        assertFalse("取消判定不得读 closed", b.contains("closed.get()"))
        assertFalse("取消判定不得读 generation", b.contains("generation.get()"))
    }

    @Test
    fun `S05-08 IP 定位缓存必须单飞`() {
        val src = mainSource("backend/weather/WeatherApi.kt")
        assertTrue("用 Mutex 而非 synchronized 串行化整段（请求是 suspend）",
            src.contains("private val locationLock = Mutex()"))
        val body = bodyOf(src, "suspend fun getIpLocation(): IpLocation")
        assertTrue("找不到 getIpLocation 方法体", body != null)
        assertTrue("读缓存/请求/写回必须在同一把锁内", body!!.contains("locationLock.withLock {"))
        assertTrue("拿锁后必须二次判缓存", body.contains("if (cached != null) return@withLock cached"))
    }

    // ────────────────── ④ 转场终态 / 渲染器 ──────────────────

    @Test
    fun `S11-01 S11-02 S11-03 转场 p=1 终态必须干净`() {
        // PhotoTransitionClock HOLD 期 progress 恒 1f ⇒ 终态持续整个停留期（数秒），不是单帧
        for (path in listOf(
            "visualizer/photo/transitions/EffectsP1Transitions.kt",
            "visualizer/photo/transitions/TransformP2Transitions.kt",
            "visualizer/photo/transitions/TilesP2Transitions.kt",
        )) {
            assertTrue("$path 必须在 p>=1f 时早返回，避免过曝叠加 / 顶部暗条 / 黑网格",
                mainSource(path).contains("if (p >= 1f)"))
        }
    }

    @Test
    fun `S12-01 海滨沙纹合批必须用相对 count`() {
        assertTrue("绝对返回下标当 count 会让 HIGH 档 b=1/b=2 沙纹丢失",
            mainSource("visualizer/renderers/SeasideRenderer.kt").contains("drawLinesBatch(sandGrainPts, dst0, dst - dst0, paint)"))
    }

    @Test
    fun `S14-01 从未有 beat 时也能进入兜底`() {
        assertTrue("lastBeatMs>0 的旧条件排除了「从未命中 beat」",
            mainSource("visualizer/renderers/ParticleRenderers.kt").contains("lastBeatMs <= 0L || frame.timeMs - lastBeatMs > 6000L"))
    }

    @Test
    fun `S15-03 歌词分词按 codepoint 且保留中文逐字`() {
        val src = mainSource("visualizer/renderers/lyricsStage/LyricsStagePayload.kt")
        assertTrue("必须按 codepoint 遍历才能保留 emoji / 组合字符 / 俄文",
            src.contains("text.codePointAt(i)"))
        assertFalse("不得再只用 TOKEN_REGEX 切 CJK+ASCII", src.contains("TOKEN_REGEX"))
    }

    // ────────────────── ⑤ UI 状态一致性 ──────────────────

    @Test
    fun `S09-01 Lazy key 必须带 index 前缀`() {
        val src = mainSource("ui/screens/MineScreen.kt")
        assertTrue("重复 id 会让裸 key 崩，必须 itemsIndexed + index 前缀",
            src.contains("itemsIndexed(mergedFavorites, key = { index, song -> \"fav_\${index}_\${song.id}\" })"))
        assertTrue("歌单内重复曲目同样要 index", src.contains("\"pl_song_\${playlist.id}_\${index}_\${song.id}\""))
    }

    @Test
    fun `V5-N02 搜索清空分支必须递增代数`() {
        val src = mainSource("ui/viewmodel/SearchViewModel.kt")
        val count = src.split("++networkSearchGeneration").size - 1
        assertTrue("空词 / 清空分支也要递增代数，否则在途搜索会把已清空的结果写回（实际 $count 处）",
            count >= 2)
    }

    // ────────────────── 工具（与 ExpectedFailureLogLevelGateTest 同款） ──────────────────

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件：$relativePath", file != null)
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("源码读出来是空的：$relativePath", text.isNotBlank())
        return text
    }

    private fun bodyOf(src: String, signature: String): String? {
        val start = src.indexOf(signature)
        if (start < 0) return null
        val open = src.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }
}
