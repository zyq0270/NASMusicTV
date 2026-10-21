package com.nasmusic.tv.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.BuildConfig
import com.nasmusic.tv.util.AppLog

/**
 * 焦点指示器（全应用唯一共用机制）—— 真机反馈「遥控器导航完全看不到焦点在哪」的修复。
 *
 * 这里有**两件**必须放在一起的事，任何一处单独改都不完整：
 *
 * ## ① 画法：焦点环必须画在背景**之上**
 *
 * Compose 的绘制顺序 = 修饰符链顺序，越靠前越靠下。`FocusableSurface` 此前是
 * `border(...)` → `background(...)` ⇒ 不透明容器色把焦点环**整个盖住**。
 * 焦点环只在容器色为 `Color.Transparent` 的少数几个组件上侥幸可见，
 * 而所有卡片/列表行用的都是不透明 `NasMusicColors.Surface` ⇒ 全应用看不见焦点框。
 *
 * 本修饰符把焦点环追加到链尾，**画在最上层**。
 *
 * ## ② 判据：看「输入能力」，不看「设备特征」
 *
 * `PackageManager.hasSystemFeature(leanback / type.television)` 在非 Google 认证的
 * 电视盒子上经常双双返回 false（这个判据在本项目历史上已被迫补过两次：
 * 先从 leanback 扩到 `type.television`，v2.20.0 又修了「单查 leanback」）。
 * 而它一旦为 false，[FocusableSurface] 的 `activeFocus` 恒为 false ⇒ 缩放、焦点环、
 * 容器色、内容色**全部**关闭，用户只能靠按确认键反推焦点位置。
 *
 * 设备特征靠不住，**运行时输入能力**靠得住 —— 遥控器本身就是注册在系统里的
 * input device、声明了 `SOURCE_DPAD`，盒子再怎么隐瞒 leanback/television 也瞒不掉它。
 * 五条判据任一成立即显示焦点视觉：
 * 1. 没有触摸屏（`FEATURE_TOUCHSCREEN` 为 false；电视盒子 / 机顶盒 / 投影仪的常态）
 * 2. `FEATURE_LEANBACK` 或 `android.hardware.type.television`
 * 3. **`Configuration.touchscreen` 不是手指触摸**，或 `Configuration.keyboard` 报出了实体键盘
 *    （v2.39.6 新增，见 [isKeyNavigationConfiguration]）
 * 4. **存在支持 `SOURCE_DPAD` 的输入设备**（能力探测，遥控器/外接键盘/手柄）
 * 5. 进程内收到过任一方向/确认键（[DpadInputTracker] 兜底，覆盖运行中才接入的设备）
 * ## 为什么要加第 3 条（用户盒子看不见焦点环、我们的电视正常）
 * 判据 1 读的是 `<uses-feature>`（**厂商手写的 permissions.xml**），判据 3 读的是
 * `Configuration.touchscreen`（**框架按真实输入设备聚合出来的全局配置**）——
 * 两者是**互相独立**的来源。大量电视盒子会把 `android.hardware.touchscreen` 声明成
 * `required="false"` 以便装手机应用，于是判据 1 直接落空；但那台盒子并没有触摸数字化器，
 * 框架给出的 `configuration.touchscreen` 是 `TOUCHSCREEN_NOTOUCH` ⇒ 判据 3 命中。
 * 手机侧两个来源**一致**（FINGER + NOKEYS），不会被带进来看似 TV 的焦点环。
 *
 * 第 4 条是「切页面后焦点环立刻可见」这条需求的**唯一保证** ——
 * 前三条是静态嗅探，在刻意隐瞒特征的盒子上可能全部落空，而第 5 条必须先按一次键
 * 才会翻转，那正是用户拒绝的行为（「而不是先按一下才看到焦点环」）。
 *
 * ## 判据本身必须可回传（[logFocusVisualDiagnostics]）
 * 出问题的是**用户的**电视/盒子，不是开发机 —— 那边连不上 adb，`AppLog.d/i` 在 release
 * 又完全不产生。所以五条判据的实测值 + 全部输入设备清单会写进日志页的「调试」档
 * （[com.nasmusic.tv.util.LogStore.LEVEL_DIAG]），由用户截图/复制回传。
 * ⛔ 这条通道不能被 `BuildConfig.DEBUG` 门控，否则等于没有。
 *
 * 手机上 `clickable` 节点点一下会获得焦点且焦点粘住，因此在**只靠触摸**时仍需
 * 关闭焦点视觉（`docs/conventions-adaptive-ui.md` §11）。裸机手机五项判据全为 false：
 * 有触摸屏、正在触摸的那个 input device 不声明 `SOURCE_DPAD`、且没按过方向键。
 */

