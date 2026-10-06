plugins {
    id("torfilx.android.library")
    id("torfilx.android.hilt")
}

android {
    namespace = "com.torfilx.core.torrent"

    defaultConfig {
        // Both ARM ABIs cover TV hardware; x86/x86_64 exercise old and modern Android emulators.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.catalogue)
    implementation(projects.core.catalogueSwarm)
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.libtorrent4j.android.x86.v64)
    implementation(libs.libtorrent4j.android.x86)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
