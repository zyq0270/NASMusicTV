/*!
 * @name         夹具·基础音源
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * 覆盖：`inited` 声明两个平台的 actions/qualitys、ES6（const / 模板串 / 箭头函数 /
 * async-await）、`info.type` 音质透传。**不发任何网络请求**，可离线跑。
 */
const SOURCES = {
  wy: {
    name: '网易云音乐',
    actions: ['musicUrl', 'lyric'],
    qualitys: ['128k', '320k', 'flac']
  },
  kg: {
    name: '酷狗音乐',
    actions: ['musicUrl'],
    qualitys: ['128k', '320k']
  }
}

const handler = async ({ source, action, info }) => {
  if (action !== 'musicUrl') throw new Error('不支持的 action: ' + action)
  if (!info.songmid) throw new Error('缺少 songmid')
  return `https://fixture.local/${source}/${info.type || 'none'}/${info.songmid}`
}

lx.on(lx.EVENT_NAMES.request, handler)
lx.send(lx.EVENT_NAMES.inited, { status: true, sources: SOURCES })
