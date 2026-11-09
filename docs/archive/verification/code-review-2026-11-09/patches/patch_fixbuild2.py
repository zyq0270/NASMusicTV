# -*- coding: utf-8 -*-
"""编译验证第 2 轮：SongExporter.mediaScan 的 usbIndexRefresh 智能转换失败。

`suspendFunction1<String,Unit>?` 收在构造器 val 上，编译器不做智能转换
（属性可能被子类覆盖 / 不视为稳定）⇒ 先取本地 val 再判空。
"""
import io
import sys

P = 'app/src/main/java/com/nasmusic/tv/backend/export/SongExporter.kt'
with io.open(P, 'r', encoding='utf-8', newline='') as f:
    S = f.read()
eol = '\r\n' if '\r\n' in S else '\n'
S = S.replace('\r\n', '\n')

OLD = """        if (devicePath.isNotBlank() && usbIndexRefresh != null) {
            runCatching { usbIndexRefresh.invoke(devicePath) }
                .onFailure { AppLog.w(TAG, "mediaScan: usb index refresh failed: ${it.message}") }
        }"""
NEW = """        // 构造器 val 上的可空函数类型不做智能转换 ⇒ 先取本地 val（同一帧内引用稳定）
        val refresh = usbIndexRefresh
        if (devicePath.isNotBlank() && refresh != null) {
            runCatching { refresh.invoke(devicePath) }
                .onFailure { AppLog.w(TAG, "mediaScan: usb index refresh failed: ${it.message}") }
        }"""
assert S.count(OLD) == 1, 'anchor count=%d' % S.count(OLD)
S = S.replace(OLD, NEW, 1)
assert 'val refresh = usbIndexRefresh' in S

with io.open(P, 'w', encoding='utf-8', newline='') as f:
    f.write(S.replace('\n', eol))
print('OK SongExporter.kt (%s)' % ('CRLF' if eol == '\r\n' else 'LF'))
