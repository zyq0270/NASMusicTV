plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun readKeystoreProperty(name: String): String {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return ""
    return try {
        val lines = f.readLines()
        val prefix = "$name="
        lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: ""
    } catch (_: Exception) { "" }
}

// Gradle 不把 local.properties 当项目属性加载（AGP 只取其中的 sdk.dir），要读必须自己解析。
// ⚠️ 不能用 `java.util.Properties`：Kotlin DSL 里 `java` 被 `JavaPluginExtension` 访问器占用。
fun readLocalProperty(name: String): String? {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return null
    return try {
        val prefix = "$name="
        f.readLines().firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

val keystoreStoreFile = readKeystoreProperty("storeFile")
val keystoreStorePassword = readKeystoreProperty("storePassword")
val keystoreKeyAlias = readKeystoreProperty("keyAlias")
val keystoreKeyPassword = readKeystoreProperty("keyPassword")

// 百度网盘开放平台 AppKey/SecretKey（从 keystore.properties 读取，gitignored，不硬编码在源码）
val baiduAppId = readKeystoreProperty("baiduAppId")
val baiduAppSecret = readKeystoreProperty("baiduAppSecret")

// S1 阶段 A+（2026-09-14）：CryptoUtils AES-256 派生口令不再在仓库内提供默认值。
// 取值顺序：keystore.properties.cryptoPassphrase → 环境变量 CRYPTO_PASSPHRASE。
// keystore.properties 已在 .gitignore 中（不入仓），仓库 HEAD 不再包含该口令。
//
// ⚠️ 兼容性：既有安装的加密凭据（百度 refresh_token 等）由历史口令加密，本地必须提供
// 与之相同的 cryptoPassphrase，否则已保存凭据将无法解密。为防止静默发布"换过密钥"的包，
// release 打包在口令缺失时会直接失败（见文件末尾 guard），debug/CI 不受影响。
// 注意：口令仍会被编译进 APK 的 BuildConfig（"混淆级"而非"保密级"）；彻底方案见
// CryptoUtils.kt 注释（AndroidKeyStore/StrongBox + 既有数据迁移）。
val cryptoPassphrase = readKeystoreProperty("cryptoPassphrase")
    .ifBlank { System.getenv("CRYPTO_PASSPHRASE").orEmpty() }

// ── 本机「私有覆盖层」（仓库外；CI 与别人的机器上不存在）────────────────────
// 本机的 Gradle 初始化脚本（~/.gradle/init.d/10-nasmusic-e51-local.gradle）把**仓库外**的
// 覆盖层挂成 app 模块 main 源集的额外源目录，并在 Kotlin 编译任务层排除仓库里那几份同名文件
// ⇒ **本地构建实际编译的是覆盖层那份**。覆盖层的目录形态（与 init 脚本一一对应）：
//     <overlay>/ovl/app/src/main/java/com/nasmusic/tv/...    ← Kotlin 源码
//     <overlay>/ovl/app/src/main/assets/...                  ← 资产（E52 的 e52_highway/）
// init 脚本把覆盖层根目录通过项目属性 `e51.overlay` 传进来 ⇒ 这里读同一个属性。
//
// ⭐ 这就是「**在 main 上本地编译也携带隐藏分支的效果**」的实现：
//    隐藏分支（feat/e51-private）的 E51/E52 源码与资产经覆盖层进入本地构建；
//    GitHub / CI 上没有 init 脚本 ⇒ 没有覆盖层 ⇒ 一行隐藏分支代码都不会被编译进公开包。
val privateOverlayRoot: File? = (rootProject.findProperty("e51.overlay") as String?)
    ?.let { File(it) }
    ?.takeIf { it.isDirectory }
val privateOverlayJava: File? = privateOverlayRoot?.resolve("ovl/app/src/main/java")
val privateOverlayAssets: File? = privateOverlayRoot?.resolve("ovl/app/src/main/assets")

// E51「像素城市」发布隔离开关（docs/archive/e51-lofi-city-plan.md §2.5）：
// 本机 local.properties 里写 `lofi.city=true` 即把该效果编进包；CI 与公开构建缺省 false。
// ⚠️ 该开关只隔离 APK，不隔离源码（本仓库是公开仓库，代码文本仍随仓库发布）。
val lofiCityEnabled: String = (readLocalProperty("lofi.city") ?: providers.gradleProperty("lofi.city").orNull)
    .toString().toBooleanStrictOrNull()?.toString() ?: "false"

// E51「像素城市」源码存在性护栏（配合上面的发布隔离开关）：
// `lofi.city=true` 的语义是"本次构建要含**真**的像素城市效果"，但 E51 移植件**不上传本仓库**
// （公开仓库，代码不外发 —— 见方案 §2.6）。它们有两种可能的来源：
//   ① 分支 `feat/e51-private` 里被跟踪的源码（app/src/main/java/.../visualizer/e51）；
//   ② 本机 Gradle 初始化脚本挂进来的**仓库外覆盖层**（~/.gradle/init.d/10-nasmusic-e51-local.gradle，
//      它把 `e51.overlay` 这个项目属性指到覆盖层目录）。
// 两者都没有时带着这个开关构建，Gradle 不会报错：工厂会把 LOFI_CITY 映射到 `CircularRingRenderer()`
// （穷尽性兜底），于是会**静默**编出一个"主题在、效果是通用圆环"的假包。故此处显式失败。
//
// ⚠️ 判定的是「**源码真的在**」（覆盖层里要有 visualizer/e51 目录），而不是「覆盖层目录存在」
// —— 后者会在覆盖层不完整时放行 ⇒ 又变成假包。
if (lofiCityEnabled == "true") {
    val e51Tracked = rootProject.file("app/src/main/java/com/nasmusic/tv/visualizer/e51")
    val e51OverlaySources = privateOverlayJava?.resolve("com/nasmusic/tv/visualizer/e51")
    if (!e51Tracked.isDirectory && e51OverlaySources?.isDirectory != true) {
        throw GradleException(
            "E51「像素城市」：lofi.city=true，但既没有分支内的 E51 源码，覆盖层里也没有。\n" +
                "  · 要出**带真效果**的本地包：切到分支 feat/e51-private 构建，或让本机 Gradle 初始化脚本\n" +
                "    （~/.gradle/init.d/10-nasmusic-e51-local.gradle）挂上仓库外覆盖层，\n" +
                "    并用 `python scripts/sync_private_overlay.py` 把覆盖层同步到该分支的 E51/E52 源码与资产。\n" +
                "  · 在没有 E51 源码的分支上构建：请把 local.properties 的 lofi.city 设为 false，\n" +
                "    否则会编出一个「主题可选、效果却是通用兜底」的假包。"
        )
    }
}

// E52「阔野公路」发布隔离开关（docs/archive/e52-highway-opengl-plan.md §八）：
// 本机 local.properties 里写 `highway.enabled=true` 即把该效果编进包；CI 与公开构建缺省 false。
// ⚠️ 与 E51 的 `lofi.city` 同理：开关只隔离 APK 可选性，不隔离源码（E52 移植件 ⛔ 不 push）。
val highwayEnabled: String = (readLocalProperty("highway.enabled") ?: providers.gradleProperty("highway.enabled").orNull)
    .toString().toBooleanStrictOrNull()?.toString() ?: "false"

// E52「旷野公路」资产存在性护栏（配合上面的发布隔离开关）：
// `highway.enabled=true` 的语义是"本次构建要含**真**的旷野公路效果"。E52 的 GLES 渲染器
// 在运行时按固定路径从 `assets/e52_highway/` 读贴图与 .obj 模型，路径与体积登记在随资产
// 一同提交的 `manifest.json` 里。
//
// 为什么必须显式失败：清单里的文件缺失或被截断时，**Gradle 不会报错**——assets 是原样打包的。
// 于是一个"主题可选、贴图缺了一半"的假包会被静默编出来，症状要等到真机跑到那一步才炸
// （纹理变白 / 模型不出现 / 尺寸不匹配），定位成本极高。故此处提前失败。
//
// ⛔ 只校验「存在 + 字节数」，**不**在 Gradle 里算 SHA-256：94 个文件共 8.4 MiB，
// 逐位哈希会让每次**配置期**多花几百毫秒，而配置期在 CI 上会被放大成分钟级。
// 逐位一致性交给独立的 JVM 单测：
// `app/src/test/java/com/nasmusic/tv/visualizer/highway/HighwayAssetManifestTest.kt`
// （它读同一个 manifest.json，对每个文件重算 SHA-256）。
//
// ⚠️ 缺省 false（CI 与公开构建）时本段**完全不执行**：不读 manifest、不 stat 任何文件 ⇒ 零开销。
if (highwayEnabled == "true") {
    // ⭐ 资产根的两种来源（顺序固定）：
    //   ① 仓库内 `app/src/main/assets/e52_highway`（私有分支 feat/e51-private 上有）；
    //   ② 本机覆盖层 `<overlay>/ovl/app/src/main/assets/e52_highway`（main 上靠它携带效果）。
    // 两者都没有 ⇒ 下面的清单护栏会显式失败，不会编出「主题可选、贴图却缺失」的假包。
    val repoHighwayAssets = rootProject.file("app/src/main/assets/e52_highway")
    val overlayHighwayAssets = privateOverlayAssets?.resolve("e52_highway")
    val highwayAssetRoot: File = when {
        repoHighwayAssets.isDirectory -> repoHighwayAssets
        overlayHighwayAssets?.isDirectory == true -> overlayHighwayAssets
        else -> repoHighwayAssets
    }

    // E52 渲染器源码存在性护栏：资产齐了但源码不在（覆盖层不完整 / 分支上没带源码）
    // ⇒ Kotlin 编译会以 "Unresolved reference 'HighwayRenderer'" 报错，信息难懂 ⇒ 这里提前失败。
    val repoHighwaySources = rootProject.file("app/src/main/java/com/nasmusic/tv/visualizer/highway")
    val overlayHighwaySources = privateOverlayJava?.resolve("com/nasmusic/tv/visualizer/highway")
    if (!repoHighwaySources.isDirectory && overlayHighwaySources?.isDirectory != true) {
        throw GradleException(
            "E52「旷野公路」：highway.enabled=true，但既没有分支内的 E52 源码，覆盖层里也没有。\n" +
                "  · 要出**带真效果**的本地包：切到分支 feat/e51-private 构建，或让本机 Gradle 初始化脚本\n" +
                "    （~/.gradle/init.d/10-nasmusic-e51-local.gradle）挂上仓库外覆盖层，\n" +
                "    并用 `python scripts/sync_private_overlay.py` 把覆盖层同步到该分支的 E52 源码与资产。\n" +
                "  · 在没有 E52 源码的分支上构建：请把 local.properties 的 highway.enabled 设为 false。"
        )
    }

    val highwayManifestFile = File(highwayAssetRoot, "manifest.json")
    if (!highwayManifestFile.isFile) {
        throw GradleException(
            "E52「旷野公路」：highway.enabled=true，但缺少资产清单 " +
                "${highwayManifestFile.absolutePath}。\n" +
                "  · 清单随资产一同提交，记录每个文件的字节数与 SHA-256（逐位校验的基准）。\n" +
                "  · 没有清单就无法校验资产 ⇒ 请把整个 e52_highway/ 目录（含 manifest.json）一起带入\n" +
                "    （仓库内 app/src/main/assets/e52_highway/ 或覆盖层 ovl/app/src/main/assets/e52_highway/）。\n" +
                "  · 在不带 E52 资产的分支上构建：请把 local.properties 的 highway.enabled 设为 false，\n" +
                "    否则会编出一个「主题可选、贴图却缺失」的假包。"
        )
    }
    val highwayManifest: Map<*, *> = try {
        // ⚠️ JsonSlurper 在 Kotlin 侧没有 String 重载 ⇒ 传 Reader。
        groovy.json.JsonSlurper().parse(highwayManifestFile.reader()) as? Map<*, *>
            ?: throw GradleException(
                "E52「旷野公路」：资产清单顶层不是 JSON 对象：${highwayManifestFile.absolutePath}"
            )
    } catch (e: GradleException) {
        throw e
    } catch (e: Exception) {
        throw GradleException(
            "E52「旷野公路」：资产清单无法解析：${highwayManifestFile.absolutePath}\n" +
                "  · ${e.message}\n" +
                "  · 清单必须是合法 JSON，且含非空的 assets 数组。"
        )
    }
    @Suppress("UNCHECKED_CAST")
    val highwayEntries: List<Map<String, Any?>> =
        (highwayManifest["assets"] as? List<Map<String, Any?>>) ?: emptyList()
    if (highwayEntries.isEmpty()) {
        throw GradleException(
            "E52「旷野公路」：资产清单里没有 assets 条目：${highwayManifestFile.absolutePath}\n" +
                "  · 空清单意味着「什么都不要」——那就不该开 highway.enabled=true。"
        )
    }
    val highwayProblems = mutableListOf<String>()
    highwayEntries.forEachIndexed { index, entry ->
        val assetName = entry["file"]?.toString()
        val expectedBytes = (entry["bytes"] as? Number)?.toLong()
        if (assetName.isNullOrBlank()) {
            highwayProblems += "  · assets[$index] 缺少 file 字段"
            return@forEachIndexed
        }
        if (expectedBytes == null) {
            highwayProblems += "  · assets[$index] ($assetName) 缺少 bytes 字段"
            return@forEachIndexed
        }
        val assetFile = File(highwayAssetRoot, assetName)
        if (!assetFile.isFile) {
            highwayProblems += "  · 缺失资产：$assetName —— 清单期望 $expectedBytes 字节，" +
                "实际：文件不存在（${assetFile.absolutePath}）"
            return@forEachIndexed
        }
        val actualBytes = assetFile.length()
        if (actualBytes != expectedBytes) {
            highwayProblems += "  · 字节数不符：$assetName —— 清单期望 $expectedBytes 字节，" +
                "实际 $actualBytes 字节（${assetFile.absolutePath}）"
        }
    }
    if (highwayProblems.isNotEmpty()) {
        throw GradleException(
            "E52「旷野公路」：资产校验失败（${highwayProblems.size} 项）—— highway.enabled=true " +
                "表示本次构建要含真的旷野公路效果，\n但 assets/e52_highway/ 里的资产与清单对不上：\n" +
                highwayProblems.joinToString("\n") + "\n" +
                "  · 修法：按清单里的 sha256 从上游重新拷贝缺失/损坏的文件（清单是逐位校验的基准），\n" +
                "    或把 local.properties 的 highway.enabled 设为 false。"
        )
    }
}

android {
    namespace = "com.nasmusic.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nasmusic.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = 201
        versionName = "2.39.16"

        // 洛雪宿主的引擎测试依赖 native 库，只能在设备上跑（见文件末尾 androidTestImplementation 注释）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // 百度网盘 AppKey/SecretKey（运行时由 BaiduOAuthClient 经 BuildConfig 读取）
        buildConfigField("String", "BAIDU_APP_ID", "\"$baiduAppId\"")
        buildConfigField("String", "BAIDU_APP_SECRET", "\"$baiduAppSecret\"")
        
        // S1 修复（2026-09-13）：CryptoUtils AES-256 派生口令（运行时由 CryptoUtils 经 BuildConfig 读取）
        buildConfigField("String", "CRYPTO_PASSPHRASE", "\"$cryptoPassphrase\"")

        // E51 像素城市：本机/公开双变体开关（读取处见文件顶部，口径见 docs/archive/e51-lofi-city-plan.md §2.5）
        buildConfigField("boolean", "LOFI_CITY_ENABLED", lofiCityEnabled)
        // E52「阔野公路」：本机/公开双变体开关（读取处见文件顶部）
        buildConfigField("boolean", "HIGHWAY_ENABLED", highwayEnabled)
    }

    // CI 的 Unit tests job 不生成 keystore.properties：无 release 签名配置时跳过创建，
    // 否则 file("") 在配置期抛 IllegalArgumentException，导致所有 Gradle 任务失败（含 testDebugUnitTest）。
    //
    // storeFile 用 rootProject.file() 而非 project.file()：前者让相对路径相对**项目根**解析
    // （`release-key.jks` / `ci-keystore.jks` 都放在项目根），后者会找 `app/<file>` 找不到。
    // 绝对路径两种都兼容。详见 docs/technical-overview.md §10.164。
    signingConfigs {
        if (keystoreStoreFile.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(keystoreStoreFile)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // findByName：无 keystore.properties 时返回 null（AGP 跳过签名，打包阶段才失败），
            // 而非 getByName 的配置期 NoSuchElementException。
            // ⚠️ 2026-10-06 起 CI push/tag 路径的失败点前移：workflow 的 keystore 步骤
            // 已 D5 fail-fast（secrets 缺失直接 exit 1），不会等到这里的打包阶段才失败
            // （docs/archive/permission-and-signing-plan.md §14.4）。本地构建仍走本行的打包期失败。
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isDebuggable = true
            // ⭐ **可选的包名后缀**（默认**关闭** ⇒ 行为与以往完全一致）。
            //
            // 用途：真机 instrumented 测试（`connectedDebugAndroidTest`）需要装一个 **debug 签名**的
            // App 到设备上；若设备上已装**同包名**的 **release 签名**包 ⇒ 签名不一致 ⇒
            // `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ⇒ 而 UTP 的安装流程会**先卸载旧包** ⇒
            // **App 数据（服务器连接与凭据）会丢**。
            // ⇒ 加后缀让两包**并存**（`com.nasmusic.tv.debug` vs `com.nasmusic.tv`）⇒ 不卸载、不丢数据。
            //
            // 用法：`./gradlew.bat connectedDebugAndroidTest "-Pe50.debugSuffix=true" …`
            // ⛔ 该开关**不得**改成默认开启（会让所有 debug 安装都变成 `com.nasmusic.tv.debug`）。
            if (providers.gradleProperty("e50.debugSuffix").isPresent) {
                applicationIdSuffix = ".debug"
            }
        }
    }

    testOptions {
        // 纯 JVM 单测允许 android.* API 返回默认值（E25 催眠渲染器构造含 Paint）
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
                // 注：Media3 的 androidx.media3.common.util.UnstableApi 不能在这里 -opt-in。
                // 它走的是 androidx 的 @RequiresOptIn 机制，Kotlin 编译器不认（加进来会报
                // "not an opt-in requirement marker"），lint 也不认。正确做法是在使用处标
                // @androidx.annotation.OptIn(UnstableApi::class)，见 PcmTapProcessor 等 7 个文件。
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // APK 文件名格式：NASMusicTV-release-v2-24-2.apk（版本号点号替换为横线）
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val vName = variant.versionName.replace(".", "-")
            output.outputFileName = "NASMusicTV-${variant.name}-v${vName}.apk"
        }
    }
}

