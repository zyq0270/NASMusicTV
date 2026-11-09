#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Low 批次 2：L06 / L08 / L09 / L10 / L11（C03、L07 已在前面批次修掉，跳过）"""
import io, sys

FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

def load(rel):
    raw = io.open(rel, 'r', encoding='utf-8', newline='').read()
    crlf = raw.count('\r\n')
    eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
    return raw.replace('\r\n', '\n'), eol

def save(rel, S, eol):
    out = S.replace('\n', eol) if eol == '\r\n' else S
    io.open(rel, 'w', encoding='utf-8', newline='').write(out)
    print('OK', rel, 'crlf' if eol == '\r\n' else 'lf')

# ================= L06 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/impl/DaoliyuAdapter.kt'
S, eol = load(REL)
old = """        val rawAlbumId = obj.get("albumId")?.asString ?: albumId"""
new = """        // L06：两个来源的「前缀状态」不同 —— `obj.albumId` 是服务端裸值，
        // 而 `albumId` 入参来自 `getAlbumSongs(albumId)`，调用方传的是**已带前缀**的 id。
        // 原实现对回退值再拼一次前缀，得到 `daoliyu_daoliyu_XXX`：
        // 按专辑取歌 / 取封面时 id 对不上，详情页封面与「同专辑其它歌」全部落空。
        // 这里先统一剥掉前缀，后面只加一次。
        val rawAlbumId = (obj.get("albumId")?.asString ?: albumId)?.removePrefix(SONG_ID_PREFIX)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L06', False, 'anchor not found')
expect('L06-strip', 'removePrefix(SONG_ID_PREFIX)' in S)
if FAILS:
    print('NOT WRITTEN(1)'); sys.exit(1)
save(REL, S, eol)

# ================= L08 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt'
S, eol = load(REL)
old = """            AppLog.d("JellyfinAdapter", "utf8Body: GBK fallback applied for ${request.url} (U+FFFD detected in UTF-8)")"""
new = """            // L08：同一函数下一行已经改用 `UrlSanitizer.sanitize` —— Jellyfin 的 api_key
            // 是明文 query 参数，直接把 `request.url` 打进日志会把它落盘。
            AppLog.d("JellyfinAdapter", "utf8Body: GBK fallback applied for ${UrlSanitizer.sanitize(request.url.toString())} (U+FFFD detected in UTF-8)")"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L08', False, 'anchor not found')
expect('L08-sanitize', S.count('UrlSanitizer.sanitize(request.url.toString())') >= 2)
if FAILS:
    print('NOT WRITTEN(2)'); sys.exit(1)
save(REL, S, eol)

# ================= L09 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt'
S, eol = load(REL)
old = """    /**
     * 归一化歌曲标识：小写 + 去除首尾空白 + 合并连续空格
     */
    private fun normalizeKey(title: String, artist: String): String {
        return "${title.trim().lowercase()}|${artist.trim().lowercase()}"
    }"""
new = """    /**
     * 归一化歌曲标识：小写 + 去除首尾空白 + 合并连续空格
     *
     * L09：KDoc 一直承诺「合并连续空格」，实现却没做 —— 同一首歌在不同源里常常是
     * `"Song  Title"` / `"Song Title"`（多个空格来自标签里的对齐填充），
     * 归一化没折掉就判成两首，同源去重失效（列表里出现两条一模一样的歌）。
     */
    private fun normalizeKey(title: String, artist: String): String {
        return "${squeezeSpaces(title)}|${squeezeSpaces(artist)}"
    }

    /** 小写 + trim + 连续空白折叠成单个空格（L09） */
    private fun squeezeSpaces(raw: String): String =
        raw.trim().lowercase().replace(WHITESPACE_RUN, " ")"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L09', False, 'anchor not found')

# 常量：放进 companion object 之前找一处 private val
old = """    private fun deduplicateWithinSource(ranked: List<RankedSong>): List<RankedSong> {"""
new = """    private companion object {
        /** L09：连续空白（含全角空格 U+3000 与不换行空格 U+00A0）折叠用 */
        val WHITESPACE_RUN = Regex("[\\\\s\\\\u00A0\\\\u3000]+")
    }

    private fun deduplicateWithinSource(ranked: List<RankedSong>): List<RankedSong> {"""
if old in S and 'val WHITESPACE_RUN' not in S:
    S = S.replace(old, new, 1)
else:
    expect('L09-const', False, 'anchor not found or already present')
expect('L09-regex', 'WHITESPACE_RUN' in S)
if FAILS:
    print('NOT WRITTEN(3)'); sys.exit(1)
save(REL, S, eol)

# ================= L10 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt'
S, eol = load(REL)
old = """            AppLog.d(TAG, "login success, token=${token.take(8)}...")"""
new = """            // L10：令牌是可直接冒充用户的凭据（服务端只认 `Authorization: <userToken>`，
            // 无过期时间、无二次校验）⇒ 连前 8 位都不该落盘。只记世代号，足以判断
            // 「是否真的重新登录过」，排障时配合服务端日志即可定位。
            AppLog.d(TAG, "login success, tokenGeneration=$tokenGeneration")"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L10', False, 'anchor not found')
expect('L10-notoken', 'token.take(8)' not in S)
if FAILS:
    print('NOT WRITTEN(4)'); sys.exit(1)
save(REL, S, eol)

# ================= L11 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt'
S, eol = load(REL)
old = """        try {
            baseUrl = ""
            username = ""
            password = ""
            apiToken = ""
            salt = ""
            AppLog.d("NavidromeAdapter", "close: auth state cleared (shared OkHttp pool retained)")"""
new = """        try {
            baseUrl = ""
            username = ""
            password = ""
            apiToken = ""
            salt = ""
            // L11：认证态是**两处**存的 —— initialize 时同步给 restClient 一份
            // （见 R-10 注释），close 只清了本类字段 ⇒ restClient 里那串明文 password / salt
            // 会跨「断开连接」一直驻留到下一次 initialize，任何延迟引用 restClient 的请求
            // （分页拉取、封面批量取）仍会带着旧口令打旧服务器。
            restClient.baseUrl = ""
            restClient.username = ""
            restClient.password = ""
            restClient.salt = ""
            AppLog.d("NavidromeAdapter", "close: auth state cleared (shared OkHttp pool retained)")"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L11', False, 'anchor not found')
expect('L11-restclient', 'restClient.password = ""' in S)
if FAILS:
    print('NOT WRITTEN(5)'); sys.exit(1)
save(REL, S, eol)
print('ALL OK')
