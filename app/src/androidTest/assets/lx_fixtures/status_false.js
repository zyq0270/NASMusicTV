/*!
 * @name         夹具·自报失败
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * `inited` 带 `status: false` + `message` —— 洛雪本体据此把脚本判为不可用。
 * 期望 `LxScriptInfo.Failed(L1_SCRIPT_ERROR)`。
 */
lx.on(lx.EVENT_NAMES.request, async () => 'https://fixture.local/x')
lx.send(lx.EVENT_NAMES.inited, { status: false, message: '夹具：初始化失败' })