/** 焦点环实心描边的默认宽度。TV 远距离观看，2dp 太弱（且见上方 ① 的盖住问题）。 */
val FocusRingWidth: Dp = 3.dp

/** 焦点环外侧柔光带的默认总厚度（内含 [FocusRingWidth] 的实心环）。 */
val FocusHaloWidth: Dp = 7.dp

/**
 * 方向/确认类按键 —— 「用户正在用按键导航」的纯函数判据（可单测）。
 *
 * 只收**方向键 + 确认键**：音量/频道等媒体键由 [com.nasmusic.tv.util.MediaKeyHandler]
 * 处理，与焦点导航无关，收进来会让「按了音量键 ⇒ 之后焦点常亮」的误触发面变大。
 */
fun isDirectionalNavigationKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A -> true

    else -> false
}

/**
 * 方向键使用痕迹（进程级，粘住）。
 *
 * ⛔ 只记「有没有用过方向键」，**不记时间也不回收** —— 焦点视觉本身是无害的
 * （只是把已有的焦点画出来），而回收逻辑一旦出错就会在用户已经形成肌肉记忆后
 * 把指示器抽走，那正是本次要修的故障本身。
 */
object DpadInputTracker {
    /** 组合可读 —— 被 [shouldShowFocusVisuals] 在组合期读取，变化即触发重组。 */
    var directionalNavigationSeen by mutableStateOf(false)
        private set

    /**
     * 每个来源**最多记 [KEY_ARRIVAL_LOG_LIMIT] 条**到达记录。
     *
     * 上限存在的理由：这条记录是「遥控器键到底进没进 Compose」的取证手段，
     * 不是运行轨迹；方向键长按会连发（repeat），不设上限会把 400 行窗口里的
     * W/E 现场全挤掉 —— 那正是 [com.nasmusic.tv.util.LogStore] 反复强调要避免的事。
     */
    private const val KEY_ARRIVAL_LOG_LIMIT = 5

    /** 各来源已记录的条数（两个调用点都在主线程，无需并发容器）。 */
    private val arrivalLogged = HashMap<String, Int>()

    /**
     * 记录一个按键事件。
     *
     * @param source **必须是 "compose" 或 "window"** —— 这两个来源的差集正是故障定位点：
     *   只有 window 有记录 ⇒ 按键从没进过 Compose 焦点树（老盒子冷启动无焦点的形态）；
     *   两个都有 ⇒ 按键进来了，问题就只在别处（判据或绘制顺序）。
     *   ⚠️ 门禁 `FocusIndicatorContractTest` 认这两个字面量，改名要同步改门禁。
     * @param device 事件携带的输入设备（可为 null），只在真的要记日志时才格式化
     */
    fun noteKeyEvent(keyCode: Int, action: Int, source: String, device: InputDevice? = null) {
        if (action == KeyEvent.ACTION_DOWN && isDirectionalNavigationKey(keyCode)) {
            val logged = arrivalLogged[source] ?: 0
            if (logged < KEY_ARRIVAL_LOG_LIMIT) {
                arrivalLogged[source] = logged + 1
                AppLog.diag(
                    "Focus",
                    "按键到达[$source] code=$keyCode ${describeInputDevice(device)} " +
                        "（第 ${logged + 1}/$KEY_ARRIVAL_LOG_LIMIT 条）",
                )
            }
        }
        val next = nextDirectionalSeen(directionalNavigationSeen, keyCode, action)
        if (next == directionalNavigationSeen) return
        directionalNavigationSeen = next
        // 必须走 diag 而不是 i：release 下 `AppLog.i` **完全不产生**，
        // 「判据 5 到底有没有翻转」在用户设备上就再也查不到了（v2.39.6 的取证缺口）。
        AppLog.diag("Focus", "按键导航已确认[$source] code=$keyCode → 焦点视觉常开")
    }
}

/**
 * 状态推进纯函数（可单测）：当前标志 + 一个按键事件 ⇒ 新标志。
 *
 * - 只在 **ACTION_DOWN** 上翻转：`dispatchKeyEvent` 会同时收到 down / up，
 *   在 up 上翻转会白改一次 state，且在按键被系统吞掉时给出错误的「在用按键导航」结论。
 * - 一旦为 true 就永远为 true（粘住），理由见 [DpadInputTracker]。
 */
