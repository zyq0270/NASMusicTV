/*!
 * @name         夹具·请求回显
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * 覆盖 `lx.request` 桥接：`await` 拿到响应、`resp.body` 的 JSON 自动解析、`resp.headers`。
 * 目标 URL 由调用方放进 `info.albumName`（夹具专用通道，真实脚本不看这个字段）。
 */
lx.on(lx.EVENT_NAMES.request, async ({ action, info }) => {
  if (action !== 'musicUrl') throw new Error('不支持的 action: ' + action)
  const resp = await lx.request(String(info.albumName), { method: 'GET' })
  const url = resp.body && resp.body.url ? resp.body.url : '(no url)'
  const ct = resp.headers['content-type'] || 'unknown'
  return url + '|' + resp.statusCode + '|' + ct
})

lx.send(lx.EVENT_NAMES.inited, {
  status: true,
  sources: { wy: { name: 'wy', actions: ['musicUrl'], qualitys: ['320k'] } }
})
