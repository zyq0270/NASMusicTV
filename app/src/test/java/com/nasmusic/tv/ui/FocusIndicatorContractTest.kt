package com.nasmusic.tv.ui

import android.content.res.Configuration
import android.view.InputDevice
import android.view.KeyEvent
import com.nasmusic.tv.ui.components.isDirectionalNavigationKey
import com.nasmusic.tv.ui.components.isKeyNavigationConfiguration
import com.nasmusic.tv.ui.components.isRemoteNavigationDevice
import com.nasmusic.tv.ui.components.nextDirectionalSeen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 门禁：焦点指示器的**绘制顺序**与**设备判据** —— 真机反馈「遥控器导航完全看不到焦点在哪」。
 *
 * ## 根因（各自独立，每一道都必须防住）
 *
 * ① **绘制顺序**：Compose 的绘制顺序 = 修饰符链顺序，越靠前越靠下。
 *    `FocusableSurface` 此前是 `border(...)` → `background(...)`，不透明容器色把焦点环整个盖住。
 *    焦点环只在容器色为 `Color.Transparent` 的少数组件上侥幸可见，而所有卡片/列表行用的都是
 *    不透明 `NasMusicColors.Surface` ⇒ 全应用看不见焦点框。这条**编译过、单测过**，
 *    只有肉眼能发现，必须门禁。
 *
 * ② **判据能力不足**：仅靠 `PackageManager.hasSystemFeature(leanback / type.television)` 的
 *    **静态特征嗅探**不够 —— 非 Google 认证的电视盒子上常常双双 false；唯一的兜底
 *    「按过一次方向键」又必须先有一次按键才翻转，正好是用户拒绝的行为
 *    （「切到页面要立刻知道焦点在哪，而不是先按一下才看到」）。
 *    ⇒ 必须存在**基于输入能力**的判据：`InputDevice` 里有没有声明 `SOURCE_DPAD` 的设备。
 *    遥控器本身就是注册在系统里的 input device，盒子瞒不掉它的能力。
 *
 * ③ **判据来源不能只有一路**（v2.39.6，用户盒子复现、开发机正常）：`hasSystemFeature` 读的是
 *    厂商手写的 permissions.xml，`Configuration.touchscreen/keyboard` 读的是框架按**真实输入
 *    设备**聚合出来的全局配置 —— 两者独立。大量盒子为装手机应用把 touchscreen 声明成
 *    `required="false"`，于是「无触摸屏」这条直接落空，只剩触摸来源能认出它。
 *
 * ④ **按键兜底有自举循环**（同一轮）：判据 5 只挂在 Compose 根节点的 `onPreviewKeyEvent` 上，
 *    而那是「按键已经进入 Compose 焦点树」之后才有机会执行的地方 —— 老盒子冷启动没有焦点时，
 *    「要有焦点环才认得方向键，要认得方向键才有焦点环」。⇒ 必须同时存在**窗口层**兜底
 *    （`MainActivity.onKeyDown`，source="window"）。
 *
 * ⑤ **只要还在猜设备，就总有一台设备猜错**（v2.39.7，所有者裁决）：上面 ②③④ 把判据从 2 条
 *    加到 5 条，用户的盒子**仍然**看不见焦点 —— 因为三路来源（permissions.xml /
 *    `Configuration` / `InputDevice`）都可被厂商改写，而猜错的代价是整台设备看不见焦点，
 *    这个代价我们远程承担不了。⇒ 决策权交给**用户开关**（默认开）+ **触摸抑制**
 *    （手指点屏收起、方向键恢复），五条判据降级为日志页「调试」档的**取证读数**。
 *    ⚠️ 手机侧的抑制同样⛔不许回到设备嗅探：若抑制仍读 `Configuration`，「默认开」就等价于
 *    旧判据 3，这个开关等于什么都没加 —— 所以必须有 `dispatchTouchEvent` 这条真实触摸来源。
 *
 * ## 判定规则
 *
 * 1. `FocusableSurface.kt` / `UnifiedSongRow.kt` 内，`.focusRing(` 必须出现在 `.background(` **之后**
 *    —— 否则会被容器色盖住。
 * 2. 焦点视觉的判据必须是 `shouldShowFocusVisuals()`，不得再直接用 `isTVDevice()`。
 * 3. **决策只有两个输入**：`shouldShowFocusVisuals()` 的函数体必须读
 *    `FocusVisualPreference.highlightEnabled`（开关）与 `FocusInputTracker.touchNavigating`（触摸抑制），
 *    且**不得**出现任何设备嗅探标记（`hasSystemFeature` / `dpadProbe` / `directionalNavigationSeen` …）。
 * 4. **开关默认值必须是 `true`**，且四处默认值（快照 / DataStore / 设置页初值 / 分区 state）
 *    必须一致 —— 出问题的用户装上新包就得先能看见焦点。
 * 5. 触摸抑制必须①由**窗口层** `dispatchTouchEvent` 置位，②由**方向键**解除，
 *    且初始值必须是「不抑制」（电视进场第一帧就可见）。
 * 6. 五条自动判据必须**仍在**（作为诊断读数），且必须被 `logFocusVisualDiagnostics` 打进
 *    `AppLog.diag`；开关状态与触摸抑制也必须在同一条读数里 —— 否则回传的日志无法判断
 *    「判据落空」还是「用户自己关了」。
 * 7. 按键兜底必须**两个来源都在**（compose + window），少一个就退回上面 ④ 的自举循环。
 * 8. 判据翻转与按键到达记录必须走 `AppLog.diag`（release 也落盘），⛔ 不许退回 `AppLog.i` ——
 *    出问题的设备在用户那边，连不上 adb，日志页「调试」档是唯一取证通道。
 * 9. 开关必须是**设备本地偏好**：走 `prefs.display`，⛔ 不得进 `AppSettings`（备份 json
 *    会把电视的可见性策略恢复到手机上，反之亦然 —— 与屏幕方向同类）。
 *
 * ⚠️ 与 [FocusableSurfaceColorContractTest] 同款：这是**启发式**护栏（只看出现次序），
 * 目的是「别删 / 别挪」。护栏本身的有效性由末尾的负向用例守住。
 *
 * 约定见 `docs/conventions-adaptive-ui.md` §11。
 */
class FocusIndicatorContractTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `focusRing 必须画在 background 之后，否则会被不透明容器色盖住`() {
        val offenders = FOCUS_RING_SITES
            .map { it to uiSourceRoot().resolve(it) }
            .filter { (_, file) -> focusRingBeforeBackground(file.readText()) }
            .map { it.first }

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("焦点环被画在不透明背景**之下**（看不到焦点）：$offenders")
                    appendLine()
                    appendLine("Compose 的绘制顺序 = 修饰符链顺序，越靠前越靠下；")
                    appendLine("`.background(...)` 会把排在其前面的 `.focusRing(...)` 整个盖住。")
                    appendLine()
                    appendLine("修法：把 `.focusRing(...)` 移到链尾（在 `.background(...)` 之后）。")
                    appendLine("详见 docs/conventions-adaptive-ui.md §11。")
                }
            )
        }
    }

    @Test
    fun `焦点视觉的判据必须是 shouldShowFocusVisuals 而不是 isTVDevice`() {
        val file = uiSourceRoot().resolve("components/FocusableSurface.kt")
        assertTrue("找不到 FocusableSurface.kt：${file.absolutePath}", file.isFile)
        val text = file.readText()
        assertTrue(
            "FocusableSurface 未使用 shouldShowFocusVisuals() 作为焦点视觉判据",
            text.contains("shouldShowFocusVisuals()"),
        )
        assertTrue(
            "focusRing 必须挂在链尾（visible 由 showFocusBorder 与 activeFocus 相与决定）",
            text.contains("visible = showFocusBorder && activeFocus"),
        )
    }

    @Test
    fun `UnifiedSongRow 的行焦点判据已切换到 shouldShowFocusVisuals`() {
        val file = uiSourceRoot().resolve("components/song/UnifiedSongRow.kt")
        assertTrue("找不到 UnifiedSongRow.kt：${file.absolutePath}", file.isFile)
        val text = file.readText()
        assertTrue("UnifiedSongRow 未使用 shouldShowFocusVisuals()", text.contains("shouldShowFocusVisuals()"))
        assertTrue(
            "UnifiedSongRow 不应再 import isTVDevice（焦点视觉改走共享判据）",
            !text.contains("import com.nasmusic.tv.ui.components.isTVDevice"),
        )
    }

    @Test
    fun `FocusIndicator 必须提供共享的焦点环与判据`() {
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        for (required in listOf("fun Modifier.focusRing(", "fun shouldShowFocusVisuals()", "object DpadInputTracker")) {
            assertTrue("FocusIndicator.kt 缺少 $required", text.contains(required))
        }
    }

    // ─────────────── 自动判据：已从决策降级为诊断读数（必须仍在） ───────────────

    @Test
    fun `判据源码必须包含 SOURCE_DPAD 输入设备探测`() {
        // 删掉它虽然不再影响显示（决策已交给开关），但回传的日志就再也看不出那台盒子
        // 到底有没有注册遥控器 —— 而这正是下一轮修复的唯一线索。
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        for (required in listOf("InputDevice.SOURCE_DPAD", "InputDevice.getDeviceIds()", "supportsSource")) {
            assertTrue("FocusIndicator.kt 缺少 D-PAD 能力探测的 $required", text.contains(required))
        }
    }

    @Test
    fun `五条自动判据必须全部进入诊断读数`() {
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        val body = functionBody(text, "internal fun probeFocusVisualInputs(")
        val required = mapOf(
            "无触摸屏（FEATURE_TOUCHSCREEN）" to "FEATURE_TOUCHSCREEN",
            "电视特征 FEATURE_LEANBACK" to "FEATURE_LEANBACK",
            "电视特征 type.television" to "android.hardware.type.television",
            "按键形态的 Configuration" to "isKeyNavigationConfiguration",
            "D-PAD 输入设备能力" to "dpadProbe.present",
            "按键痕迹 directionalNavigationSeen" to "DpadInputTracker.directionalNavigationSeen",
        )
        val missing = required.filterValues { !body.contains(it) }.keys
        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("焦点自动判据的实测读数被删减：$missing")
                    appendLine()
                    appendLine("v2.39.7 起这五条不再决定显示，但它们是日志页「调试」档的证据：")
                    appendLine("用户回传的截图里若没有这些读数，我们无法区分")
                    appendLine("「盒子谎报特征」/「用户自己关了开关」/「触摸抑制没解除」三种故障。")
                    appendLine()
                    appendLine("详见 docs/conventions-adaptive-ui.md §11。")
                }
            )
        }
    }

    @Test
    fun `决策必须由开关与触摸抑制驱动，不得再猜设备`() {
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        val body = functionBody(text, "fun shouldShowFocusVisuals()")
        val missing = DECISION_MARKERS.filter { !body.contains(it) }
        val sniffing = deviceSniffHits(body)
        if (missing.isNotEmpty() || sniffing.isNotEmpty()) {
            fail(
                buildString {
                    if (missing.isNotEmpty()) appendLine("shouldShowFocusVisuals 缺少的决策输入：$missing")
                    if (sniffing.isNotEmpty()) appendLine("shouldShowFocusVisuals 又回到了设备嗅探：$sniffing")
                    appendLine()
                    appendLine("显示 = 用户开关开着 且 此刻不是触摸导航。猜设备的代价是整台设备看不见")
                    appendLine("焦点（用户的盒子正是这么坏的），而开关猜错最坏只是多画一圈。")
                }
            )
        }
    }

    @Test
    fun `开关默认值必须四处一致且为 true`() {
        // 出问题的用户装上新包就得先能看见焦点，否则他连「焦点在哪」都说不清，
        // 「调试」档的读数也无从对照。任意一处默认成 false 都会在第一帧把焦点藏起来。
        val checks = listOf(
            "进程级快照" to Pair("ui/components/FocusIndicator.kt", "var highlightEnabled by mutableStateOf(true)"),
            "DataStore 默认" to Pair("data/prefs/AppPreferences.kt", "it[keyFocusHighlightEnabled] ?: true"),
            "设置页初值" to Pair("ui/components/branches/SettingsBranch.kt", "focusHighlightEnabled.collectAsState(initial = true)"),
            "分区 state 默认" to Pair("ui/screens/settings/GeneralSettingsSection.kt", "val focusHighlightEnabled: Boolean = true"),
        )
        val unreadable = checks.filter { projectSource(it.second.first).isFile.not() }.map { it.first }
        assertTrue("默认值用例读不到源文件（路径被挪动，护栏会空转）：$unreadable", unreadable.isEmpty())
        val missing = checks.filter { !projectSource(it.second.first).readText().contains(it.second.second) }.map { it.first }
        assertTrue("焦点高亮开关的默认值不是 true（或写法被改）：$missing", missing.isEmpty())
    }

    @Test
    fun `触摸抑制必须由窗口层置位并由方向键解除`() {
        val indicator = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        val tracker = functionBody(indicator, "object FocusInputTracker")
        assertTrue("触摸事件没置位 touchNavigating（手机粘滞焦点会重现）", tracker.contains("touchNavigating = true"))
        assertTrue("抑制态只应由方向键解除", tracker.contains("touchNavigating = false"))
        assertTrue(
            "初始值必须是「不抑制」：电视/盒子从头到尾不发触摸事件，进场第一帧就要看到焦点",
            tracker.contains("var touchNavigating by mutableStateOf(false)"),
        )

        // 置位来源必须是**窗口层触摸事件**，不是设备特征 —— 见类头 ⑤ 的推演
        val activity = uiSourceRoot().resolve("MainActivity.kt").readText()
        val dispatchBody = functionBody(activity, "override fun dispatchTouchEvent(ev: MotionEvent)")
        assertTrue("MainActivity 没覆写 dispatchTouchEvent ⇒ 触摸抑制没有数据来源", dispatchBody.contains("noteTouchEvent("))

        // 方向键必须把输入方式交还给按键导航（触屏电视 / 手机外接遥控器的混合输入形态）
        val keyBody = functionBody(indicator, "object DpadInputTracker")
        assertTrue("方向键未解除触摸抑制", keyBody.contains("FocusInputTracker.noteDirectionalKey()"))
    }

    @Test
    fun `开关必须走设置页写偏好并由常驻收集刷进快照`() {
        // 三段链路缺一段就表现为「拨了没反应」或「重启又变回去」
        val section = uiSourceRoot().resolve("screens/settings/GeneralSettingsSection.kt").readText()
        assertTrue("设置页没有焦点高亮开关", section.contains("R.string.settings_focus_highlight"))
        assertTrue(
            "开关没接 onChangeFocusHighlight（SettingSwitch 的 onClick 必须回调到 VM）",
            section.contains("actions.onChangeFocusHighlight"),
        )
        val viewModel = projectSource("ui/viewmodel/MainViewModel.kt").readText()
        assertTrue(
            "MainViewModel 缺 updateFocusHighlightEnabled",
            viewModel.contains("fun updateFocusHighlightEnabled("),
        )
        // ⛔ 不得进 AppSettings（备份 json 会把电视的可见性策略带到手机上，反之亦然）
        assertTrue(
            "焦点高亮必须走 prefs.display（设备本地偏好），不得写进 AppSettings 备份",
            viewModel.contains("prefs.display.setFocusHighlightEnabled"),
        )
        val appSettings = projectSource("data/model/AppSettings.kt").readText()
        assertTrue(
            "焦点高亮被放进了 AppSettings —— 它会随备份跨设备恢复，把电视的策略带到手机",
            !appSettings.contains("focusHighlight"),
        )
        val app = projectSource("NasMusicApp.kt").readText()
        assertTrue(
            "NasMusicApp 没起常驻收集 ⇒ 开关只在重启后生效（组合期读的是进程级快照）",
            app.contains("FocusVisualPreference.update"),
        )
    }

    @Test
    fun `焦点高亮文案必须中英齐备`() {
        for (locale in listOf("values", "values-en")) {
            val text = resFile(locale).readText()
            for (key in listOf("settings_focus_highlight", "settings_focus_highlight_desc")) {
                assertTrue(
                    "$locale/strings.xml 缺 $key —— 缺一侧是运行期 getString 抛异常，不是编译错误",
                    text.contains("name=\"$key\""),
                )
            }
        }
    }

    /** 负向自证：把决策改回「猜设备」的写法必须被上一条咬住。 */
    @Test
    fun `负向自证 决策回到设备嗅探必须判负`() {
        val sample = """
            @Composable
            fun shouldShowFocusVisuals(): Boolean {
                val inputs = LocalContext.current.let { probeFocusVisualInputs(it ) }
                return inputs.dpadInputDevicePresent || inputs.tvFeature
            }
        """.trimIndent()
        val body = functionBody(sample, "fun shouldShowFocusVisuals()")
        assertTrue("护栏空转：嗅探写法没被检出", deviceSniffHits(body).isNotEmpty())
        assertTrue("护栏空转：嗅探写法没被判为缺决策输入", DECISION_MARKERS.any { !body.contains(it) })
        // 反向：正确写法不得误伤
        val good = """
            @Composable
            fun shouldShowFocusVisuals(): Boolean {
                if (!FocusVisualPreference.highlightEnabled) return false
                return !FocusInputTracker.touchNavigating
            }
        """.trimIndent()
        val goodBody = functionBody(good, "fun shouldShowFocusVisuals()")
        assertTrue("护栏误伤：正确的开关+抑制写法被判负", deviceSniffHits(goodBody).isEmpty() && DECISION_MARKERS.all { goodBody.contains(it) })
    }

    /** 负向自证：默认关的开关必须被「默认值」用例的谓词咬住。 */
    @Test
    fun `负向自证 开关默认关必须判负`() {
        val sample = "object FocusVisualPreference { var highlightEnabled by mutableStateOf(false) }"
        assertTrue("护栏空转：mutableStateOf(false) 没被检出", !sample.contains("var highlightEnabled by mutableStateOf(true)"))
    }

    /** 负向自证：没有触摸来源（只有设备嗅探）的抑制写法必须判负。 */
    @Test
    fun `负向自证 缺窗口层触摸来源必须判负`() {
        val sample = """
            override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
                return super.onKeyDown(keyCode, event)
            }
        """.trimIndent()
        val body = functionBody(sample, "override fun onKeyDown(keyCode: Int, event: KeyEvent?)")
        assertTrue("护栏空转：样例里没有 dispatchTouchEvent/noteTouchEvent 却被当成已接线", !body.contains("noteTouchEvent("))
    }

    // ─────────────── 兜底通道：窗口层 + 可回传的调试日志 ───────────────

    @Test
    fun `按键兜底必须同时挂在 Compose 层与窗口层`() {
        // 只挂 onPreviewKeyEvent ⇒ 自举循环（按键要先有焦点才进得来）。
        // 只挂 onKeyDown ⇒ 按键被 Compose 消费后根本不会走到，release 无痕。
        val activity = uiSourceRoot().resolve("MainActivity.kt").readText()
        assertTrue("根节点 onPreviewKeyEvent 没接 noteKeyEvent", activity.contains("noteKeyEvent("))
        assertTrue("缺 source=\"compose\" 记录点", activity.contains("source = \"compose\""))
        assertTrue("缺 source=\"window\" 记录点（窗口层兜底）", activity.contains("source = \"window\""))

        val onKeyDownBody = functionBody(activity, "override fun onKeyDown(keyCode: Int, event: KeyEvent?)")
        assertTrue(
            "onKeyDown 里没有 noteKeyEvent —— 按键没进 Compose 焦点树时判据 5 永远不会翻转",
            onKeyDownBody.contains("noteKeyEvent("),
        )
    }

    @Test
    fun `焦点判据的翻转与到达必须写进 release 可见的调试档`() {
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        // 取整个 object（`fun noteKeyEvent(` 的签名里带 `device: InputDevice? = null`，
        // functionBody 会把它误判成表达式体只取一行 —— 按 object 取整块才可靠）
        val trackerBody = functionBody(text, "object DpadInputTracker")
        assertTrue("DpadInputTracker 不写 AppLog.diag ⇒ release 无痕，用户侧无从取证", trackerBody.contains("AppLog.diag("))
        assertTrue(
            "仍在用 AppLog.i（release 完全不产生）：这就是「用户盒子看不见焦点环却什么日志都没有」的形态",
            !trackerBody.contains("AppLog.i("),
        )
        // 启动时的一次快照读数：不按键也能看到五条判据的实测值
        val activity = uiSourceRoot().resolve("MainActivity.kt").readText()
        assertTrue("MainActivity 没调用 logFocusVisualDiagnostics —— 焦点判据没有可回传的读数", activity.contains("logFocusVisualDiagnostics("))

        // 读数必须**同时**含开关与触摸抑制：只有判据值的话，回传的截图无法区分
        // 「盒子谎报特征 ⇒ 开关救了它」和「用户自己把开关关了 ⇒ 该提醒他打开」。
        val diagBody = functionBody(text, "fun logFocusVisualDiagnostics(")
        assertTrue("诊断读数缺开关值", diagBody.contains("FocusVisualPreference.highlightEnabled"))
        assertTrue("诊断读数缺触摸抑制值", diagBody.contains("FocusInputTracker.touchNavigating"))
        assertTrue("诊断读数缺自动判据的对照值", diagBody.contains("inputs.show"))
    }

    /** 负向自证：只挂 Compose 层的写法必须判负。 */
    @Test
    fun `负向自证 只挂 Compose 层的兜底必须判负`() {
        val sample = """
            Modifier.onPreviewKeyEvent { event ->
                DpadInputTracker.noteKeyEvent(event.nativeKeyEvent.keyCode, event.nativeKeyEvent.action)
                false
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：只有 compose 来源的样例被误判为已有窗口层兜底",
            !sample.contains("source = \"window\""),
        )
    }

    /** 负向自证：翻转只写 AppLog.i 的写法必须判负（与上一条同口径，取 object 整块）。 */
    @Test
    fun `负向自证 用 AppLog 记录 release 无痕的翻转必须判负`() {
        val sample = """
            object DpadInputTracker {
                var directionalNavigationSeen by mutableStateOf(false)
                    private set

                fun noteKeyEvent(keyCode: Int, action: Int, device: InputDevice? = null) {
                    directionalNavigationSeen = true
                    AppLog.i("FocusIndicator", "directional key detected")
                }
            }
        """.trimIndent()
        val body = functionBody(sample, "object DpadInputTracker")
        assertTrue("没取到 object 块体，负向用例在空转", body.contains("AppLog.i("))
        assertTrue(
            "护栏空转：AppLog.i 在 release 不产生，却被误判为已留痕",
            !(body.contains("AppLog.diag(") && !body.contains("AppLog.i(")),
        )
    }

    // ─────────────────── Configuration 判据的纯逻辑（误判面） ───────────────────

    @Test
    fun `裸机手机的输入配置一律不得命中按键形态判据`() {
        // 手机：手指触摸 + 无实体键盘（软键盘不进 Configuration.keyboard）
        assertFalse(
            "FINGER + NOKEYS 是手机的标准读数",
            isKeyNavigationConfiguration(
                touchscreen = Configuration.TOUCHSCREEN_FINGER,
                keyboard = Configuration.KEYBOARD_NOKEYS,
            ),
        )
        assertFalse(
            "手指触摸 + 未定义键盘（软键盘从不进 Configuration.keyboard）是手机的另一种读数",
            isKeyNavigationConfiguration(
                touchscreen = Configuration.TOUCHSCREEN_FINGER,
                keyboard = Configuration.KEYBOARD_UNDEFINED,
            ),
        )
    }

    @Test
    fun `没有手指触摸的设备必须命中按键形态判据`() {
        // 电视盒子的常态：框架按真实输入设备聚合出 NOTOUCH，
        // 哪怕厂商在 permissions.xml 里把 touchscreen 声明成 required=false
        assertTrue(
            "NOTOUCH 必须命中",
            isKeyNavigationConfiguration(
                touchscreen = Configuration.TOUCHSCREEN_NOTOUCH,
                keyboard = Configuration.KEYBOARD_NOKEYS,
            ),
        )
        assertTrue(
            "STYLUS（触控笔电视 / 手写板）同样不是手指触摸导航",
            isKeyNavigationConfiguration(
                touchscreen = Configuration.TOUCHSCREEN_STYLUS,
                keyboard = Configuration.KEYBOARD_NOKEYS,
            ),
        )
        assertTrue(
            "实体键盘（QWERTY，蓝牙遥控常被识别成键盘）必须命中",
            isKeyNavigationConfiguration(
                touchscreen = Configuration.TOUCHSCREEN_FINGER,
                keyboard = Configuration.KEYBOARD_QWERTY,
            ),
        )
        // `TOUCHSCREEN_UNDEFINED` 只能是合成 Configuration 或畸形 ROM 的读数 —— 真机（含手机）
        // 由框架按真实输入设备聚合，恒为 NOTOUCH / STYLUS / FINGER 之一。
        // 「不知道是不是手指触摸」不是「有手指触摸」的证据：把它判负等于在最需要这条判据的
        // 设备上把它关掉，而手机侧永远不会走到这个分支，误判面为空。
        assertTrue(
            "未定义同样不是手指触摸的证据，必须命中",
            isKeyNavigationConfiguration(
                touchscreen = Configuration.TOUCHSCREEN_UNDEFINED,
                keyboard = Configuration.KEYBOARD_UNDEFINED,
            ),
        )
    }

    @Test
    fun `InputDevice 查询必须被缓存且只存在于探测函数里`() {
        // shouldShowFocusVisuals 被 135+ 处组合期调用，而 getDeviceIds() 是 binder 调用。
        // 缓存必须是全进程一次（`by lazy` 委托给探测函数），不是每个调用点各自的 remember。
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        assertTrue("未找到 InputDevice.getDeviceIds() 调用", text.contains("InputDevice.getDeviceIds()"))

        // ① 缓存必须存在，且其初始化体调用探测函数
        assertTrue(
            "InputDevice 探测未缓存（应有 `by lazy { probeDpadInputDevices() }`，" +
                "否则 135+ 个组合期调用点各做一次 binder 调用）",
            Regex("""by\s+lazy\s*\{[^}]*probeDpadInputDevices\(\)""").containsMatchIn(text),
        )

        // ② binder 查询只允许出现在 probeDpadInputDevices 函数体内，不得泄漏进组合函数
        val composableBody = functionBody(text, "fun shouldShowFocusVisuals()")
        assertTrue(
            "InputDevice.getDeviceIds() 出现在 shouldShowFocusVisuals 里 ⇒ 每次重组都打 binder",
            !composableBody.contains("InputDevice.getDeviceIds()") &&
                !composableBody.contains("probeDpadInputDevices()"),
        )
    }

    @Test
    fun `按键痕迹必须仍进诊断读数并在首次方向键后再打一次`() {
        // dpadProbe 是进程级缓存，对「运行中才接入」的键盘/手柄会过期。
        // v2.39.7 起显示不再依赖它（所以不需要失效机制），但读数必须还能反映按键痕迹，
        // 并且要在第一次按键后**再打一次** —— 否则回传的截图只有进场那一刻的过期值。
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        val probeBody = functionBody(text, "internal fun probeFocusVisualInputs(")
        assertTrue(
            "probeFocusVisualInputs 不再读 directionalNavigationSeen（按键痕迹从读数里消失了）",
            probeBody.contains("DpadInputTracker.directionalNavigationSeen"),
        )
        val activity = uiSourceRoot().resolve("MainActivity.kt").readText()
        assertTrue(
            "MainActivity 没有以 directionalNavigationSeen 为键的 LaunchedEffect（首按后不会再打读数）",
            activity.contains("LaunchedEffect(DpadInputTracker.directionalNavigationSeen)"),
        )
        // 决策函数绝不许读 InputDevice（binder 不得进组合期）
        val composableBody = functionBody(text, "fun shouldShowFocusVisuals()")
        assertTrue("组合期决策打 binder", !composableBody.contains("InputDevice.getDeviceIds()"))
    }

    // ─────────────────────────── D-PAD 探测的纯逻辑（误判面） ───────────────────────────

    @Test
    fun `遥控器这类独立 D-PAD 设备应判定为具备导航能力`() {
        // 遥控器 / 蓝牙键盘 / 手柄：独立 device，声明 DPAD 但不是触摸数字化器
        assertTrue("独立 DPAD 设备应命中", isRemoteNavigationDevice(hasDpad = true, isTouchDigitizer = false))
    }

    @Test
    fun `触摸数字化器即使顺带支持 DPAD 也不得命中`() {
        // 部分 ROM / 模拟器给内建触摸设备多报 source 位 —— 那不是遥控器，
        // 命中它会让裸机手机误判成远控设备（v2.36.0 P2-34 的回归形态）
        assertFalse(
            "TOUCHSCREEN + DPAD 不得命中（防手机误判）",
            isRemoteNavigationDevice(hasDpad = true, isTouchDigitizer = true),
        )
    }

    @Test
    fun `裸机手机的典型设备组合一律不命中`() {
        // 手机上通常只挂着触摸数字化器（无 DPAD）；键盘类设备只报 KEYBOARD 不报 DPAD
        val phoneCases = listOf(
            Triple("触摸数字化器", false, true),
            Triple("纯触摸", false, false),
            Triple("无任何能力", false, false),
            Triple("软键盘（只报 KEYBOARD）", false, false),
        )
        val wrong = phoneCases.filter { (label, dpad, touch) ->
            isRemoteNavigationDevice(hasDpad = dpad, isTouchDigitizer = touch)
        }
        assertTrue("裸机手机不应被判为具备导航能力：${wrong.map { it.first }}", wrong.isEmpty())
    }

    // ─────────────────────────── 方向键判据（纯逻辑） ───────────────────────────

    @Test
    fun `方向键与确认键算作按键导航`() {
        val navigation = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
        )
        val missed = navigation.filterNot { isDirectionalNavigationKey(it) }
        assertTrue("这些键应被识别为方向键导航：$missed", missed.isEmpty())
    }

    @Test
    fun `媒体键与音量键不算方向键导航`() {
        // 媒体键由 MediaKeyHandler 处理；把它们算进来会让「调个音量 ⇒ 焦点此后常亮」误触发
        val notNavigation = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_UNKNOWN,
        )
        val wrong = notNavigation.filter { isDirectionalNavigationKey(it) }
        assertTrue("这些键不应被识别为方向键导航：$wrong", wrong.isEmpty())
    }

    @Test
    fun `只有按下才翻转标志，且一旦翻转就粘住`() {
        assertTrue(
            "ACTION_DOWN 的方向键应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN),
        )
        assertFalse(
            "ACTION_UP 不应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP),
        )
        assertFalse(
            "ACTION_MULTIPLE 不应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_MULTIPLE),
        )
        assertFalse(
            "非方向键不应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN),
        )
        assertTrue(
            "已翻转后必须保持 true（粘住）",
            nextDirectionalSeen(true, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP),
        )
    }

    // ─────────────────────────── 负向自证（护栏有效性） ───────────────────────────

    @Test
    fun `负向：focusRing 排在 background 之前必须判违规`() {
        val text = """
            Box(
                modifier = Modifier
                    .focusRing(shape = shape, color = c, visible = focused)
                    .background(containerColor, shape)
            )
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `负向：只有裸 border 且排在 background 之前同样判违规`() {
        val text = """
            Box(
                modifier = Modifier
                    .border(width = 2.dp, color = focusBorderColor, shape = shape)
                    .background(targetContainerColor, shape)
            )
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：focusRing 排在 background 之后必须判通过`() {
        val text = """
            Box(
                modifier = Modifier
                    .background(targetContainerColor, shape)
                    .combinedClickable(...)
                    .focusRing(shape = shape, color = c, visible = focused)
            )
        """.trimIndent()
        assertFalse(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：完全没有焦点环修饰符不算违规（避免对无焦点组件误报）`() {
        assertFalse(focusRingBeforeBackground("Modifier.fillMaxWidth().padding(8.dp)"))
    }

    @Test
    fun `负向：两条链里只要有一条把焦点环排在背景之前就判违规`() {
        // 每条链各自成段判定 ⇒ 不能只比「文件里第一个 .background(」
        val text = """
            val a = Modifier.background(c, shape).focusRing(shape, c, true)
            val b = Modifier.focusRing(shape, c, true).background(c, shape)
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `负向：lambda 体夹在中间也不得把焦点环与背景切散`() {
        // 深度跟踪的意义：`.onFocusChanged { … }` 内部有不带点的行，
        // 按行首字符切会漏掉「ring 在 background 之前」的违规
        val text = """
            Box(
                modifier = Modifier
                    .focusRing(shape, c, true)
                    .onFocusChanged { state ->
                        isFocused = state.hasFocus
                        scope.launch { animScale.animateTo(1.08f, tween(200)) }
                    }
                    .background(c, shape)
            )
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：lambda 体夹在中间且顺序正确仍判通过`() {
        val text = """
            Box(
                modifier = Modifier
                    .background(c, shape)
                    .onFocusChanged { state ->
                        isFocused = state.hasFocus
                        scope.launch { animScale.animateTo(1.08f, tween(200)) }
                    }
                    .focusRing(shape, c, true)
            )
        """.trimIndent()
        assertFalse(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：注释里的括号不应把链切碎`() {
        // `// ……（见 §9）` 之类若被计入深度，会让后续真实代码并进上一段造成漏判
        val text = """
            // 说明（见 §9）：这一段注释里有括号 ( 与 半角括号 )
            val b = Modifier.focusRing(shape, c, true).background(c, shape)
        """.trimIndent()
        assertTrue("仍应检出第二条链的违规", focusRingBeforeBackground(text))
    }

    // ─────────────── functionBody 的自证（它服务于上面两条「缓存」门禁） ───────────────

    @Test
    fun `负向自证 functionBody 能正确取出块体函数`() {
        val text = """
            fun outer() {
                val nested = mapOf("a" to 1)
                if (nested.isNotEmpty()) { println(nested) }
            }
            fun after() = 1
        """.trimIndent()
        val body = functionBody(text, "fun outer()")
        assertTrue("块体函数应含其嵌套大括号", body.contains("println(nested)"))
        assertFalse("块体函数不得吞进下一个函数", body.contains("fun after()"))
    }

    @Test
    fun `负向自证 functionBody 能正确取出表达式体函数`() {
        // `fun f(): Boolean = expr` 没有大括号 —— 按「找下一个 { 」切会切错
        val text = """
            fun expr(): Boolean = looksLike || dpadPresent
            fun later() {
                val x = 1
            }
        """.trimIndent()
        val body = functionBody(text, "fun expr(): Boolean")
        assertTrue("表达式体应被取出", body.contains("dpadPresent"))
        assertFalse("表达式体不得吞进后面的块体函数", body.contains("val x = 1"))
    }

    @Test
    fun `负向自证 functionBody 跳过字符串字面量里的括号`() {
        val text = """
            fun withString(): Boolean {
                val q = "}"
                return q.isNotEmpty()
            }
        """.trimIndent()
        val body = functionBody(text, "fun withString(): Boolean")
        assertTrue("字面量里的 } 不应提前截断函数体", body.contains("q.isNotEmpty()"))
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private fun uiSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui"),
            File("app/src/main/java/com/nasmusic/tv/ui"),
            File("../app/src/main/java/com/nasmusic/tv/ui"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 UI 源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null,
        )
        return found!!
    }

    /** 相对 `com/nasmusic/tv/` 的源文件 —— 开关链路跨了 ui / data / Application 三层。 */
    private fun projectSource(relative: String): File {
        val root = uiSourceRoot().parentFile
        assertTrue("UI 源码目录的父目录不是 com/nasmusic/tv：${root?.absolutePath}", root?.name == "tv")
        val file = root!!.resolve(relative)
        assertTrue("找不到源文件 ${file.absolutePath}（链路被挪动，护栏会空转）", file.isFile)
        return file
    }

    private fun resFile(locale: String): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"), File("../app/src/main/res"))
        val res = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到 res 目录（user.dir=${File("").absolutePath}）", res != null)
        val file = File(res!!, "$locale/strings.xml")
        assertTrue("${file.absolutePath} 不存在", file.isFile)
        return file
    }
}

/** 画焦点环的源码文件（相对 `ui/` 源目录） */
private val FOCUS_RING_SITES = listOf(
    "components/FocusableSurface.kt",
    "components/song/UnifiedSongRow.kt",
)

/** 焦点环的两种写法（都要查，否则换个写法就能绕过门禁） */
private val FOCUS_RING_MARKERS = listOf(".focusRing(", ".border(")

/**
 * 决策函数**必须**读的两个输入（v2.39.7：用户开关 + 触摸抑制）。
 * 少任何一个都会重现对应故障：少开关 ⇒ 我们又回到猜设备；少抑制 ⇒ 手机粘滞焦点。
 */
private val DECISION_MARKERS = listOf(
    "FocusVisualPreference.highlightEnabled",
    "FocusInputTracker.touchNavigating",
)

/**
 * 决策函数**绝不许**出现的设备嗅探标记。
 *
 * 每一条都曾是「焦点看不看得见」的判据，也都在用户的盒子上落空过 ——
 * 它们现在只该出现在 [probeFocusVisualInputs] / 诊断日志里。
 */
private val DEVICE_SNIFF_MARKERS = listOf(
    "hasSystemFeature",
    "isKeyNavigationConfiguration(",
    "probeFocusVisualInputs",
    "dpadProbe",
    "SOURCE_DPAD",
    "directionalNavigationSeen",
)

/** 决策体里命中的嗅探标记（真实用例与负向自证**共用同一个谓词**，否则自证只证明了自己）。 */
private fun deviceSniffHits(body: String): List<String> = DEVICE_SNIFF_MARKERS.filter { body.contains(it) }

/** 该文件里是否存在「焦点环排在不透明背景之前」的写法。逐条修饰符链判定。 */
private fun focusRingBeforeBackground(text: String): Boolean =
    modifierChains(text).any { chain ->
        val backgroundAt = chain.indexOf(".background(")
        if (backgroundAt < 0) return@any false
        firstIndexOfAny(chain, FOCUS_RING_MARKERS) in 0 until backgroundAt
    }

/**
 * 把源码切成「修饰符链」片段。
 *
 * 续行判定用**括号深度**而不是行首字符：`.onFocusChanged { state -> … }` 的 lambda 体里有
 * 缩进但不带点的行（`isFocused = state.hasFocus`、`tween(200)`、`}`），按行首字符切会把
 * `焦点环` 和 `.background(` 切到两段去 ⇒ 漏判。深度回到 0 且新行不以 `.` 开头时才另起一段。
 */
private fun modifierChains(text: String): List<String> {
    val chains = mutableListOf<StringBuilder>()
    var current = StringBuilder()
    var depth = 0
    for (raw in text.lines()) {
        if (depth <= 0 && current.isNotEmpty() && !raw.trimStart().startsWith(".")) {
            chains += current
            current = StringBuilder()
        }
        current.append(raw).append('\n')
        depth += depthDelta(raw)
    }
    if (current.isNotEmpty()) chains += current
    return chains.map { it.toString() }
}

/** 一行带来的括号深度变化；先剥掉 `//` 行注释与字符串，避免注释里的括号把深度带偏。 */
private fun depthDelta(raw: String): Int {
    val line = raw.substringBefore("//")
    var inString = false
    var delta = 0
    for (ch in line) {
        when (ch) {
            '"' -> inString = !inString
            '{', '(', '[' -> if (!inString) delta++
            '}', ')', ']' -> if (!inString) delta--
        }
    }
    return delta
}

private fun firstIndexOfAny(text: String, needles: List<String>): Int =
    needles.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1

/**
 * 取出某个函数声明的函数体（含签名那一行），按括号深度配平。
 *
 * ⛔ 不能用「下一个空行」或「下一个 `fun `」切段 —— Kotlin 表达式体函数
 * （`fun f() = expr`）没有大括号，而块体函数的 body 里常含嵌套 lambda。
 * 按深度配平才既覆盖两种形态、又不把嵌套 lambda 提前截断。
 */
private fun functionBody(text: String, signature: String): String {
    val start = text.indexOf(signature)
    require(start >= 0) { "找不到函数签名：$signature" }
    val open = text.indexOf('{', start)
    // 表达式体：`fun f(): Boolean = …` 到行尾为止（不跨行，避免误吞下一段）
    if (open < 0 || text.substring(start, open).contains('=')) {
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return text.substring(start, lineEnd)
    }
    var depth = 0
    var i = open
    while (i < text.length) {
        val ch = text[i]
        // 跳过字符串字面量，避免字面量里的括号把深度带偏
        if (ch == '"') {
            i++
            while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
        } else {
            if (ch == '{') depth++ else if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        i++
    }
    return text.substring(start)
}