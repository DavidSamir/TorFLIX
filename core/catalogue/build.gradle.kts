plugins {
    id("torfilx.jvm.library")
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
}

// The catalogue release format and its trust rules, shared by the app and the publisher tool.
//
// Pure JVM on purpose. The tool that signs a release and the app that accepts it must run the same
// verification code, and a module both can depend on is the only way to guarantee that. Nothing here
// touches Android or libtorrent, so every rule is unit-tested on a plain JVM.
dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.eddsa)

    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
