/*!
 * @name         夹具·从不 inited
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * 只注册处理器、从不 `send(inited)` —— 对应真实脚本里「初始化链路挂死」那一类
 * （野花就是：初始化请求失败后既不 inited 也不抛）。期望 `LxScriptInfo.Failed(L1_TIMEOUT)`。
 */
lx.on(lx.EVENT_NAMES.request, async () => 'https://fixture.local/never')
