plugins {
    id("torfilx.jvm.library")
    id("torfilx.libtorrent.desktop")
}

// The libtorrent side of catalogue distribution: reading and publishing the DHT pointer, downloading
// and seeding release torrents, building them, and keeping the DHT routing table across restarts.
//
// Pure JVM so the app's engine and the publisher tool run the same code, and so it can be tested
// against a real libtorrent network on a desktop JVM. The Android app supplies its own native
// libraries through :core:torrent; the desktop natives wired in by torfilx.libtorrent.desktop are for
// this module's tests only.
dependencies {
    api(projects.core.catalogue)
    implementation(libs.libtorrent4j)

    testImplementation(testFixtures(projects.core.catalogue))
    testImplementation(libs.kotlinx.coroutines.test)
}
