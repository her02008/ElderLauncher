import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------- 版本号自动递增 ----------------
//
// `version.properties`（项目根目录）是版本号的唯一来源。
// 每次执行打包类任务（assemble / bundle / build / install / package）时：
//   versionCode +1，versionName 末位 +1，然后把新值写回文件，并用新值打这个包。
// 这样每出一个包版本号都不重复，不会出现「同号包装不上」。只想看看任务列表（如 `gradle tasks`、
// `clean`）不会动版本号。
//
// 注意：android 自带的 APK 里 versionName 会显示成 1.0.1 这种，设置页「关于」也读的是它。

val versionFile = rootProject.file("version.properties")

val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}

/** 末位 +1：1.0.0 -> 1.0.1；末位不是数字时直接补一个 .1 */
fun bumpPatch(name: String): String {
    val parts = name.split(".").toMutableList()
    val last = parts.lastOrNull()?.toIntOrNull()
    if (last == null) parts.add("1") else parts[parts.size - 1] = (last + 1).toString()
    return parts.joinToString(".")
}

val wantsPackage = gradle.startParameter.taskNames.any { task ->
    // 只看任务名最后一段（":app:assembleDebug" -> "assembleDebug"），
    // 免得像 generateDebugBuildConfig 这种带 build 字样的任务被误判成打包
    val name = task.substringAfterLast(':').lowercase()
    name == "build" ||
        name.startsWith("assemble") ||
        name.startsWith("bundle") ||
        name.startsWith("install") ||
        name.startsWith("package")
}

var autoVersionCode = versionProps.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 0
var autoVersionName = versionProps.getProperty("versionName")?.trim().orEmpty().ifEmpty { "1.0.0" }

// 发正式版（打 tag）时不希望版本号被偷偷 +1，否则 tag v1.0.47 里装出来是 1.0.48。
// CI 的 tag 构建会带 ELDER_NO_VERSION_BUMP=1；本地也可以用 -PnoVersionBump 临时关掉。
val skipVersionBump = System.getenv("ELDER_NO_VERSION_BUMP") == "1" ||
    gradle.startParameter.projectProperties.containsKey("noVersionBump")

if (wantsPackage && !skipVersionBump) {
    autoVersionCode += 1
    autoVersionName = bumpPatch(autoVersionName)
    // 连同说明一起写回，别让注释被冲掉
    versionFile.writeText(
        "# 版本号唯一来源，由 app/build.gradle.kts 在每次打包时自动 +1 后写回，不要手工改（除非想跳号）。\n" +
            "# versionCode 每次打包递增；versionName 取末位递增（1.0.0 -> 1.0.1 -> ...）。\n" +
            "versionCode=$autoVersionCode\n" +
            "versionName=$autoVersionName\n"
    )
    logger.lifecycle("[version] 本次构建版本：$autoVersionName ($autoVersionCode)，已回写 version.properties")
} else if (wantsPackage) {
    logger.lifecycle("[version] 已跳过自增（NO_VERSION_BUMP），本次构建版本：$autoVersionName ($autoVersionCode)")
}

// ---------------- 签名 ----------------
//
// 仓库里带了一个**固定**的测试签名 `signing/elder-launcher.jks`（别名 elderlauncher，
// storePassword = keyPassword = "elderlauncher"）。它是故意提交进来的，原因：
//
//   CI 每次跑都是全新虚拟机，`~/.android/debug.keystore` 不存在，AGP 会**自己随机生成**一个，
//   于是每次构建出来的包签名都不一样 → 手机上装着上一版就再也盖不上，报
//   INSTALL_FAILED_UPDATE_INCOMPATIBLE。用一个仓库内的固定 key，本地和 CI 签出来的包才一致，
//   才能一直覆盖升级、不丢数据。
//
// 想换成自己的正式签名（上架/长期分发）：在**根目录**放 signing.properties（已被 .gitignore 排除）：
//   storeFile=signing/my-release.jks   # 相对项目根目录
//   storePassword=***
//   keyAlias=***
//   keyPassword=***
// 存在它的时候 release 就用它签；不存在就用上面那个仓库内的测试 key。
val repoKeystore = rootProject.file("signing/elder-launcher.jks")
val repoKeyAlias = "elderlauncher"
val repoKeyPassword = "elderlauncher"
val signingPropsFile = rootProject.file("signing.properties")

android {
    namespace = "com.elder.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elder.launcher"
        minSdk = 24
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = autoVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (repoKeystore.exists()) {
            create("repoTest") {
                storeFile = repoKeystore
                storePassword = repoKeyPassword
                keyAlias = repoKeyAlias
                keyPassword = repoKeyPassword
            }
        }
        if (signingPropsFile.exists()) {
            val sp = Properties().apply { signingPropsFile.inputStream().use { load(it) } }
            create("userRelease") {
                storeFile = rootProject.file(sp.getProperty("storeFile"))
                storePassword = sp.getProperty("storePassword")
                keyAlias = sp.getProperty("keyAlias")
                keyPassword = sp.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 显式指定，别用 AGP 默认那个每次随机生成的 debug.keystore
            if (repoKeystore.exists()) signingConfig = signingConfigs.getByName("repoTest")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                signingPropsFile.exists() -> signingConfigs.getByName("userRelease")
                repoKeystore.exists() -> signingConfigs.getByName("repoTest")
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        // 设置页「关于」要读 BuildConfig.VERSION_NAME / VERSION_CODE
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    // 汉字转拼音不用第三方库：用系统自带的 android.icu.text.Transliterator（API 24 起就有），
    // 零依赖、零体积增长。见 util/PinyinMatcher。
}