internal fun nextDirectionalSeen(current: Boolean, keyCode: Int, action: Int): Boolean {
    if (current) return true
    if (action != KeyEvent.ACTION_DOWN) return false
    return isDirectionalNavigationKey(keyCode)
}

/**
 * 方向按键能力的判定 —— 纯函数（可单测，不碰任何 Android 运行时）。
 *
 * ⛔ **排除「既声明 DPAD 又声明 TOUCHSCREEN」的设备**：
 * 那几乎必然是内建触摸数字化器顺带多报了 source 位（部分 ROM / 模拟器如此），
 * 而不是外接遥控器。真正的遥控器 / 键盘 / 手柄是**独立**的 input device，
 * 不会同时声明 `SOURCE_TOUCHSCREEN`。这条排除把手机侧的误判面压到最低，
 * 且**不牺牲**「电视盒子谎报 touchscreen 也要点亮焦点环」这条核心需求 ——
 * 那台盒子的遥控器同样是一个独立 device，照样命中。
 *
 * @param hasDpad 该设备是否支持 `InputDevice.SOURCE_DPAD`
 * @param isTouchDigitizer 该设备是否同时是触摸数字化器（`InputDevice.SOURCE_TOUCHSCREEN`）
 */
internal fun isRemoteNavigationDevice(hasDpad: Boolean, isTouchDigitizer: Boolean): Boolean =
    hasDpad && !isTouchDigitizer

/**
 * 「这台设备的形态就是靠按键导航」的判据 —— 纯函数（可单测，不碰 Android 运行时）。
 *
 * 读的是 [Configuration]（框架按**真实输入设备**聚合出来的全局配置），
 * 而不是 `PackageManager.hasSystemFeature`（厂商手写的 permissions.xml）——
 * 来源独立，所以能在「盒子谎报触摸屏 feature」时把判据 1 的落空补上。
 * 手机两个字段一致（`TOUCHSCREEN_FINGER` + `KEYBOARD_NOKEYS`）⇒ 恒 false。
 *
 * ⚠️ 「不是 `TOUCHSCREEN_FINGER`」是刻意的宽松写法，`UNDEFINED` 也算命中：真机由框架按真实
 * 输入设备聚合，恒为 NOTOUCH / STYLUS / FINGER 之一，`UNDEFINED` 只会来自合成 Configuration
 * 或畸形 ROM —— 把它判负等于在最需要这条判据的设备上把它关掉，而手机侧永远走不到该分支。
 *
 * ⚠️ `Configuration.keyboard` / `.touchscreen` 在 android-34 的 stub 里**没有** `@Deprecated`
 * （用 javap 核对过），引用它们是常量内联，API 22 上也不会缺符号。
 *
 * @param touchscreen [Configuration.touchscreen]
 * @param keyboard [Configuration.keyboard]
 */
internal fun isKeyNavigationConfiguration(touchscreen: Int, keyboard: Int): Boolean =
    touchscreen != Configuration.TOUCHSCREEN_FINGER ||
        (keyboard != Configuration.KEYBOARD_NOKEYS && keyboard != Configuration.KEYBOARD_UNDEFINED)

/** 焦点视觉判据的一次实测读数（诊断日志与 [shouldShowFocusVisuals] 共用同一份来源）。 */
internal data class FocusVisualInputs(
    val noTouchScreen: Boolean,
    val tvFeature: Boolean,
    val keyNavigationConfig: Boolean,
    val dpadInputDevicePresent: Boolean,
    val directionalNavigationSeen: Boolean,
) {
    val show: Boolean
        get() = noTouchScreen || tvFeature || keyNavigationConfig ||
            dpadInputDevicePresent || directionalNavigationSeen
}

/** 当前连接的所有 input device 里是否有支持 D-PAD 的（binder 调用，见 [dpadProbe] 的缓存说明）。 */
private fun probeDpadInputDevices(): DpadProbe {
    var present = false
    val described = mutableListOf<String>()
    runCatching {
        InputDevice.getDeviceIds().forEach { id ->
            val device = InputDevice.getDevice(id) ?: return@forEach
            // 判定规则与纯函数 [isRemoteNavigationDevice] 共用同一个来源，⛔ 不许在这里另写一份
            val remote = isRemoteNavigationDevice(
                hasDpad = device.supportsSource(InputDevice.SOURCE_DPAD),
                isTouchDigitizer = device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN),
            )
            if (remote) present = true
            if (described.size < DIAG_DEVICE_LIMIT) described += describeInputDevice(device)
        }
    }
    // 个别 ROM 在输入服务未就绪时会抛异常 ⇒ 保守地当作「无 D-PAD 设备」，由判据 5 兜底，
    // 绝不让整个 app 崩在组合期。
    return DpadProbe(present = present, devices = described.joinToString(" | "))
}

