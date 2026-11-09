#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Low 批次 3：L12 / L13 / C19 / L14 / L15 / L16 / L17（C08 已修，跳过）"""
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

# ================= L12 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/download/CoverFileWriter.kt'
S, eol = load(REL)
old = """                return@withContext if (isNormalizedJpeg(existing)) {
                    existing
                } else {
                    MediaTagWriter.compressCover(existing) ?: existing
                }"""
new = """                return@withContext if (isNormalizedJpeg(existing)) {
                    existing
                } else {
                    // L12：压缩失败时原先回退 `existing`（原始字节，可能是 PNG / 超大图），
                    // 而调用方 `MediaTagWriter.embed` 一律按 `image/jpeg` 写入 ID3 ——
                    // 产出「声明 jpeg、内容 PNG」的 APIC 帧，部分播放器直接不显示封面、
                    // 且体积失控（专辑目录里的 cover.jpg 也可能就是几百 KB 的原图）。
                    // 归一化失败就返回 null：cover.jpg 仍在，只是这一首不做内嵌。
                    MediaTagWriter.compressCover(existing)
                }"""
if old in S:
    S = S.replace(old, new, 1)
elif 'L12：压缩失败时原先回退' in S:
    print('SKIP L12 (already applied)')
else:
    expect('L12', False, 'anchor not found')
expect('L12-nofallback', 'MediaTagWriter.compressCover(existing) ?: existing' not in S)
if FAILS:
    print('NOT WRITTEN(1)'); sys.exit(1)
save(REL, S, eol)

# ================= L13 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/local/CoverUrlPersistentCache.kt'
S, eol = load(REL)
old = """    private fun save() {
        synchronized(saveLock) {
            try {
                val json = gson.toJson(cache.toMap())
                val tmpFile = File(file.path + ".tmp")
                tmpFile.writeText(json)
                // 原子 rename：避免写文件中途崩溃导致缓存损坏
                if (!tmpFile.renameTo(file)) {
                    // rename 失败时回退为直接写
                    file.writeText(json)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "save failed: ${e.message}", e)
            }
        }
    }"""
