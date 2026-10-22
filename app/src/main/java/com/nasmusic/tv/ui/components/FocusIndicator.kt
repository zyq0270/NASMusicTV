package com.nasmusic.tv.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
 * ## ② 决策：用户说了算 + 看当下用什么输入，不猜设备
 *
 * v2.39.7 起（所有者裁决）显示焦点视觉只有两个条件：
 * 1. **设置页「焦点高亮」开关为开**（[FocusVisualPreference]，默认**开**）
 * 2. **此刻不是触摸导航**（[FocusInputTracker]：手指点一次屏收起，按一次方向键回来）
 *
 * ## 为什么把判断从设备交给用户
 * 原本靠五条自动判据猜「这台设备是不是电视」，但非认证电视盒子会把三路特征
 * **同时谎报**：`hasSystemFeature` 读的是厂商手写的 permissions.xml，
 * 猜错的代价是**整台设备看不见焦点**——这正是用户报的故障，而我们远程改不动。
 * 改成默认显示 + 可手动关后，猜错最坏只是多画一圈。
 *
 * 手机侧的粘滞焦点（`clickable` 点一下获得焦点且焦点不消失）也不再靠设备嗅探，
 * 改用**触摸事件**：触摸与按键在系统里分开上报，比任何设备特征都可靠。
 * ⚠️ 这里有个容易踩空的推演：若手机抑制仍用 `Configuration` 嗅探，
 * 「默认开」就等价于今天的自动判据 3，开关等于什么都没加。
 *
 * ## 五条自动判据去哪了：降级为诊断证据
 * 它们**不再参与决策**，仍由 [probeFocusVisualInputs] 实测并写进日志页「调试」档
 * （[logFocusVisualDiagnostics]）。留着是因为回传读数需要一条对照：
 * 「判据会显示=false 而实际显示=true」就直接证明那台盒子在谎报特征、开关救了它。
 * 原五条（任一成立即视为电视形态，仅供取证）：
 * 1. 没有触摸屏（`FEATURE_TOUCHSCREEN` 为 false；电视盒子 / 机顶盒 / 投影仪的常态）
 * 2. `FEATURE_LEANBACK` 或 `android.hardware.type.television`
 * 3. **`Configuration.touchscreen` 不是手指触摸**，或 `Configuration.keyboard` 报出了实体键盘
 *    （v2.39.6 新增，见 [isKeyNavigationConfiguration]；它读的是框架按**真实输入设备**
 *    聚合出的全局配置，与判据 1 的 permissions.xml **来源独立**，
 *    所以能补上「盒子声明 `touchscreen required=false` 但实际无触摸数字化器」这一类落空）
 * 4. **存在支持 `SOURCE_DPAD` 的输入设备**（能力探测，遥控器/外接键盘/手柄）
 * 5. 进程内收到过任一方向/确认键（[DpadInputTracker]）
 *
 * ## 读数必须可回传（[logFocusVisualDiagnostics]）
 * 出问题的是**用户的**电视/盒子，不是开发机 —— 那边连不上 adb，`AppLog.d/i` 在 release
 * 又完全不产生。所以开关状态、触摸抑制、实际显示 + 五条判据实测值 + 全部输入设备清单
 * 会写进日志页的「调试」档（[com.nasmusic.tv.util.LogStore.LEVEL_DIAG]），
 * 由用户截图/复制回传。⛔ 这条通道不能被 `BuildConfig.DEBUG` 门控，否则等于没有。
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
 * 用户侧「焦点高亮」开关 —— 进程级快照，**默认开**（所有者 2026-10-22 裁决）。
 *
 * ## 为什么由用户说了算，而不是继续加判据
 * 焦点视觉原先**完全靠猜设备**（`hasSystemFeature` / `Configuration` / `InputDevice` 三路）。
 * 非认证电视盒子会把这三处**同时谎报**，猜错的代价是「整台设备看不见焦点」——
 * 而这正是用户报的故障，且我们远程改不动。改成**默认显示 + 可手动关**之后，
 * 猜错最坏只是「多画一圈」，手机侧的粘滞焦点交给 [FocusInputTracker] 的触摸抑制，
 * 不再依赖猜设备。
 *
 * ⛔ 默认值必须是 `true`：出问题的用户装上新包就得先能看见焦点，
 * 否则他连「焦点在哪」都说不清，「调试」档的读数也无从对照（门禁
 * `焦点高亮开关必须默认开` 锁死这一点）。
 *
 * ⚠️ 组合期**只读这个快照**，不在这里读 DataStore —— 本函数被 135+ 个组件调用。
 */
