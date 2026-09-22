plugins {
    id("torfilx.android.feature")
}

android {
    namespace = "com.torfilx.feature.details"
}

dependencies {
    testImplementation(testFixtures(projects.core.catalogue))
}
