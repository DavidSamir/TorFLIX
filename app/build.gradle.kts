plugins {
    id("torfilx.android.application")
    id("torfilx.android.compose")
    id("torfilx.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The catalogue is the app's entire content, and Android's asset merge lets a variant source set
 * replace it without a word.
 *
 * A two-title `src/debug/assets/catalog.json` lived here and did exactly that: every debug build —
 * which is the build the README tells people to sideload — shipped 2 films instead of 2000, and
 * nothing in the build output or the app said so. Diverging content between variants is never worth
 * the confusion it causes, so it is now a build failure rather than a surprise on the television.
 */
val shadowingCatalogues = file("src")
    .listFiles()
    .orEmpty()
    .filter { it.isDirectory && it.name != "main" }
    .map { File(it, "assets/catalog.json") }
    .filter { it.exists() }

require(shadowingCatalogues.isEmpty()) {
    "A variant source set overrides the bundled catalogue, so this build would ship different " +
        "content from a release build: ${shadowingCatalogues.joinToString { it.relativeTo(projectDir).path }}. " +
        "Delete it — the catalogue in :core:data is the only one that should ship."
}

android {
    namespace = "com.torfilx.tv"

    defaultConfig {
        applicationId = "com.torfilx.tv"
        versionCode = 21
        versionName = "0.4.0"
        testInstrumentationRunner = "com.torfilx.tv.HiltTestRunner"
        resourceConfigurations += setOf("en")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Ship the catalogue uncompressed.
        //
        // It is a 2.1 MB asset that was being DEFLATEd into the APK, and a *compressed* asset has to
        // be inflated through AssetManager's buffer, which on older Android is capped
        // (UNCOMPRESS_DATA_MAX, 1 MB) — squarely a Fire OS 5/6 concern, which is the floor this app
        // targets. Stored, the asset is read straight out of the APK with no inflate step and no cap,
        // and it also loads faster on a very slow CPU. The cost is APK size: the catalogue compresses
        // ~91%, so this adds about 2 MB.
        noCompress += "json"
    }

    signingConfigs {
        // v1 (JAR) signing is required by Android 6 and older — Fire OS 5 sticks reject a v2-only
        // APK with "There was a problem parsing the package".
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            // Populated from env for CI; falls back to the debug key for local sideloading.
            val storePath = providers.environmentVariable("TORFILX_KEYSTORE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("TORFILX_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TORFILX_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TORFILX_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8/minification is deliberately OFF. ART on Fire OS 5 (Android 5.1) miscompiles R8's
            // optimised dex and the process dies with a native SIGSEGV on a coroutine thread at
            // launch; the same build with minification off runs clean. Since the whole point is to
            // support the Fire OS 5 sticks, correctness wins over the smaller/obfuscated APK. The
            // proguard-rules.pro keep-rules are retained but inactive — see the note in that file.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Every Fire TV is 32-bit ARM (armeabi-v7a); arm64 is cheap forward-insurance for a
            // 64-bit Android TV box. The x86/x86_64 libtorrent binaries exist only for the emulator
            // and are ~13 MB of dead weight in a shipped APK, so keep them out of release. The debug
            // variant keeps all ABIs so it still runs on the x86 emulators used for testing.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

            signingConfig = if (providers.environmentVariable("TORFILX_KEYSTORE").isPresent) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // ChromeOsAbiSupport wants an x86_64 ABI so the app runs on ChromeOS; this is a Fire TV app
        // (32-bit ARM only), and shipping x86 is exactly the bloat #15 removed, so the check does
        // not apply.
        //
        // IconMissingDensityFolder wants drawable-mdpi/hdpi buckets; those are phone densities. Fire
        // TV renders at xhdpi (1080p) and xxhdpi/xxxhdpi (4K), which are the only buckets shipped, so
        // the "missing" folders are intentional. This check is folder-level and its baseline
        // fingerprint does not match across OSes (generated on Windows, CI runs on Linux), so it
        // leaked past the baseline and, under warningsAsErrors, failed the Linux CI lint. Disabling
        // it is the correct call for a TV-only app rather than shipping unused phone-density assets.
        disable += setOf(
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            "ObsoleteLintCustomCheck", "ChromeOsAbiSupport", "IconMissingDensityFolder",
        )
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.player)
    implementation(projects.core.torrent)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    implementation(projects.feature.home)
    implementation(projects.feature.library)
    implementation(projects.feature.details)
    implementation(projects.feature.search)
    implementation(projects.feature.player)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp.core)

    testImplementation(projects.core.testing)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.rules)
    kspAndroidTest(libs.hilt.compiler)
}
