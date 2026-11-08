# -*- coding: utf-8 -*-
"""把 docs/globe-present.html 拆成 App 内的 index.html + globe.js。

用法（仓库根目录）：
    python scripts/build_eco_globe_page.py

设计（见 docs/eco-globe-webview-plan.md §4.5）：
  * 页面结构与样式来自源文件（`<style>` … `</head>` 与 `<body>` 内的 DOM）；
  * 业务代码来自 `<script type="module">` 全体，改成**普通 `<script src>`** 加载；
  * ⛔ ESM importmap / import 语句全部去掉 ⇒ 复用 E41 的 ES5 UMD 产物（绝对路径 /globe/…）；
  * 模型 / 贴图路径改成 /eco-globe/…（虚拟资产域绝对路径）；
  * 删掉 poly.pizza 的 CDN 回退与 url 字段 ⇒ **运行时零网络**；
  * 追加 `window.EcoGlobe`（setAudio / initApp / boot）给 Kotlin 宿主用。

本脚本是幂等的：重复跑覆盖同一份产物。源文件改动后重跑即可，不需要手工同步。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'docs', 'globe-present.html')
OUT_DIR = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'eco-globe')
OUT_HTML = os.path.join(OUT_DIR, 'index.html')
OUT_JS = os.path.join(OUT_DIR, 'globe.js')


def must(cond, msg):
    if not cond:
        sys.stderr.write('✗ ' + msg + '\n')
        sys.exit(1)


def main():
    with io.open(SRC, encoding='utf-8') as f:
        src = f.read()
    lines = src.split('\n')

    def find_line(pred, what, start=0):
        for i in range(start, len(lines)):
            if pred(lines[i]):
                return i
        raise SystemExit('✗ 源文件结构变了：找不到 ' + what)

    i_style_end = find_line(lambda l: l.strip() == '</style>', '</style>')
    i_body = find_line(lambda l: l.strip() == '<body>', '<body>')
    i_importmap = find_line(lambda l: l.strip() == '<script type="importmap">', 'importmap 起始')
    i_module = find_line(lambda l: l.strip() == '<script type="module">', 'module script 起始')
    i_module_end = max(i for i, l in enumerate(lines) if l.strip() == '</script>')

    must(i_style_end < i_body < i_importmap < i_module < i_module_end, '源文件段落顺序异常')

    head = lines[:i_style_end + 1]                 # <!DOCTYPE … </style>
    body = lines[i_body + 1:i_importmap]           # body 内容（含注释与 DOM）
    js = lines[i_module + 1:i_module_end]           # module script 内容

    # ── 1. 去掉 ESM import（改用 E41 的全局 THREE 与本页 loaders 产物） ──────────────
    must(any('import * as THREE from "three"' in l for l in js), '找不到 THREE 的 ESM import')
    i_imp0 = next(i for i, l in enumerate(js) if 'import * as THREE from "three"' in l)
    i_imp1 = next(i for i, l in enumerate(js)
                  if 'import { clone as cloneSkeleton }' in l)
    must(i_imp0 == 0, 'import 块不在脚本开头（第 %d 行）' % (i_imp0 + 1))
    js = js[i_imp1 + 1:]

    js = [
        '// ⛔ 已去掉 ESM import：THREE 来自 /globe/three.es5.js 的 UMD 全局，',
        '//    GLTFLoader / SkeletonUtils 来自 /eco-globe/loaders.es5.js（本页 esbuild 产物）。',
        '//    由 scripts/build_eco_globe_page.py 从 docs/globe-present.html 生成。',
    ] + js

    text = '\n'.join(js)

    # ── 2. 模型 / 贴图路径 → 虚拟资产域绝对路径 ──────────────────────────────────
    n_glb = len(re.findall(r"'assets/glb/([\w.]+\.glb)'", text))
    must(n_glb == 16, '预期 16 个 GLB 路径，实际 %d' % n_glb)
    text = re.sub(r"'assets/glb/([\w.]+\.glb)'", r"'/eco-globe/glb/\1'", text)
    must("bakedDir: 'assets/tex/'" in text, '找不到 bakedDir')
    text = text.replace("bakedDir: 'assets/tex/'", "bakedDir: '/eco-globe/tex/'")

    # ── 3. 运行时零网络：删掉 CDN 回退与 url 字段 ────────────────────────────────
    n_url = len(re.findall(r",\s*url: 'https://static\.poly\.pizza/[\w.-]+\.glb'", text))
    must(n_url == 16, '预期 16 个 CDN url 字段，实际 %d' % n_url)
    text = re.sub(r",\s*url: 'https://static\.poly\.pizza/[\w.-]+\.glb'", '', text)

    old_fallback = (
        "    // 本地副本优先。部署时若没带 docs/assets/glb/,自动回退到原 CDN 地址。\n"
        "    return fetchGLTF(spec.local).catch(function () {\n"
        "      console.warn('[globe-present] ' + spec.name + ': 本地 GLB 不可用,回退 CDN');\n"
        "      return fetchGLTF(spec.url);\n"
        "    }).then(function (gltf) {"
    )
    new_fallback = (
        "    // ⛔ 运行时零网络：没有 CDN 回退，缺文件直接失败（方案 §4.5 / §九 R4）。\n"
        "    return fetchGLTF(spec.local).then(function (gltf) {"
    )
    must(old_fallback in text, '找不到 loadGLB 的 CDN 回退块')
    text = text.replace(old_fallback, new_fallback)

    must('static.poly.pizza' not in text, '仍残留 poly.pizza 引用')
    # 错误提示里的 CDN 说法一并改掉（否则用户会以为可以联网重试）
    text = text.replace('(poly.pizza CDN 加载?)', '(应用内资产缺失?)')
    text = text.replace('(poly.pizza CDN 不可达?)', '(应用内资产缺失?)')

    # ── 4. 音频输入：把歌曲能量映射到自转 / 云层速度（原型页没有音频） ─────────────
    must('earth.rotation.y += dt * SPIN_RATE;' in text, '找不到地球自转行')
    text = text.replace(
        'earth.rotation.y += dt * SPIN_RATE;',
        'earth.rotation.y += dt * SPIN_RATE * (1 + AUDIO.energy * 0.85 + AUDIO.beat * 0.35);')
    must('clouds.rotation.y += dt * CLOUD_RATE;' in text, '找不到云层旋转行')
    text = text.replace(
        'clouds.rotation.y += dt * CLOUD_RATE;',
        'clouds.rotation.y += dt * CLOUD_RATE * (1 + AUDIO.energy * 0.6);')

    # 帧率采样（宿主 renderFps() 的数据源）
    anchor = '\n    renderer.render(scene, camera);'
    must(anchor in text, '找不到 renderer.render 行，无法插入帧率采样')
    fps_block = (
        '\n'
        '    // 帧率采样（500ms 滑窗）：View 型渲染器有自己的 rAF 循环，\n'
        '    // 舞台的 Choreographer 徽章对它是假读数 ⇒ 由本页自己上报。\n'
        '    FPS.n++;\n'
        '    if (now - FPS.t >= 500) {\n'
        '      FPS.v = FPS.n * 1000 / Math.max(1, now - FPS.t);\n'
        '      FPS.n = 0; FPS.t = now;\n'
        '    }'
    )
    text = text.replace(anchor, fps_block + anchor, 1)

    # AUDIO 必须定义在任何函数使用它之前 —— 放在脚本最前面（import 注释之后）
    audio_decl = [
        '',
        '/* ---------- 音频输入（Kotlin 宿主 100ms tick 调用 EcoGlobe.setAudio）----------',
        '   原型页是纯自动旋转，没有音频；接入 App 后只把能量映射成转速，',
        '   ⛔ 不引入随机性、不改任何摆放/季节逻辑，保证画面与演示页一致。 */',
        'var AUDIO = { energy: 0, bass: 0, mid: 0, treble: 0, beat: 0 };',
        '/* 实际渲染帧率（500ms 滑窗）—— 宿主 renderFps() 的唯一数据源。',
        '   View 型渲染器有独立 rAF 循环，舞台的 Choreographer 口径对它是假读数。 */',
        'var FPS = { v: 0, n: 0, t: 0 };',
        'function clampAudio(v) { return (typeof v === "number" && isFinite(v)) ? Math.max(0, Math.min(1, v)) : 0; }',
        'function setAudio(json) {',
        '  try {',
        '    var a = typeof json === "string" ? JSON.parse(json) : (json || {});',
        '    AUDIO.energy = clampAudio(a.energy);',
        '    AUDIO.bass = clampAudio(a.bass);',
        '    AUDIO.mid = clampAudio(a.mid);',
        '    AUDIO.treble = clampAudio(a.treble);',
        '    AUDIO.beat = clampAudio(a.beat);',
        '  } catch (e) { /* 坏数据不影响渲染 */ }',
        '}',
        '',
    ]
    # 插到注释 3 行之后（即原 js 的第一个空行前）
    text = '\n'.join(audio_decl) + text

    # ── 5. 包成 IIFE（module 作用域 → 函数作用域），只暴露 window.EcoGlobe ─────────
    footer = [
        '',
        '/* ---------- 宿主入口 ----------',
        '   Kotlin 侧只认这三个；其余保持原型页的模块内私有作用域不变。 */',
        'window.EcoGlobe = {',
        '  setAudio: setAudio,',
        '  initApp: initApp,',
        '  boot: boot,',
        '  fps: function () { return FPS.v; }',
        '};',
        '',
    ]
    globe_js = (
        '/* 生态地球 · 业务代码\n'
        '   由 scripts/build_eco_globe_page.py 从 docs/globe-present.html 自动生成，⛔ 不要手改。\n'
        '   改动请改源文件（docs/globe-present.html）后重跑脚本。 */\n'
        '(function () {\n'
        "'use strict';\n"
        + text
        + '\n'.join(footer)
        + '})();\n'
    )

    # ── 6. index.html ───────────────────────────────────────────────────────────
    html = '\n'.join(head) + '\n</head>\n<body>\n' + '\n'.join(body) + '\n'
    html += (
        '\n<!-- 共享产物：E41「世界」的 ES5 UMD（⛔ 不复制，直接引用 /globe/）；'
        '顺序不可换 -->\n'
        '<script src="/globe/polyfill.es5.js"></script>\n'
        '<script src="/globe/three.es5.js"></script>\n'
        '<script src="/globe/three-globe.es5.js"></script>\n'
        '<script src="/eco-globe/loaders.es5.js"></script>\n'
        '<script src="/eco-globe/globe.js"></script>\n'
        '</body>\n</html>\n'
    )

    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)
    with io.open(OUT_HTML, 'w', encoding='utf-8', newline='\n') as f:
        f.write(html)
    with io.open(OUT_JS, 'w', encoding='utf-8', newline='\n') as f:
        f.write(globe_js)

    # ⚠️ 控制台是 GBK，别用 ✓/✗ 这类非 ASCII 字符打印（会 UnicodeEncodeError）
    print('[ok] index.html  %d B' % os.path.getsize(OUT_HTML))
    print('[ok] globe.js    %d B' % os.path.getsize(OUT_JS))


if __name__ == '__main__':
    main()
