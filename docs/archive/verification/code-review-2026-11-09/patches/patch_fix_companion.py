# -*- coding: utf-8 -*-
"""修正 L09 引入的第二个 companion object：Kotlin 一个类只允许一个 companion object。

做法：把 WHITESPACE_RUN 并入既有 companion（`SearchAggregator` 类体 91 行那一个），
删掉 428 行那个重复的 `private companion object`，并把原本错位的 KDoc 归还给
deduplicateWithinSource。
"""
import io
import sys

P = 'app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt'

with io.open(P, 'r', encoding='utf-8', newline='') as f:
    S = f.read()

assert '\r\n' not in S, 'expect LF only'

# ---- 1) WHITESPACE_RUN 并入既有 companion ----
OLD1 = """            private const val LOCAL_TIMEOUT = 2_000L
    }
"""
NEW1 = """            private const val LOCAL_TIMEOUT = 2_000L

            /** L09：连续空白（含全角空格 U+3000 与不换行空格 U+00A0）折叠用 */
            private val WHITESPACE_RUN = Regex("[\\\\s\\\\u00A0\\\\u3000]+")
    }
"""
if OLD1 not in S:
    print('FAIL anchor1')
    sys.exit(1)
assert S.count(OLD1) == 1, 'anchor1 not unique: %d' % S.count(OLD1)
S = S.replace(OLD1, NEW1, 1)

# ---- 2) 删掉重复的 companion，KDoc 归位 ----
OLD2 = """    /**
     * 同源内去重：相同 title+artist 归一化后只保留第一个
     * 跨源不去重（不同源的同一首歌保留多条，各自标注来源）
     */
    private companion object {
        /** L09：连续空白（含全角空格 U+3000 与不换行空格 U+00A0）折叠用 */
        val WHITESPACE_RUN = Regex("[\\\\s\\\\u00A0\\\\u3000]+")
    }

"""
NEW2 = """    /**
     * 同源内去重：相同 title+artist 归一化后只保留第一个
     * 跨源不去重（不同源的同一首歌保留多条，各自标注来源）
     */
"""
if OLD2 not in S:
    print('FAIL anchor2')
    sys.exit(1)
S = S.replace(OLD2, NEW2, 1)

# ---- 断言 ----
assert S.count('companion object') == 1, 'companion count = %d' % S.count('companion object')
assert S.count('WHITESPACE_RUN') == 2, 'WHITESPACE_RUN use = %d' % S.count('WHITESPACE_RUN')
assert 'val WHITESPACE_RUN = Regex' in S
assert 'raw.trim().lowercase().replace(WHITESPACE_RUN, " ")' in S
# 类尾部其它声明仍在
for name in ('private fun deduplicateWithinSource', 'private fun normalizeKey',
             'private fun matchesBySearchType'):
    assert name in S, 'lost: ' + name

with io.open(P, 'w', encoding='utf-8', newline='') as f:
    f.write(S)
print('ALL OK')
