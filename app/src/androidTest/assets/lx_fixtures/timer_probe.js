/*!
 * @name         夹具·定时器
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * 覆盖 `setInterval` / `clearInterval` 的双向桥接：Java 定时线程到点回调 JS，
 * JS 再 resolve 到 Kotlin。⛔ 若 job 队列不排空，这个 Promise 永远不会 settle。
 */
lx.on(lx.EVENT_NAMES.request, () => new Promise((resolve) => {
  let fired = 0
  const id = setInterval(() => {
    fired++
    if (fired === 2) {
      clearInterval(id)
      resolve('timer-ok')
    }
  }, 50)
}))

lx.send(lx.EVENT_NAMES.inited, {
  status: true,
  sources: { wy: { name: 'wy', actions: ['musicUrl'], qualitys: ['128k'] } }
})