/** [probeDpadInputDevices] 的结果：命中位 + 一份人类可读的设备清单（诊断用）。 */
private data class DpadProbe(val present: Boolean, val devices: String)

/**
 * D-PAD 输入设备探测结果 —— **进程内只查一次**，顺带产出设备清单。
 *
 * ## 为什么 `by lazy` 而不是 `remember`
 *
 * `shouldShowFocusVisuals()` 被 135+ 个组件在组合期调用。若缓存写在该 `@Composable` 里
 * （`remember(packageManager) { … }`），每个调用点各有一份 ⇒ 首屏 135 次
 * `InputDevice.getDeviceIds()`，而它是一次 **binder 调用**（`InputManager`），
 * 会把首屏组合期拖出可见卡顿。放到 `object` 上则整个进程只付一次。
 *
 * ## 「设备是运行中才接入的」怎么办
 *
 * 蓝牙键盘 / 外接手柄后连时，这个值确实已经过期。**但不需要失效机制** ——
 * 用户要用外接键盘导航，就得先按方向键；那一刻 [DpadInputTracker] 会立刻把
 * `directionalNavigationSeen` 置 true 并触发重组（它是快照 state，读它就订阅了）。
 * 于是两条判据形成互补：**探测负责「进场就有」（遥控器）**，按键负责
 * **「后连的设备按一下就生效」**。加主动失效反而会引入一个风险 ——
 * 在用户已形成肌肉记忆后把焦点环抽走，那正是本次要修的故障本身。
 */
private val dpadProbe: DpadProbe by lazy { probeDpadInputDevices() }

/** 判据 4（存在 D-PAD 输入设备）—— 取自 [dpadProbe] 的进程级缓存。 */
private val dpadInputDevicePresent: Boolean
    get() = dpadProbe.present

/**
 * 是否应当显示焦点视觉 —— **全应用唯一判据**（焦点环 / 缩放 / 容器色 / 内容色都走它）。
 *
 * 五条判据任一成立即为真（详见文件头 ②）：
 * 1. 没有触摸屏 2. 电视 feature 任一 3. 按键导航形态的 `Configuration`
 * 4. **存在支持 `SOURCE_DPAD` 的输入设备** 5. 进程内收到过方向/确认键（快照 state 读，参与重组）
 *
 * 判据 1–3 缓存于 [remember]（PackageManager 在 API 22 上可能是 binder 调用），
 * 判据 4 缓存于 [dpadProbe]（全进程一次，见其 KDoc —— 组合期**绝不**直接查 InputDevice），
 * 判据 5 是快照 state 读 —— 它一变，所有读它的组件自动重组。
 */
@Composable
fun shouldShowFocusVisuals(): Boolean {
    val context = LocalContext.current
    val packageManager = remember(context) { context.packageManager }
    val probed = remember(packageManager, context.resources) { probeFocusVisualInputs(context) }
    return probed.noTouchScreen || probed.tvFeature || probed.keyNavigationConfig ||
        dpadInputDevicePresent || DpadInputTracker.directionalNavigationSeen
}

/**
 * 取三条**静态**判据的实测值（PackageManager 特征 + 框架输入配置）。
 *
 * ⚠️ 判据 4/5 不在这里算 —— 它们分别由 [dpadProbe]（进程级 lazy）和
 * [DpadInputTracker]（快照 state）负责，混进来的话组合期就会打 binder、
 * 或者丢掉对按键标志的订阅。门禁 `FocusIndicatorContractTest` 锁的就是这条边界。
 */
internal fun probeFocusVisualInputs(context: Context): FocusVisualInputs {
    val packageManager = context.packageManager
    val configuration = context.resources.configuration
    return FocusVisualInputs(
        noTouchScreen = !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        tvFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("android.hardware.type.television"),
        keyNavigationConfig = isKeyNavigationConfiguration(
            touchscreen = configuration.touchscreen,
            keyboard = configuration.keyboard,
        ),
        dpadInputDevicePresent = dpadProbe.present,
        directionalNavigationSeen = DpadInputTracker.directionalNavigationSeen,
    )
}

/** 诊断日志里最多列几台输入设备（再多就把 400 行窗口挤爆，见 [DpadInputTracker.KEY_ARRIVAL_LOG_LIMIT]）。 */
private const val DIAG_DEVICE_LIMIT = 8

