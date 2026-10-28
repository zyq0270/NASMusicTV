/*!
 * @name         夹具·加载即抛
 * @description  宿主测试夹具（自建，非真实音源）
 * @version      v1.0.0
 * @author       自建夹具
 *
 * 求值阶段直接抛错 —— 对应六音：正文自带校验，不满足条件就 `throw`
 * （实测 `加载音源脚本失败，请前往 http://www.sixyin.com 下载最新版本！`）。
 * 期望 `LxScriptInfo.Failed(L1_SCRIPT_ERROR)`，且 `detail` 里带得到原文。
 */
throw new Error('夹具：加载即失败')
