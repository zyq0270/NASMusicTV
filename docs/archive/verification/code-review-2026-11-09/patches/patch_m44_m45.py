#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M44: 日志列表改 LazyColumn；M45: 流派条形图 Top-N 折叠"""
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

# ================= M44 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/screens/settings/LogsSettingsSection.kt'
S, eol = load(REL)

old = """                else -> visibleEntries.forEach { entry ->
                    LogEntryRow(entry = entry, onOpen = { selectedEntry = entry })
                }"""
new = """                // M44：最多 100 条日志原先是 forEach 一次性铺进非 lazy 的 Column ——
                // 整个分区是外层 LazyColumn 里的**一个** item、行内也没有 key，
                // 100 条多行 Text 全部实时布局，筛选/刷新一次就要全量重排。
                // 改成有界高度的 LazyColumn：只组合可见项，key 用「时间戳 + 序号」。
                // ⚠️ 高度必须有界：本分区外层是 verticalScroll 的 Column，
                //    给 LazyColumn 无限高约束会直接崩（measured with infinity maximum height）。
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = LOG_LIST_MAX_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        items = visibleEntries,
                        key = { index, entry -> "${entry.timestamp}_$index" },
                    ) { _, entry ->
                        LogEntryRow(entry = entry, onOpen = { selectedEntry = entry })
                    }
                }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M44-body', False, 'anchor not found')
expect('M44-lazy', 'else -> LazyColumn(' in S)

# 常量（放在 LOG_LEVEL_FILTERS 之前）
old = """private data class LogLevelFilter(val level: String, @StringRes val labelRes: Int)"""
new = """/**
 * M44：日志列表的最大可视高度。
 *
 * 取一个有界值而不是 `fillMaxHeight()`，是因为本分区最终嵌在**可垂直滚动**的设置页里，
 * 无界高度约束会让 LazyColumn 直接抛 `measured with infinity maximum height constraints`。
 * 360dp ≈ 6~7 条（单行条目）+ 滚动查看其余。
 */
private val LOG_LIST_MAX_HEIGHT = 360.dp

private data class LogLevelFilter(val level: String, @StringRes val labelRes: Int)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M44-const', False, 'anchor not found')

for imp, anchor in (
    ('import androidx.compose.foundation.lazy.LazyColumn\n', 'import androidx.compose.foundation.rememberScrollState\n'),
    ('import androidx.compose.foundation.lazy.itemsIndexed\n', 'import androidx.compose.foundation.rememberScrollState\n'),
):
    if imp.strip() in S:
        continue
    if anchor in S:
        S = S.replace(anchor, imp + anchor, 1)
    else:
        expect('M44-imp:' + imp.strip(), False)
expect('M44-imports',
       'import androidx.compose.foundation.lazy.LazyColumn' in S and
       'import androidx.compose.foundation.lazy.itemsIndexed' in S)
expect('M44-const-use', 'heightIn(max = LOG_LIST_MAX_HEIGHT)' in S)
expect('M44-no-foreach', 'else -> visibleEntries.forEach' not in S)

if FAILS:
    print('NOT WRITTEN(1)')
    sys.exit(1)
save(REL, S, eol)

# ================= M45 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/screens/stats/PlayStatsScreen.kt'
S, eol = load(REL)

# 调用点：高度改由图表自己决定
old = """        GenreBarChart(
            genres = bundle.genreDistribution,
            modifier = Modifier
                .fillMaxWidth()
                .height((bundle.genreDistribution.size * 44).coerceAtMost(320).dp)
        )"""
