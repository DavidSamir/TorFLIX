plugins {
    id("torfilx.android.feature")
}

android {
    namespace = "com.torfilx.feature.home"
}

dependencies {
    testImplementation(testFixtures(projects.core.catalogue))
}