object FocusVisualPreference {
    var highlightEnabled by mutableStateOf(true)
        private set

    /** 由 `NasMusicApp.onCreate` 起的常驻收集写入（DataStore → 快照，唯一写入方）。 */
    fun update(enabled: Boolean) {
        if (enabled == highlightEnabled) return
        highlightEnabled = enabled
        AppLog.diag("Focus", "焦点高亮开关=$enabled（设置页改动或启动读偏好）")
    }
}

/**
 * 触摸抑制 —— 手指一操作就收起焦点视觉，直到再次出现方向键。
 *
 * ## 它替掉的是什么
 * 「这是不是电视」的设备嗅探。触摸与按键在系统里是**分开上报**的事件，
 * 比任何设备特征都可靠：真机手机报得出 `FINGER` 也可能报得出 DPAD 设备（外接键盘），
 * 但它**第一次点屏**就说明此刻是触摸导航 ⇒ 该收起焦点环。
 *
 * ## 为什么初始值是不抑制
 * 电视/盒子用户从头到尾不会发出触摸事件，于是进场第一帧起焦点就可见
 * （这正是「切到页面立刻知道焦点在哪」那条需求）。手机侧的粘滞焦点在第一次点屏
 * 之后即被抑制，与改动前的实际观感一致。
 *
 * ⛔ 置位/清除都必须只记「此刻的输入方式」，不做计时回收 —— 超时收起会让
 * 「混合输入」设备（触屏电视）反复闪框，比常亮更难读。
 */
object FocusInputTracker {
    /** 组合可读 —— 被 [shouldShowFocusVisuals] 在组合期读取，变化即触发重组。 */
    var touchNavigating by mutableStateOf(false)
        private set

    private var touchLogged = false

    /** 由 `MainActivity.dispatchTouchEvent` 接线（窗口层，触摸事件一定经过这里）。 */
    fun noteTouchEvent(action: Int) {
        if (action != MotionEvent.ACTION_DOWN) return
        // 已经处于触摸导航时不再写 state：滚动时每帧都有 down，白触发 135+ 重组
        if (touchNavigating) return
        touchNavigating = true
        if (!touchLogged) {
            touchLogged = true
            AppLog.diag("Focus", "检测到触摸导航 → 焦点视觉抑制（按方向键可恢复）")
        }
    }

    /** 方向键把输入方式交还给按键导航（[DpadInputTracker.noteKeyEvent] 内部调用）。 */
    internal fun noteDirectionalKey() {
        if (!touchNavigating) return
        touchNavigating = false
        AppLog.diag("Focus", "方向键接管输入 → 焦点视觉恢复显示")
    }
}

/**
 * 方向键使用痕迹（进程级，粘住）。
 *
 * ⛔ 只记「有没有用过方向键」，**不记时间也不回收** —— 焦点视觉本身是无害的
 * （只是把已有的焦点画出来），而回收逻辑一旦出错就会在用户已经形成肌肉记忆后
 * 把指示器抽走，那正是本次要修的故障本身。
 */