new = """    private fun save() {
        synchronized(saveLock) {
            val tmpFile = File(file.path + ".tmp")
            try {
                val json = gson.toJson(cache.toMap())
                tmpFile.writeText(json)
                // 原子 rename：避免写文件中途崩溃导致缓存损坏
                if (!tmpFile.renameTo(file)) {
                    // L13：rename 只在「跨卷 / 目标被占用」时失败。原实现这里直接
                    // `file.writeText(json)` —— 等于放弃原子性（写一半被杀就损坏整份缓存），
                    // 而且 `.tmp` 上残留的旧内容从此再不会被清理，下次 save 又被覆盖但没人管。
                    // 改成「写第二份临时文件再 rename」重试一次；仍然失败就放弃本次保存
                    //（缓存可重建，宁可不写也不能写坏），并在 finally 里清掉两份临时文件。
                    val retryTmp = File(file.path + ".tmp2")
                    try {
                        retryTmp.writeText(json)
                        if (!retryTmp.renameTo(file)) {
                            AppLog.w(TAG, "save: rename failed twice, keeping previous cache file")
                        }
                    } finally {
                        if (retryTmp.exists()) retryTmp.delete()
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "save failed: ${e.message}", e)
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }
        }
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L13', False, 'anchor not found')
expect('L13-atomic', 'if (!tmpFile.renameTo(file)) {\n                    file.writeText(json)' not in S)
if FAILS:
    print('NOT WRITTEN(2)'); sys.exit(1)
save(REL, S, eol)

# ================= C19 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduFileIndexCache.kt'
S, eol = load(REL)
old = """    private fun getOrBuildDirIndex(): Map<String, List<BaiduIndexEntry>> {
        dirIndex?.let { return it }
        val index = cachedIndex ?: return emptyMap()
        val map = index.entries.groupBy { entry ->
            // parentDir = path 去掉尾部文件名后的目录路径
            entry.path.substringBeforeLast('/')
        }
        synchronized(cacheLock) {
            if (dirIndex == null) dirIndex = map
        }
        return dirIndex!!
    }"""
new = """    private fun getOrBuildDirIndex(): Map<String, List<BaiduIndexEntry>> {
        // C19：`dirIndex` 是 @Volatile，且 [save]/[load] 会把它置 null。
        // 原实现「判空 → 同步块内再判空赋值 → 出了同步块再读 dirIndex!!」——
        // 最后一次读与别人的置 null 之间没有任何保护 ⇒ 偶发 NPE。
        // 整个「读 → 建 → 写 → 读」都收进同一临界区，直接从锁内的局部变量返回值。
        dirIndex?.let { return it }
        val index = cachedIndex ?: return emptyMap()
        val map = index.entries.groupBy { entry ->
            // parentDir = path 去掉尾部文件名后的目录路径
            entry.path.substringBeforeLast('/')
        }
        synchronized(cacheLock) {
            val existing = dirIndex
            if (existing != null) return existing
            dirIndex = map
            return map
        }
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C19', False, 'anchor not found')
expect('C19-nonnull', 'return dirIndex!!' not in S)
if FAILS:
    print('NOT WRITTEN(3)'); sys.exit(1)
save(REL, S, eol)

# ================= L14 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/network/mv/MvPersistentCache.kt'
S, eol = load(REL)
old = """        cache.clear()
        entries.forEach { cache[it.songId] = it }
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()"""
new = """        cache.clear()
        // L14：先按最近播放时间取最新 MAX_ENTRIES 条再入表 —— 原实现把**全部**条目先塞进
        // map（一份几千条的备份会瞬时占用数十 MB，还要再做一次全量 sort 才 evict），
        // 而最终留下的本来就只有最新的那些。
        entries.sortedByDescending { it.lastPlayedAt }
            .take(MAX_ENTRIES)
            .forEach { cache[it.songId] = it }
        save()"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L14-import', False, 'anchor not found')

old = """    private fun load() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return"""
new = """    private fun load() {
        try {
            if (!file.exists()) return
            // L14：读盘必须有体积上限 —— 原实现 `readText()` 不判大小，
            // 一个被写坏的/超大的 mv_cache.json 会一次性把整个文件读进内存再 Gson 解析，
            // 低内存电视盒上直接 OOM。超限就当作无缓存（缓存可重建，丢一次远比崩掉好）。
            if (file.length() > MAX_CACHE_FILE_BYTES) {
                AppLog.w(TAG, "load: cache file too large (${file.length()} bytes), ignored")
                return
            }
            val json = file.readText()
            if (json.isBlank()) return"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L14-load', False, 'anchor not found')

if 'MAX_CACHE_FILE_BYTES' in S and 'val MAX_CACHE_FILE_BYTES' not in S:
    old = '    private val saveLock = Any()'
    new = """    /** L14：mv_cache.json 的读取上限（8MB ≈ 上万条，真实规模远小于此） */
    private val MAX_CACHE_FILE_BYTES = 8L * 1024 * 1024

    private val saveLock = Any()"""
    if old in S:
        S = S.replace(old, new, 1)
    else:
        expect('L14-const', False)
expect('L14-const-use', 'file.length() > MAX_CACHE_FILE_BYTES' in S)
if FAILS:
    print('NOT WRITTEN(4)'); sys.exit(1)
save(REL, S, eol)

# ================= L15 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/photo/YuNetFaceDetector.kt'
S, eol = load(REL)
old = """            val env = OrtEnvironment.getEnvironment()
            // ⛔ 绝不 close env：它是进程级单例（与 DemucsSeparator 同一条约定）
            session = env.createSession(bytes, opts)"""
new = """            val env = OrtEnvironment.getEnvironment()
            // ⛔ 绝不 close env：它是进程级单例（与 DemucsSeparator 同一条约定）
            //
            // L15：但 `SessionOptions` 是**本方法私有**的 native 资源，createSession 之后
            // 必须关闭 —— 原实现每次 warmUp 成功都漏掉一份（它不参与 GC 的 native 释放，
            // 反复出入设置页触发的 warmUp 会累积）。
            session = try {
                env.createSession(bytes, opts)
            } finally {
                runCatching { opts.close() }
            }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L15', False, 'anchor not found')
expect('L15-close', 'opts.close()' in S)
if FAILS:
    print('NOT WRITTEN(5)'); sys.exit(1)
save(REL, S, eol)

# ================= L16 =================
REL = 'app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduOAuthClient.kt'
S, eol = load(REL)
old = """                    val tokens = BaiduTokens(
                        accessToken = json.get("access_token").asString,
                        refreshToken = json.get("refresh_token").asString,
                        expiresAt = System.currentTimeMillis() +
                            (json.get("expires_in")?.asLong ?: 2592000L) * 1000,
                        scope = grantedScope
                    )"""
new = """                    // L16：`expires_in` 是服务端下发的秒数，未钳制直接 ×1000 会 Long 溢出
                    //（脏数据 / 被中间人篡改的响应给出极大值）⇒ `expiresAt` 变负数 ⇒
                    // `needsRefresh` 恒 true，表现为「刚授权完又要求重新授权」。
                    // 下限 60s：防止服务端给 0 导致每分钟刷新一次。
                    val expiresInSec = (json.get("expires_in")?.asLong ?: 2592000L)
                        .coerceIn(60L, 365L * 24 * 3600)
                    // `refresh_token` 并非所有链路都下发（设备码授权在部分场景只给 access_token），
                    // 原实现直接 `.asString` 会在缺字段时抛 NPE（JsonNull.asString）。
                    val refreshToken = json.get("refresh_token")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        .orEmpty()
                    val tokens = BaiduTokens(
                        accessToken = json.get("access_token").asString,
                        refreshToken = refreshToken,
                        expiresAt = System.currentTimeMillis() + expiresInSec * 1000,
                        scope = grantedScope
                    )"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L16', False, 'anchor not found')
expect('L16-clamp', 'coerceIn(60L, 365L * 24 * 3600)' in S)
if FAILS:
    print('NOT WRITTEN(6)'); sys.exit(1)
save(REL, S, eol)
print('ALL OK (L17 单独脚本)')