// S1 阶段 A+ guard：release 打包前校验口令已配置，避免静默发布一个换了密钥的包
// （既有用户凭据将无法解密）。仅作用于 release 打包任务，debug / CI 不受影响。
tasks.configureEach {
    if (name == "packageRelease") {
        doFirst {
            if (cryptoPassphrase.isBlank()) {
                throw GradleException(
                    "CRYPTO_PASSPHRASE 未配置：请在 keystore.properties 添加 " +
                        "cryptoPassphrase=<历史口令>，或设置环境变量 CRYPTO_PASSPHRASE。" +
                        "缺失会改变加密密钥，导致既有已保存凭据无法解密。"
                )
            }
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose for TV
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")

    // Compose core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")

    // Concurrent Futures (for ResolvableFuture used in CoilBitmapLoader)
    implementation("androidx.concurrent:concurrent-futures:1.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // 封面取色（可视化效果 T5）
    implementation("androidx.palette:palette-ktx:1.0.0")

// DataStore (Preferences)
    // 1.0.0 在 Windows(Robolectric)下连续写同名文件有 rename 竞态（SingleProcessDataStore.kt:433），1.1.1 修复
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // 二维码生成（手机扫码输入）
    implementation("com.google.zxing:core:3.5.3")

    // 本地 HTTP server（接收手机浏览器提交的文字）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 拼音转换（兼容 API 22+，不依赖 ICU）
    implementation("com.github.promeg:tinypinyin:2.0.3")

    // AppCompat (for AppCompatDelegate.setApplicationLocales)
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Core KTX (for LocaleListCompat)
    implementation("androidx.core:core-ktx:1.12.0")

    // Leanback (TV support)
    implementation("androidx.leanback:leanback:1.0.0")

    // ONNX Runtime (Spleeter 高质量人声分离)
    //
    // ⚠️ 版本被「minSdk 22」与「16KB 页对齐」双重锁定，**不要随手升级**（2026-09-14 实测结论）
    //
    // lint 的 `Aligned16KB` 会报这个依赖：1.17.1 的 native 库 PT_LOAD 段 `p_align = 4096`，
    // 未满足 Android 16KB 页要求。但升级不是「改个版本号」的事：
    //
    //   版本     libonnxruntime.so   libonnxruntime4j_jni.so    minSdk 要求
    //   1.17.1   4096  (未对齐)      4096  (未对齐)             21
    //   1.20.0   16384 (已对齐)      4096  (未对齐)             21   ← 陷阱，见下
    //   1.21.1   16384 (已对齐)      arm64 已对齐 / v7a 未对齐   24
    //   1.29.0   16384 (已对齐)      16384 (已对齐)             24
    //
    // 两个关键事实（均实测，非推测）：
    //
    // 1) **只有 1.29.0 能让警告消失**。lint 的检查器（AGP 的
    //    `PageAlignmentDetector.getIncidentsFromAndroidLibrary`）遍历 AAR 解包目录下
    //    **全部 4 个 ABI × 全部 native 库**（不受 `abiFilters` 影响），命中第一个未对齐的库
    //    就 `return` —— 所以每个依赖**只报 1 条**（报告里的 "3" 是聚合重复）。
    //    1.20.0 的 `libonnxruntime.so` 虽已对齐，但 `libonnxruntime4j_jni.so` 仍是 4096，
    //    lint 只会改报那个文件，**警告并不会消失**；且 16KB 真机上该库 `dlopen` 仍会失败。
    // 2) **1.29.0 的 AAR manifest 要求 `minSdkVersion 24`**，而本项目是 22。直接改会让
    //    manifest merger 失败；改 minSdk 则等于砍掉 Android 5.0/5.1/6.0 设备
    //    （含开发用的创维 Android 5.1.1 电视 —— 真机回归的基准设备）。
    //
    // 因此当前**有意保持 1.17.1**：`targetSdk 34` 下 Google Play 的 16KB 强制要求尚未触发
    // （那是 `targetSdk 35+` 的事），且这条是 Warning 而非 Error（lint 当前 0 errors）。
    // **触发条件**：升 `targetSdk 35` 或上架前必须处理 —— 届时只有两条路：升 minSdk 24 并换
    // 1.29.0，或自编 ONNX Runtime（加 `-Wl,-z,max-page-size=16384`）。
    // 完整实测矩阵见 `docs/technical-overview.md` §10.146 的 §七-4 遗留项。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Room (本地音乐索引持久化)
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // jaudiotagger (音频元数据读写：封面/歌词内嵌，LGPL；GPL v3 项目整体合规)
    implementation("net.jthink:jaudiotagger:3.0.1")

    // 洛雪自定义音源：QuickJS 沙箱（方案 §2.6 / §9.2）
    // 库已停更（2021-10），但只服务于「脚本取链」，搜索链路完全不受影响。
    // ⚠️ 它的 JNI 从不调用 `JS_ExecutePendingJob` ⇒ `async/await` 不会推进，
    //    所以下面用 JNA 直接调 `libquickjs.so` 导出的该函数（见 LxJobPump.kt）。
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // v2.35.0：Room 迁移测试（MigrationTestHelper）
    testImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // 洛雪宿主测试：QuickJS 是 native 库，Robolectric（JVM）载不进 .so，
    // 所以 T2–T6 的引擎测试只能放 androidTest，在真机/电视上跑（方案 §9.7）。
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

/**
 * P3-4（2026-09-16）：Room schema 导出目录。
 *
 * `LocalMusicDatabase` 声明了 `exportSchema = true`，但此前**没有**配置 `room.schemaLocation`，
 * Room 只能打印 "Schema export directory was not provided…" 警告并且**一个 schema 都不导出**——
 * 于是「v3 破坏性迁移」这件事在仓库里没有任何可追溯的记录（P3-4 发现的问题）。
 *
 * 现在 schema JSON 会写到 `app/schemas/<DB 全限定名>/<version>.json`，需随代码一并提交入库。
 * 用途不是支撑 Migration（本地索引仍走 `fallbackToDestructiveMigration`，可由重扫重建），
 * 而是**留档**：将来若要改回 Migration、或需要核对某个版本的表结构/索引定义，有权威快照可比对。
 *
 * ⚠️ 每次 bump version 都会新增一个 JSON，**不要删除旧文件**——删掉就丢了版本历史。
 *
 * v2.35.0（多码率）：`DownloadDatabase` 也改为 `exportSchema = true`。
 * 原因是它现在有了**真实迁移**（`MIGRATION_1_2`），而 `MigrationTestHelper` 必须
 * 读取基线 schema（`1.json`）才能构造 v1 库、验证迁移后的表结构与实体定义一致。
 * 没有基线 schema 就只能靠手工建表 + 手工算 identityHash，测试会变得脆弱且不可信。
 * 产物位于 `app/schemas/com.nasmusic.tv.backend.download.db.DownloadDatabase/{1,2}.json`。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * 单测失败时把**完整断言文案 + 全栈**打进 CI 控制台。
 *
 * Gradle 默认是 `SHORT`，控制台只印 `java.lang.AssertionError at XxxTest.kt:111` 加一层
 * `Caused by`，**断言自己写的消息一个字都不显示**。2026-10-17 `ProviderMirrorTest` 在 CI
 * 连红两次（本地 7/7 全绿），两次都只能看到行号、看不到「当前值/期望值/耗时」，于是排查
 * 只能靠猜。该条时序用例同日已退役（CI 环境对生产没有可比性，见 §10.248），但「失败必须
 * 自带可判读的信息」这条与它无关，是全局要求，因此本配置保留；
 * 配套的 CI 步骤见 `.github/workflows/build.yml` 的 `Dump test failure messages` +
 * `Upload unit test results`（红时整份 XML 存档，可离线复盘）。
 */
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