object DpadInputTracker {
    /**
     * 组合可读 —— v2.39.7 起**不参与决策**，由 `MainActivity` 的 `LaunchedEffect` 键控，
     * 收到第一个方向键后再打一次诊断读数（进场时那台盒子还没被证实是按键导航）。
     */
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
            // 方向键 ⇒ 输入方式交还给按键，解除触摸抑制（焦点视觉恢复显示）
            FocusInputTracker.noteDirectionalKey()
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
        AppLog.diag("Focus", "按键导航已确认[$source] code=$keyCode → 自动判据 5 命中")
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
 * 不会同时声明 `SOURCE_TOUCHSCREEN`。这条排除让「触摸抑制」（[FocusInputTracker]）之外的
 * 判据 4 读数保持干净 —— 手机外接键盘时它不会误报成遥控器。
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
 * D-PAD 输入设备探测结果 —— **进程内只查一次**，顺带产出设备清单（诊断用）。
 *
 * ## 为什么 `by lazy` 而不是每次实测
 *
 * `InputDevice.getDeviceIds()` 是一次 **binder 调用**（`InputManager`）。[logFocusVisualDiagnostics]
 * 会在启动、首次方向键、以及用户进日志页时被反复调用，每次都真打 binder 既没必要也可能卡顿；
 * 放到 `object` 上则整个进程只付一次。
 * ⛔ 更重要的边界：`shouldShowFocusVisuals()` 被 135+ 个组件在组合期调用，
 * 它**绝不**能碰到这份缓存 —— 门禁 `FocusIndicatorContractTest` 锁的就是这条线。
 *
 * ## 「设备是运行中才接入的」怎么办
 *
 * 蓝牙键盘 / 外接手柄后连时，这个值确实已经过期。**但不需要失效机制** ——
 * v2.39.7 起它只是诊断读数，焦点视觉本身由 [FocusVisualPreference]（用户开关）与
 * [FocusInputTracker]（触摸抑制）决定，不依赖这份缓存。读数会滞后，但显示不会出错。
 */
private val dpadProbe: DpadProbe by lazy { probeDpadInputDevices() }

/**
 * 是否应当显示焦点视觉 —— **全应用唯一判据**（焦点环 / 缩放 / 容器色 / 内容色都走它）。
 *
 * ## 规则（v2.39.7 起，所有者裁决）
 * 显示 = **用户开关开着** 且 **此刻不是触摸导航**
 *
 * - 开关（[FocusVisualPreference]，默认**开**）：把「这台设备是不是电视」的判断交还给用户，
 *   因为盒子可以谎报任何设备特征，而猜错的代价是整台设备看不见焦点。
 * - 触摸抑制（[FocusInputTracker]）：手机侧的粘滞焦点不再依赖设备嗅探 ——
 *   手指点一次屏就收起，按一次方向键又回来。
 *
 * ⛔ 这里**不再**读 `hasSystemFeature` / `Configuration` / `InputDevice`：那五条自动判据
 * 降级为 [probeFocusVisualInputs] 的**诊断读数**，仍写进日志页「调试」档 ——
 * 它们现在是证据，不再是决策，这正好避免了「判据落空 ⇒ 用户什么都看不见 ⇒ 也没法回传」。
 *
 * ⚠️ 两个值都是快照 state 读：读即订阅，所以切换开关、第一次点屏、第一次按方向键
 * 都会让全部 135+ 个调用点重组，不需要额外的刷新机制。
 */
@Composable
fun shouldShowFocusVisuals(): Boolean {
    if (!FocusVisualPreference.highlightEnabled) return false
    return !FocusInputTracker.touchNavigating
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
    val switch = FocusVisualPreference.highlightEnabled
    val touch = FocusInputTracker.touchNavigating
    AppLog.diag(
        "Focus",
        "焦点[$reason] 显示=${switch && !touch} 高亮开关=$switch 触摸抑制=$touch",
    )
    AppLog.diag(
        "Focus",
        "自动判据[$reason]（仅作证据，不参与决策） 判据会显示=${inputs.show} " +
            "无触摸屏=${inputs.noTouchScreen} TV特征=${inputs.tvFeature} " +
            "按键形态配置=${inputs.keyNavigationConfig} DPAD设备=${inputs.dpadInputDevicePresent} " +
            "已按过方向键=${inputs.directionalNavigationSeen}",
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