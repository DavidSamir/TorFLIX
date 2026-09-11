package com.torfilx.core.catalogue.swarm

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CataloguePointer
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.Entry

class BencodingTest {

    @Before
    fun nativeLibrary() = NativeSupport.require()

    private val pointer = CataloguePointer(CataloguePointer.FORMAT, "0697bc07ebc5914085c2a3bce646509086bf6265", 42)

    @Test
    fun `a pointer survives bencoding through libtorrent`() {
        val bencoded = Bencoding.entryOf(pointer.toMap()).bencode()
        val decoded = Entry.bdecode(bencoded)

        assertThat(CataloguePointer.fromMap(Bencoding.dictionary(decoded.swig())!!)).isEqualTo(pointer)
    }

    @Test
    fun `the bencoded pointer is the compact dictionary the DHT expects`() {
        val bencoded = Bencoding.entryOf(pointer.toMap()).bencode().decodeToString()
        assertThat(bencoded).isEqualTo("d2:cvi42e2:ih40:0697bc07ebc5914085c2a3bce646509086bf62651:vi1ee")
    }

    @Test
    fun `anything that is not a dictionary reads as nothing`() {
        assertThat(Bencoding.dictionary(Entry(7L).swig())).isNull()
        assertThat(Bencoding.dictionary(Entry("text").swig())).isNull()
    }

    @Test
    fun `nested values read as null rather than failing`() {
        val nested = Entry.bdecode("d2:cvi3e4:listli1ee1:vi1ee".encodeToByteArray())
        val map = Bencoding.dictionary(nested.swig())!!
        assertThat(map["cv"]).isEqualTo(3L)
        assertThat(map).containsKey("list")
        assertThat(map["list"]).isNull()
    }

    @Test
    fun `only integers and strings can be bencoded into a pointer`() {
        assertThrows(IllegalArgumentException::class.java) { Bencoding.entryOf(mapOf("x" to 1.5)) }
    }
}
