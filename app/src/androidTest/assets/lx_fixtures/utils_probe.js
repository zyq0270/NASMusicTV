/*!
 * @name         夹具·工具自检
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * 覆盖 `lx.utils` 全部分支：crypto（md5 / randomBytes / aes 往返）、zlib 往返、
 * buffer 编解码、以及 `lx.env` / `lx.version` / `lx.currentScriptInfo`。
 * 结果拼成 `|` 分隔的字符串返回，由测试逐段断言。
 */
lx.on(lx.EVENT_NAMES.request, () => {
  const md5 = lx.utils.crypto.md5('abc')
  const rnd = lx.utils.crypto.randomBytes(16)
  const rnd2 = lx.utils.crypto.randomBytes(16)

  const b64 = Buffer.from('hello 世界', 'utf8').toString('base64')
  const back = Buffer.from(b64, 'base64').toString('utf8')
  const hex = Buffer.from([0, 1, 15, 16, 255]).toString('hex')
  const bufStr = lx.utils.buffer.bufToString(Buffer.from('abc'), 'hex')

  const key = Buffer.from('0123456789abcdef')
  const iv = Buffer.from('abcdef0123456789')
  const enc = lx.utils.crypto.aesEncrypt(Buffer.from('secret'), 'aes-128-cbc', key, iv)
  const dec = lx.utils.crypto.aesDecrypt(enc, 'aes-128-cbc', key, iv).toString('utf8')

  const inf = lx.utils.zlib.inflate(lx.utils.zlib.deflate(Buffer.from('zlib-body')))

  return [
    md5,
    rnd.length,
    rnd.toString('hex') === rnd2.toString('hex') ? 'same' : 'diff',
    b64,
    back,
    hex,
    bufStr,
    dec,
    inf.toString('utf8'),
    lx.env,
    lx.version,
    lx.currentScriptInfo.name,
    typeof lx.currentScriptInfo.rawScript === 'string' && lx.currentScriptInfo.rawScript.length > 0 ? 'raw' : 'noraw'
  ].join('|')
})

lx.send(lx.EVENT_NAMES.inited, {
  status: true,
  sources: { wy: { name: 'wy', actions: ['musicUrl'], qualitys: ['128k'] } }
})