/**
 * 单台设备的一行描述：`#id 名称 src=0x… [DPAD][TOUCH]`。
 *
 * sources 用十六进制 + 显式标记位，是因为回传的截图要靠人读 ——
 * 只给 `0x2303` 用户/我们都要再去查表，直接标出 DPAD/TOUCH 才能一眼定位。
 */
internal fun describeInputDevice(device: InputDevice?): String {
    if (device == null) return "dev=null"
    val sources = device.sources
    val flags = buildString {
        if (sources and InputDevice.SOURCE_DPAD != 0) append("[DPAD]")
        if (sources and InputDevice.SOURCE_TOUCHSCREEN != 0) append("[TOUCH]")
        if (sources and InputDevice.SOURCE_KEYBOARD != 0) append("[KEY]")
    }
    return "#${device.id} ${device.name} src=0x${sources.toString(16)}${if (flags.isEmpty()) "" else " $flags"}"
}

/**
 * 把焦点判据的**实测值**写进日志页的「调试」档（[com.nasmusic.tv.util.LogStore.LEVEL_DIAG]）。
 *
 * ## 为什么必须存在
 * 出问题的是用户的电视/盒子，开发机复现不了；那侧连不上 adb，`AppLog.d/i` 在 release
 * 又完全不产生 ⇒ 设备侧读数**只能**靠日志页由用户截图/复制回传。
 *
 * ## 为什么拆成三条而不是一条多行
 * 日志列表每条正文只渲染 3 行（`LogsSettingsSection` 的 `maxLines = 3`），
 * 所以把「结论」单独放一条，用户在列表里就能直接截图；设备清单较长，需要点开详情或复制。
 *
 * ⛔ 只在 `LaunchedEffect` / 事件回调里调，⛔ 不得在组合期调用（本函数会触发一次
 * [dpadProbe] binder 查询，虽然进程内只付一次，但组合期打 binder 是被门禁禁止的形态）。
 */
fun logFocusVisualDiagnostics(context: Context, reason: String) {
    val inputs = probeFocusVisualInputs(context)
    AppLog.diag(
        "Focus",
        "焦点判据[$reason] 显示=${inputs.show} 无触摸屏=${inputs.noTouchScreen} " +
            "TV特征=${inputs.tvFeature} 按键形态配置=${inputs.keyNavigationConfig} " +
            "DPAD设备=${inputs.dpadInputDevicePresent} 已按过方向键=${inputs.directionalNavigationSeen}",
    )
    AppLog.diag(
        "Focus",
        "设备[$reason] ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "ver=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
    )
    AppLog.diag("Focus", "输入设备[$reason] ${dpadProbe.devices.ifEmpty { "无" }}")
}

/**
 * 焦点环：外侧柔光带 + 内侧实心描边。
 *
 * - **必须挂在修饰符链的末尾**（在 `.background(...)` 之后），否则会被不透明容器色盖住 ——
 *   这正是本次修复的主因。
 * - 两层都用 [Modifier.border]，它自带「描边整条内缩在边界之内」的语义 ⇒
 *   被祖先/自身 `.clip(shape)` 裁剪后仍完整可见，也因此**绝不覆盖组件内的文字**。
 * - `visible = false` 时**原样返回**（不插入任何修饰符节点），与此前 `width = 0.dp` 等价。
 *
 * @param shape 组件形状，环沿其轮廓绘制
 * @param color 环颜色（默认主色青 `NasMusicColors.FocusRing`）
 * @param visible 是否处于焦点态
 * @param ringWidth 实心环宽度（压在柔光带内缘）
 * @param haloWidth 柔光带宽度（整圈的总厚度）
 */
fun Modifier.focusRing(
    shape: Shape,
    color: Color,
    visible: Boolean,
    ringWidth: Dp = FocusRingWidth,
    haloWidth: Dp = FocusHaloWidth,
): Modifier {
    if (!visible) return this
    // 两层都在**边界之内**绘制（Modifier.border 自带内缩语义）：
    //   ① 柔光带（更宽、更淡）—— 3 米外先看到这一圈青色光晕
    //   ② 实心环（更窄、更亮）—— 压住柔光带的内缘，形成清晰的勾边
    // 全部落在组件自身边缘，不覆盖任何文字；被祖先/自身 `.clip(shape)` 裁剪后仍完整可见。
    return this
        .border(width = haloWidth, color = color.copy(alpha = 0.26f), shape = shape)
        .border(width = ringWidth, color = color, shape = shape)
}