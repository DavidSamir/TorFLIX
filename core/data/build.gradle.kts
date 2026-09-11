plugins {
    id("torfilx.android.library")
    id("torfilx.android.hilt")
    id("torfilx.android.room")
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The catalogue publisher keys this build trusts: comma-separated Ed25519 public keys, 64 hex
 * characters each.
 *
 * Only a catalogue release signed by one of these keys is ever installed. Blank means the build trusts
 * no publisher and keeps its bundled catalogue. A developer build can trust a test publisher instead
 * with `-Ptorfilx.cataloguePublisherKeys=<hex>`. docs/CATALOGUE_P2P.md covers key rotation.
 */
val productionCataloguePublisherKeys = "3c04a3374ae5bb233afd2158c51d5d010076a43a6fdd761755f7d2ec0e04ca51"

val cataloguePublisherKeys: String = providers.gradleProperty("torfilx.cataloguePublisherKeys")
    .orElse(productionCataloguePublisherKeys)
    .get()
    .trim()

require(
    cataloguePublisherKeys.isEmpty() ||
        cataloguePublisherKeys.split(',').all { it.trim().matches(Regex("^[0-9a-fA-F]{64}$")) },
) {
    "torfilx.cataloguePublisherKeys must be comma-separated 64-character hex Ed25519 public keys"
}

android {
    namespace = "com.torfilx.core.data"
    buildFeatures {
        androidResources = true
        buildConfig = true
    }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CATALOGUE_PUBLISHER_KEYS", "\"$cataloguePublisherKeys\"")
    }
    // The exported Room schemas are the fixtures the migration test replays; make them available to
    // the instrumented test as assets.
    sourceSets {
        named("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.torrent)
    api(projects.core.catalogue)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(projects.core.testing)
    testImplementation(testFixtures(projects.core.catalogue))
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
