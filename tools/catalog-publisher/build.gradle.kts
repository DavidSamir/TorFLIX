plugins {
    id("torfilx.jvm.library")
    id("torfilx.libtorrent.desktop")
    application
    alias(libs.plugins.kotlin.serialization)
}

// The maintainer's side of the peer-to-peer catalogue: generate the publisher key, build and sign a
// release, check it, publish its pointer to the DHT and seed it, and fetch it back the way a
// television would. Runs on a desktop JVM; the libtorrent desktop native library is wired in by
// torfilx.libtorrent.desktop. Nothing in the app depends on this module.
//
//   ./gradlew :tools:catalog-publisher:run --args="help"

application {
    mainClass.set("com.torfilx.tools.catalog.CliKt")
    applicationName = "catalog-publisher"
}

// Paths given to a command are relative to where Gradle was launched (the repository root), not to
// this module's directory.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// LauncherTest loads this class and checks its main method, so renaming the file that holds main
// fails a test instead of shipping a launcher that cannot start.
tasks.withType<Test>().configureEach {
    systemProperty("torfilx.catalogPublisher.mainClass", application.mainClass.get())
}

dependencies {
    implementation(projects.core.catalogue)
    implementation(projects.core.catalogueSwarm)
    implementation(libs.libtorrent4j)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Bundled so an installed copy (installDist) can load libtorrent without Gradle; see DesktopNatives.
    runtimeOnly(libs.libtorrent4j.windows)
    runtimeOnly(libs.libtorrent4j.linux)
    runtimeOnly(libs.libtorrent4j.macosx)

    testImplementation(testFixtures(projects.core.catalogue))
    testImplementation(libs.kotlinx.coroutines.test)
}
