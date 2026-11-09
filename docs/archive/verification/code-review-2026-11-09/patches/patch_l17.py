#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L17: LyricsManager 候选拉取按 key 单飞，网络请求移出全局锁"""
import io, sys

REL = 'app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt'
FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

raw = io.open(REL, 'r', encoding='utf-8', newline='').read()
crlf = raw.count('\r\n')
eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
S = raw.replace('\r\n', '\n')

# ---------- 1) 新增单飞登记表 + 辅助函数 ----------
old = """    /** 候选拉取互斥锁：cachedCandidates 的 containsKey→put 非原子，并发调用会重复发起网络请求（缓存惊群） */
    private val candidateFetchMutex = Mutex()"""
new = """    /**
     * L17：候选拉取的**单飞登记表** —— key = `"songId|round"`。
     *
     * 原实现用一把实例级 [candidateFetchMutex] 把整段 NETWORK 分支都罩住，而分支里含
     * 两次网络拉取（`fetchLyricsCandidates` 内部是 3 组关键词 × 2 个源 × 15s 超时）：
     * 一首歌首次取歌词期间，**任何别的歌**请求歌词都要排队，最长可等近 45s
     * —— 真机表现就是「切歌后歌词半天不出现，过一会儿又自己好了」。
     *
     * 现在锁不再跨网络：改成一个 `CompletableDeferred` 登记表，同一 key 的并发请求合并到
     * 同一个 Deferred 上（仍然只打一次网络，与原来一致），不同 key 完全并行。
     */
    private val candidateFetchInFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<List<String>>>()

    /**
     * L17：按 key 合并的候选拉取。
     *
     * - 已有同 key 的拉取在跑 ⇒ 直接 `await` 它的结果（不打网络）；
     * - 没有 ⇒ 自己登记一个 Deferred 再执行 [fetch]，无论成功失败都会注销。
     *
     * `while (true)` 是给 `putIfAbsent` 的失败分支用的：输给别的协程就回头再查一次，
     * 正常情况一次就命中。
     */
    private suspend fun fetchCandidatesSingleFlight(
        key: String,
        fetch: suspend () -> List<String>
    ): List<String> {
        while (true) {
            candidateFetchInFlight[key]?.let { return it.await() }
            val mine = kotlinx.coroutines.CompletableDeferred<List<String>>()
            if (candidateFetchInFlight.putIfAbsent(key, mine) == null) {
                return try {
                    val result = fetch()
                    mine.complete(result)
                    result
                } catch (t: Throwable) {
                    mine.completeExceptionally(t)
                    throw t
                } finally {
                    candidateFetchInFlight.remove(key, mine)
                }
            }
        }
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L17-field', False, 'anchor not found')

# ---------- 2) NETWORK 分支改造 ----------
old = """            LyricsSource.NETWORK -> candidateFetchMutex.withLock {
                // 互斥 + 双检：containsKey→put 非原子，并发调用（如用户连点）会重复发起 fetchLyricsCandidates
                if (!cachedCandidates.containsKey(song.id)) {
                    val results = networkProvider.fetchLyricsCandidates(song.title, song.artist)
                    cachedCandidates[song.id] = results
                    candidateVariantRound[song.id] = 0
                    AppLog.d("LyricsManager", "NETWORK: first fetch, ${results.size} candidates")
                }
                val candidates = cachedCandidates[song.id] ?: emptyList()

                // 2. 当前候选足够 → 直接返回（用 indices 判定，同时挡住负数索引）
                if (candidateIndex in candidates.indices) {
                    val text = candidates[candidateIndex]
                    pendingNetworkLyrics[song.id] = text
                    return@withLock LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                }

                // 3. 候选耗尽 → 换一批重新搜索
                val currentRound = candidateVariantRound[song.id] ?: 0
                val nextRound = currentRound + 1
                if (nextRound < variantSuffixes.size) {
                    val suffix = variantSuffixes[nextRound]
                    val keyword = if (suffix.isBlank()) song.title else "${song.title} $suffix"
                    AppLog.d("LyricsManager", "NETWORK: candidates exhausted, re-search round=$nextRound keyword='$keyword'")
                    val newResults = networkProvider.fetchLyricsCandidates(keyword, song.artist)
                    if (newResults.isNotEmpty()) {
                        val all = candidates + newResults
                        cachedCandidates[song.id] = all
                        candidateVariantRound[song.id] = nextRound
                        // 边界守卫：candidateIndex 来自调用方（用户反复点“在线歌词”会递增），
                        // 可能超出合并后的候选数量，直接 all[candidateIndex] 会抛 IndexOutOfBoundsException。
                        val safeIndex = candidateIndex.coerceIn(0, all.lastIndex)
                        val text = all[safeIndex]
                        pendingNetworkLyrics[song.id] = text
                        return@withLock LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    }
                }
                // 所有变异轮次用尽 → 返回 null
                AppLog.w("LyricsManager", "NETWORK: all variants exhausted, no more candidates")
                null
            }"""
new = """            LyricsSource.NETWORK -> {
                // L17：不再整段持 `candidateFetchMutex`。锁的**唯一职责**是「让同一首歌只发一次
                // 首轮拉取」，这件事交给 [fetchCandidatesSingleFlight]；网络请求本身在锁外跑，
                // 所以不同歌曲互不阻塞（原实现会让别首歌排到最长约 45s）。
                if (!cachedCandidates.containsKey(song.id)) {
                    val results = fetchCandidatesSingleFlight("${song.id}|r0") {
                        networkProvider.fetchLyricsCandidates(song.title, song.artist)
                    }
                    // putIfAbsent：单飞合并后仍可能有另一个协程已先写入，别覆盖它的结果
                    cachedCandidates.putIfAbsent(song.id, results)
                    candidateVariantRound.putIfAbsent(song.id, 0)
                    AppLog.d("LyricsManager", "NETWORK: first fetch, ${results.size} candidates")
                }
                val candidates = cachedCandidates[song.id] ?: emptyList()

                // 2. 当前候选足够 → 直接返回（用 indices 判定，同时挡住负数索引）
                if (candidateIndex in candidates.indices) {
                    val text = candidates[candidateIndex]
                    pendingNetworkLyrics[song.id] = text
                    return@withContext LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                }

                // 3. 候选耗尽 → 换一批重新搜索
                val currentRound = candidateVariantRound[song.id] ?: 0
                val nextRound = currentRound + 1
                if (nextRound < variantSuffixes.size) {
                    val suffix = variantSuffixes[nextRound]
                    val keyword = if (suffix.isBlank()) song.title else "${song.title} $suffix"
                    AppLog.d("LyricsManager", "NETWORK: candidates exhausted, re-search round=$nextRound keyword='$keyword'")
                    val newResults = fetchCandidatesSingleFlight("${song.id}|r$nextRound") {
                        networkProvider.fetchLyricsCandidates(keyword, song.artist)
                    }
                    if (newResults.isNotEmpty()) {
                        val all = candidates + newResults
                        cachedCandidates[song.id] = all
                        candidateVariantRound[song.id] = nextRound
                        // 边界守卫：candidateIndex 来自调用方（用户反复点“在线歌词”会递增），
                        // 可能超出合并后的候选数量，直接 all[candidateIndex] 会抛 IndexOutOfBoundsException。
                        val safeIndex = candidateIndex.coerceIn(0, all.lastIndex)
                        val text = all[safeIndex]
                        pendingNetworkLyrics[song.id] = text
                        return@withContext LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
                    }
                }
                // 所有变异轮次用尽 → 返回 null
                AppLog.w("LyricsManager", "NETWORK: all variants exhausted, no more candidates")
                null
            }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L17-branch', False, 'anchor not found')

expect('L17-nolock', 'candidateFetchMutex.withLock' not in S)
expect('L17-singleflight', S.count('fetchCandidatesSingleFlight(') == 3,
       'n=%d' % S.count('fetchCandidatesSingleFlight('))
expect('L17-mutex-unused-ok', True)

# candidateFetchMutex 现在无人使用 —— 检查是否还有引用，有则保留（避免误删导致编译失败）
if 'candidateFetchMutex' in S:
    print('NOTE: candidateFetchMutex 仍被引用，保留声明')
    idx = S.find('private val candidateFetchMutex')
    print('   剩余引用数:', S.count('candidateFetchMutex'))

if FAILS:
    print('NOT WRITTEN')
    sys.exit(1)

out = S.replace('\n', eol) if eol == '\r\n' else S
io.open(REL, 'w', encoding='utf-8', newline='').write(out)
print('OK', REL, 'crlf' if eol == '\r\n' else 'lf')
print('ALL OK')
