plugins {
    id("torfilx.android.library")
    id("torfilx.android.hilt")
}

android {
    namespace = "com.torfilx.core.player"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.okhttp.core)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.media3.test.utils)
    testImplementation(projects.core.testing)
    testImplementation(testFixtures(projects.core.catalogue))
}
