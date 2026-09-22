plugins {
    id("torfilx.android.feature")
}

android {
    namespace = "com.torfilx.feature.library"
}

dependencies {
    testImplementation(testFixtures(projects.core.catalogue))
}
