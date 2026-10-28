/*
 * 洛雪自定义源宿主 · JS 垫片（方案 §5.2）
 *
 * 设计边界：Java 侧只暴露少量 __native* 原语（见 LxScriptHost.registerNatives），
 * 协议语义（EVENT_NAMES / on / send / request 的 resp 形态 / utils）全部在这里实现。
 *
 * ⚠️ 两处只能在这里做的原因：
 *   1. `lx.request` 的 body 形态（JSON → 对象、文本 → 字符串）在 JS 里判断是几行的事，
 *      在 Kotlin 里要为每种形态构造嵌套 JSObject/JSArray。
 *   2. 脚本对 `lx.env`/`lx.version`/`currentScriptInfo` 有分支逻辑，必须与洛雪本体一致。
 */
(function () {
  'use strict'

  var G = typeof globalThis === 'object' ? globalThis : this

  /* ───────────────────────── console ───────────────────────── */

  // QuickJS 默认没有 console；Huibq 在 env != 'mobile' 分支会调 console.group，
  // 缺了任何一个都是 ReferenceError（方案 §5.2）。
  function toText(v) {
    if (typeof v === 'string') return v
    if (v instanceof Error) return v.name + ': ' + v.message
    try { return JSON.stringify(v) } catch (e) { return String(v) }
  }
  function emit(level) {
    return function () {
      var out = []
      for (var i = 0; i < arguments.length; i++) out.push(toText(arguments[i]))
      try { __nativeLog(level, out.join(' ')) } catch (e) { /* 日志桥自己出错不能再抛 */ }
    }
  }
  G.console = {
    log: emit('d'), info: emit('i'), debug: emit('d'),
    warn: emit('w'), error: emit('e'),
    group: emit('i'), groupEnd: function () {},
    trace: emit('d'), table: emit('d'),
    time: function () {}, timeEnd: function () {}
  }

  /* ───────────────────────── 字节工具 ───────────────────────── */

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

  function utf8Encode(str) {
    var out = [], i, c
    for (i = 0; i < str.length; i++) {
      c = str.charCodeAt(i)
      if (c < 0x80) out.push(c)
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63))
      else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var d = str.charCodeAt(i + 1)
        if (d >= 0xdc00 && d <= 0xdfff) {
          var cp = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00)
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63))
          i++
        } else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
      } else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
    }
    return new Uint8Array(out)
  }

  function utf8Decode(u8) {
    var out = '', i = 0, c, c2, c3, c4, cp
    while (i < u8.length) {
      c = u8[i++]
      if (c < 0x80) { out += String.fromCharCode(c); continue }
      if (c < 0xe0) { c2 = u8[i++] & 63; out += String.fromCharCode(((c & 31) << 6) | c2); continue }
      if (c < 0xf0) {
        c2 = u8[i++] & 63; c3 = u8[i++] & 63
        out += String.fromCharCode(((c & 15) << 12) | (c2 << 6) | c3); continue
      }
      c2 = u8[i++] & 63; c3 = u8[i++] & 63; c4 = u8[i++] & 63
      cp = ((c & 7) << 18) | (c2 << 12) | (c3 << 6) | c4
      cp -= 0x10000
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023))
    }
    return out
  }

  function latin1Decode(u8) {
    var out = ''
    for (var i = 0; i < u8.length; i++) out += String.fromCharCode(u8[i])
    return out
  }

  function b64Encode(u8) {
    var s = '', i, b0, b1, b2, t
    for (i = 0; i < u8.length; i += 3) {
      b0 = u8[i]; b1 = u8[i + 1]; b2 = u8[i + 2]
      t = (b0 << 16) | ((b1 === undefined ? 0 : b1) << 8) | (b2 === undefined ? 0 : b2)
      s += B64[(t >> 18) & 63] + B64[(t >> 12) & 63] +
        (b1 === undefined ? '=' : B64[(t >> 6) & 63]) +
        (b2 === undefined ? '=' : B64[t & 63])
    }
    return s
  }

  function b64Decode(str) {
    str = String(str).replace(/[^A-Za-z0-9+/]/g, '')
    var len = (str.length * 3) >> 2
    var out = new Uint8Array(len), o = 0, i, t
    for (i = 0; i < str.length; i += 4) {
      t = (B64.indexOf(str[i]) << 18) | (B64.indexOf(str[i + 1]) << 12)
      out[o++] = (t >> 16) & 255
      if (i + 2 < str.length) out[o++] = (t >> 8) & 255
      if (i + 3 < str.length) out[o++] = t & 255
    }
    return o === len ? out : out.subarray(0, o)
  }

  function hexEncode(u8) {
    var s = '', h
    for (var i = 0; i < u8.length; i++) { h = u8[i].toString(16); s += h.length < 2 ? '0' + h : h }
    return s
  }

  function hexDecode(str) {
    var s = String(str).replace(/[^0-9a-fA-F]/g, '')
    var out = new Uint8Array(s.length >> 1)
    for (var i = 0; i < out.length; i++) out[i] = parseInt(s.substr(i * 2, 2), 16)
    return out
  }

  /* ───────────────────────── Buffer ───────────────────────── */

  function bufferToString(enc) {
    enc = (enc || 'utf8').toLowerCase()
    if (enc === 'base64') return b64Encode(this)
    if (enc === 'hex') return hexEncode(this)
    if (enc === 'binary' || enc === 'latin1') return latin1Decode(this)
    return utf8Decode(this)
  }

  function bytesFrom(input, enc) {
    if (input instanceof Uint8Array) return new Uint8Array(input)
    if (input && typeof input.length === 'number' && typeof input !== 'string') {
      return new Uint8Array(Array.prototype.slice.call(input))
    }
    var s = String(input === undefined || input === null ? '' : input)
    enc = (enc || 'utf8').toLowerCase()
    if (enc === 'base64') return b64Decode(s)
    if (enc === 'hex') return hexDecode(s)
    if (enc === 'binary' || enc === 'latin1') {
      var out = new Uint8Array(s.length)
      for (var i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 255
      return out
    }
    return utf8Encode(s)
  }

  // Buffer 的实现是 Uint8Array + 一个 toString（洛雪脚本只用到 from / bufToString / toString）
  function mkBuffer(u8) {
    u8.toString = bufferToString
    return u8
  }

  var Buffer = {
    from: function (input, enc) { return mkBuffer(bytesFrom(input, enc)) },
    alloc: function (n) { return mkBuffer(new Uint8Array(n | 0)) },
    isBuffer: function (o) { return o instanceof Uint8Array && typeof o.toString === 'function' },
    concat: function (list) {
      var total = 0, i
      for (i = 0; i < list.length; i++) total += list[i].length
      var out = new Uint8Array(total), off = 0
      for (i = 0; i < list.length; i++) { out.set(list[i], off); off += list[i].length }
      return mkBuffer(out)
    }
  }
  G.Buffer = Buffer

  /* ───────────────────────── 定时器 ───────────────────────── */

  // 定时器回调留在 JS 侧（timerFns），Java 只知道 id —— 否则要把 JS 函数对象塞进 Java。
  var timerSeq = 0
  var timerFns = Object.create(null)

  G.setTimeout = function (fn, ms) {
    var id = ++timerSeq
    timerFns[id] = fn
    try { __nativeTimer('timeout', id, ms | 0) } catch (e) { delete timerFns[id] }
    return id
  }
  G.clearTimeout = G.clearInterval = function (id) {
    delete timerFns[id]
    try { __nativeClearTimer(id | 0) } catch (e) {}
  }
  G.setInterval = function (fn, ms) {
    var id = ++timerSeq
    timerFns[id] = fn
    try { __nativeTimer('interval', id, ms | 0) } catch (e) { delete timerFns[id] }
    return id
  }
  G.__runTimer = function (id, repeat) {
    var fn = timerFns[id]
    if (typeof fn !== 'function') return
    if (!repeat) delete timerFns[id]
    try { fn() } catch (e) { console.error(e) }
  }

  /* ───────────────────────── lx.request ───────────────────────── */

  var reqSeq = 0
  var pending = Object.create(null)

  function parseMaybeJson(text, contentType) {
    if (typeof text !== 'string' || text.length === 0) return text
    var looksJson = contentType && contentType.indexOf('json') >= 0
    var head = text.charAt(0)
    if (!looksJson && head !== '{' && head !== '[') return text
    try { return JSON.parse(text) } catch (e) { return text }
  }

  // Java 侧四个原语参数分开传，不拼 JSON 字符串 —— 省掉一层转义
  G.__lxOnResponse = function (id, ok, status, headersJson, bodyText) {
    var p = pending[id]
    if (!p) return
    delete pending[id]
    if (!ok) {
      var err = new Error(bodyText || ('request failed: ' + status))
      err.statusCode = status
      if (typeof p.callback === 'function') { try { p.callback(err) } catch (e) {} }
      try { p.reject(err) } catch (e) {}
      return
    }
    var headers = {}
    try { headers = JSON.parse(headersJson) || {} } catch (e) {}
    var resp = {
      statusCode: status,
      headers: headers,
      body: parseMaybeJson(bodyText, headers['content-type'] || headers['Content-Type']),
      raw: bodyText
    }
    if (typeof p.callback === 'function') { try { p.callback(null, resp) } catch (e) { console.error(e) } }
    try { p.resolve(resp) } catch (e) {}
  }

  G.__lxAbortAll = function () {
    for (var k in pending) {
      var p = pending[k]
      delete pending[k]
      var err = new Error('aborted')
      if (typeof p.callback === 'function') { try { p.callback(err) } catch (e) {} }
      try { p.reject(err) } catch (e) {}
    }
  }

  function request(url, options, callback) {
    if (typeof options === 'function') { callback = options; options = {} }
    options = options || {}
    var id = ++reqSeq
    var promise = new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject, callback: callback }
    })
    try {
      __nativeRequest(id, String(url), JSON.stringify(options))
    } catch (e) {
      delete pending[id]
      return Promise.reject(e)
    }
    return promise
  }

  /* ───────────────────────── utils ───────────────────────── */

  function toB64(input) { return b64Encode(bytesFrom(input)) }

  // Java 侧出错时返回 "__err:<原因>" 前缀而不是抛异常 —— Java 异常穿过 JNI 回到解释器
  // 属于未定义行为，把它变成一个普通的 JS 错误最稳。
  function nativeCall(value) {
    if (typeof value === 'string' && value.indexOf('__err:') === 0) {
      throw new Error(value.slice(6))
    }
    return value
  }

  var utils = {
    buffer: {
      from: function () { return Buffer.from.apply(Buffer, arguments) },
      bufToString: function (buf, format) { return Buffer.from(buf, 'binary').toString(format) }
    },
    crypto: {
      md5: function (str) {
        return nativeCall(__nativeCrypto('md5', JSON.stringify({ data: String(str) })))
      },
      randomBytes: function (size) {
        return mkBuffer(b64Decode(nativeCall(__nativeCrypto('randomBytes', JSON.stringify({ size: size | 0 })))))
      },
      aesEncrypt: function (buffer, mode, key, iv) {
        return mkBuffer(b64Decode(nativeCall(__nativeCrypto('aesEncrypt', JSON.stringify({
          data: toB64(buffer), mode: String(mode), key: toB64(key), iv: iv ? toB64(iv) : null
        })))))
      },
      aesDecrypt: function (buffer, mode, key, iv) {
        return mkBuffer(b64Decode(nativeCall(__nativeCrypto('aesDecrypt', JSON.stringify({
          data: toB64(buffer), mode: String(mode), key: toB64(key), iv: iv ? toB64(iv) : null
        })))))
      },
      rsaEncrypt: function (buffer, key) {
        return mkBuffer(b64Decode(nativeCall(__nativeCrypto('rsaEncrypt', JSON.stringify({
          data: toB64(buffer), key: String(key)
        })))))
      }
    },
    zlib: {
      inflate: function (buffer) {
        return mkBuffer(b64Decode(nativeCall(__nativeZlib('inflate', toB64(buffer)))))
      },
      deflate: function (buffer) {
        return mkBuffer(b64Decode(nativeCall(__nativeZlib('deflate', toB64(buffer)))))
      }
    }
  }

  /* ───────────────────────── lx 本体 ───────────────────────── */

  var handlers = Object.create(null)
  var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' }

  function scriptInfo() {
    var info = {}
    try { info = JSON.parse(String(G.__scriptInfoJson || '{}')) || {} } catch (e) {}
    // rawScript 必须是原始文件内容（不 trim、不转义）—— 部分脚本拿它做校验
    info.rawScript = String(G.__rawScript || '')
    return info
  }

  G.lx = {
    EVENT_NAMES: EVENT_NAMES,
    env: 'mobile',
    version: '2.0.0',
    currentScriptInfo: scriptInfo(),
    on: function (ev, fn) { handlers[ev] = fn },
    send: function (ev, data) {
      try { __nativeSend(String(ev), JSON.stringify(data === undefined ? null : data)) } catch (e) { console.error(e) }
    },
    request: request,
    utils: utils
  }

  /* ───────────────────────── Java → JS 入口 ───────────────────────── */

  var invokeSeq = 0

  // token 由 Java 分配并原样回传，用来把异步结果接到 Kotlin 的 CompletableDeferred 上
  G.__lxInvoke = function (token, source, action, infoJson, quality) {
    var done = false
    function finish(ok, payload) {
      if (done) return
      done = true
      try { __nativeResult(token, ok, payload === undefined || payload === null ? '' : String(payload)) } catch (e) {}
    }
    try {
      var handler = handlers[EVENT_NAMES.request]
      if (typeof handler !== 'function') { finish(false, '脚本未注册 request 处理器'); return }
      var info = {}
      try { info = JSON.parse(infoJson) || {} } catch (e) {}
      // 洛雪的 musicUrl 请求把音质放在 info.type（不是单独形参）
      if (quality) info.type = quality
      Promise.resolve(handler({ source: String(source), action: String(action), info: info }))
        .then(function (url) { finish(true, typeof url === 'string' ? url : (url === undefined || url === null ? '' : String(url))) })
        .catch(function (err) { finish(false, err && err.message ? err.message : String(err)) })
    } catch (e) {
      finish(false, e && e.message ? e.message : String(e))
    }
  }

  G.__lxInvokeSeq = function () { return ++invokeSeq }
})()
