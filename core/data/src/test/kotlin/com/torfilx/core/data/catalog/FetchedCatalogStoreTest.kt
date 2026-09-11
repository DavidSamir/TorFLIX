package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FetchedCatalogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FetchedCatalogStore

    @Before
    fun setUp() {
        store = FetchedCatalogStore { File(tmp.root, "catalogue") }
    }

    private fun downloaded(version: Long): File {
        TestCatalogues.writeRelease(store.prepareDownload(version), version)
        return store.releaseRootFor(version)
    }

    @Test
    fun `an empty store has nothing installed and nothing to clean`() {
        assertThat(store.installed()).isNull()
        assertThat(store.deleteAllExceptInstalled()).isEqualTo(0)
        assertThat(store.torrentBytes(3)).isNull()
    }

    @Test
    fun `a download directory always starts empty, even after a failed attempt`() {
        File(store.prepareDownload(3), "leftover.part").writeText("partial")

        val again = store.prepareDownload(3)

        assertThat(again).isEqualTo(store.saveDirFor(3))
        assertThat(again.list()).isEmpty()
    }

    @Test
    fun `installing records the release and keeps its torrent`() {
        downloaded(4)

        val installed = store.install(4, "ab".repeat(20), byteArrayOf(1, 2, 3), installedAtMs = 99)

        assertThat(installed).isEqualTo(FetchedCatalogStore.Installed(4, "ab".repeat(20), 99))
        assertThat(store.installed()).isEqualTo(installed)
        assertThat(store.torrentBytes(4)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(tmp.root, "catalogue/current.json.tmp").exists()).isFalse()
    }

    @Test
    fun `a release without files cannot be installed`() {
        store.prepareDownload(4)
        assertThrows(IllegalStateException::class.java) { store.install(4, "ab".repeat(20), byteArrayOf(1), 1) }
        assertThat(store.installed()).isNull()
    }

    @Test
    fun `installing a newer release replaces the record, and the old files go only when asked`() {
        downloaded(4)
        store.install(4, "aa".repeat(20), byteArrayOf(4), 1)
        downloaded(5)
        store.install(5, "bb".repeat(20), byteArrayOf(5), 2)

        assertThat(store.installed()?.version).isEqualTo(5)
        assertThat(store.saveDirFor(4).exists()).isTrue()

        store.deleteRelease(4)
        assertThat(store.saveDirFor(4).exists()).isFalse()
        assertThat(store.saveDirFor(5).exists()).isTrue()
    }

    @Test
    fun `clean-up removes every release except the installed one`() {
        downloaded(6)
        store.install(6, "cc".repeat(20), byteArrayOf(6), 1)
        File(store.prepareDownload(7), "partial").writeText("x")
        downloaded(8)

        assertThat(store.deleteAllExceptInstalled()).isEqualTo(2)
        assertThat(store.saveDirFor(6).exists()).isTrue()
        assertThat(store.saveDirFor(7).exists()).isFalse()
        assertThat(store.saveDirFor(8).exists()).isFalse()

        store.deleteRelease(6)
        assertThat(store.saveDirFor(6).exists()).isTrue()
    }

    @Test
    fun `downloading over the installed release is refused`() {
        downloaded(6)
        store.install(6, "cc".repeat(20), byteArrayOf(6), 1)
        assertThrows(IllegalStateException::class.java) { store.prepareDownload(6) }
        assertThat(store.releaseRootFor(6).isDirectory).isTrue()
    }

    @Test
    fun `uninstalling removes the record and the files`() {
        downloaded(6)
        store.install(6, "cc".repeat(20), byteArrayOf(6), 1)

        store.uninstall()

        assertThat(store.installed()).isNull()
        assertThat(store.saveDirFor(6).exists()).isFalse()
    }

    @Test
    fun `a record whose files are gone, or that is unreadable, means nothing is installed`() {
        downloaded(6)
        store.install(6, "cc".repeat(20), byteArrayOf(6), 1)
        store.saveDirFor(6).deleteRecursively()
        assertThat(store.installed()).isNull()

        File(tmp.root, "catalogue/current.json").writeText("{ this is not json")
        assertThat(store.installed()).isNull()
    }
}