new = """        // M45：不再由调用方按「流派条数 × 44dp，封顶 320dp」算高度 —— 那个封顶正是
        // 「流派一多，条形被裁掉且看不到」的来源（Column 不可滚动、TV 上也不可聚焦）。
        // 现在图表内部折叠成 Top-N +「其他」，高度自然收敛，这里只给宽度。
        GenreBarChart(
            genres = bundle.genreDistribution,
            modifier = Modifier.fillMaxWidth()
        )"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M45-callsite', False, 'anchor not found')

old = """/** 流派分布横向条形图（原生 Canvas，不引第三方图表库） */
@Composable
private fun GenreBarChart(genres: List<GenreStat>, modifier: Modifier = Modifier) {
    val maxCount = genres.maxOf { it.playCount }.coerceAtLeast(1)
    val accent = NasMusicColors.Primary
    val trackColor = NasMusicColors.Surface
    val labelColor = NasMusicColors.TextPrimary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { g ->"""
new = """/** 流派条形图最多展示的行数（M45：超出的长尾折叠成一行「其他」） */
private const val GENRE_CHART_MAX_ROWS = 8

/**
 * 把 Top-N 之后的长尾折叠成一行「其他 (N 个流派)」。
 *
 * M45：原实现直接把全量 `genreDistribution` 铺进一个固定封顶 320dp 的 `Column`，
 * 流派数一多，多出来的部分就**被裁掉且无从查看**（Column 不滚动、TV 上也不可聚焦）。
 * 折叠成 Top-(N-1) + 其他既能保留全部计数（播放次数求和），又保证高度收敛。
 */
private fun foldGenres(genres: List<GenreStat>, maxRows: Int, othersLabel: String): List<GenreStat> {
    if (genres.size <= maxRows) return genres
    val head = genres.take(maxRows - 1)
    val tail = genres.drop(maxRows - 1)
    val othersPlays = tail.sumOf { it.playCount }
    val othersSongs = tail.sumOf { it.songCount }
    return head + GenreStat(genre = othersLabel, playCount = othersPlays, songCount = othersSongs)
}

/** 流派分布横向条形图（原生 Canvas，不引第三方图表库） */
@Composable
private fun GenreBarChart(genres: List<GenreStat>, modifier: Modifier = Modifier) {
    val othersLabel = stringResource(R.string.pstats_genre_others, genres.size - (GENRE_CHART_MAX_ROWS - 1))
    val rows = remember(genres, othersLabel) { foldGenres(genres, GENRE_CHART_MAX_ROWS, othersLabel) }
    val maxCount = rows.maxOf { it.playCount }.coerceAtLeast(1)
    val accent = NasMusicColors.Primary
    val trackColor = NasMusicColors.Surface
    val labelColor = NasMusicColors.TextPrimary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { g ->"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M45-chart', False, 'anchor not found')

# remember / stringResource 导入
for imp in ('import androidx.compose.runtime.remember',):
    if imp not in S:
        anchor = 'import androidx.compose.runtime.Composable\n'
        if anchor in S:
            S = S.replace(anchor, anchor + imp + '\n', 1)
        else:
            expect('M45-imp', False, 'anchor not found')
expect('M45-remember', 'import androidx.compose.runtime.remember' in S)
expect('M45-stringres', 'import androidx.compose.ui.res.stringResource' in S, 'stringResource import missing')
expect('M45-fold', 'foldGenres(genres, GENRE_CHART_MAX_ROWS, othersLabel)' in S)

if FAILS:
    print('NOT WRITTEN(2)')
    sys.exit(1)
save(REL, S, eol)

# ================= 字符串资源 =================
for rel, val in (
    ('app/src/main/res/values/strings.xml', '<string name="pstats_genre_others">其他（%1$d 个流派）</string>'),
    ('app/src/main/res/values-en/strings.xml', '<string name="pstats_genre_others">Others (%1$d genres)</string>'),
):
    S, eol = load(rel)
    if 'name="pstats_genre_others"' in S:
        print('SKIP', rel)
        continue
    anchor = None
    for name in ('pstats_genre_distribution',):
        i = S.find('<string name="%s">' % name)
        if i >= 0:
            j = S.find('</string>', i)
            anchor = j + len('</string>')
            break
    if anchor is None:
        expect('STR:' + rel, False, 'anchor not found')
        continue
    S = S[:anchor] + '\n    ' + val + S[anchor:]
    save(rel, S, eol)

print()
if FAILS:
    print('FAILED:', FAILS)
    sys.exit(1)
print('ALL OK')
