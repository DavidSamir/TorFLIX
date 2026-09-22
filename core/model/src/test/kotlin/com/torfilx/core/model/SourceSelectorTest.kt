package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceSelectorTest {

    private val fireStick4k = DeviceCapabilities(
        videoDecoders = listOf(
            VideoDecoderCapability("video/avc", 1920, 1080, 60),
            VideoDecoderCapability("video/hevc", 3840, 2160, 60, supportsHdr10 = true),
        ),
        audioCodecs = setOf("aac"),
        passthroughCodecs = setOf("ac3", "eac3"),
        maxAudioChannels = 6,
        displayHdrTypes = setOf(HdrType.HDR10),
        maxDisplayWidth = 3840,
        maxDisplayHeight = 2160,
        supportsTunneledPlayback = true,
    )

    private fun direct(
        id: String,
        codec: String = "hevc",
        width: Int = 3840,
        height: Int = 2160,
        audio: List<String> = listOf("eac3"),
        hdr: HdrType = HdrType.NONE,
        dvProfile: Int? = null,
        bitrate: Long = 20_000_000,
    ) = MediaSource(
        id = id,
        kind = SourceKind.DIRECT,
        url = "http://server/$id",
        videoCodec = codec,
        audioCodecs = audio,
        width = width,
        height = height,
        hdr = hdr,
        dolbyVisionProfile = dvProfile,
        bitrate = bitrate,
    )

    private fun hls(id: String, height: Int = 1080) = MediaSource(
        id = id,
        kind = SourceKind.HLS,
        url = "http://server/$id.m3u8",
        videoCodec = "h264",
        audioCodecs = listOf("aac"),
        width = height * 16 / 9,
        height = height,
        bitrate = 8_000_000,
    )

    @Test
    fun `direct play is preferred over transcoding`() {
        val result = SourceSelector.select(listOf(hls("t"), direct("d")), fireStick4k)
        assertThat(result.source?.id).isEqualTo("d")
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.DIRECT_PLAY)
    }

    @Test
    fun `undecodable 4K HEVC on a 1080p only device falls back to the transcode`() {
        val capped = fireStick4k.copy(
            videoDecoders = listOf(
                VideoDecoderCapability("video/avc", 1920, 1080, 60),
                VideoDecoderCapability("video/hevc", 1920, 1080, 60),
            ),
        )
        val result = SourceSelector.select(listOf(direct("d"), hls("t")), capped)
        assertThat(result.source?.id).isEqualTo("t")
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.TRANSCODE)
    }

    @Test
    fun `dolby vision profile 7 is never selected`() {
        val dv7 = direct("dv7", hdr = HdrType.DOLBY_VISION, dvProfile = 7)
        val result = SourceSelector.select(listOf(dv7, hls("t")), fireStick4k)
        assertThat(result.source?.id).isEqualTo("t")
    }

    @Test
    fun `hdr source is rejected when the display cannot show that hdr type`() {
        val sdrTv = fireStick4k.copy(displayHdrTypes = emptySet())
        val result = SourceSelector.select(listOf(direct("hdr", hdr = HdrType.HDR10)), sdrTv)
        assertThat(result.source).isNull()
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.NO_COMPATIBLE_SOURCE)
    }

    @Test
    fun `a source whose audio the device cannot handle is rejected for direct play`() {
        val dtsOnly = direct("dts", audio = listOf("dts"))
        val result = SourceSelector.select(listOf(dtsOnly, hls("t")), fireStick4k)
        assertThat(result.source?.id).isEqualTo("t")
    }

    @Test
    fun `passthrough codecs count as supported audio`() {
        val eac3 = direct("eac3src", audio = listOf("eac3"), width = 1920, height = 1080, codec = "h264")
        val result = SourceSelector.select(listOf(eac3), fireStick4k)
        assertThat(result.source?.id).isEqualTo("eac3src")
    }

    @Test
    fun `failed sources are excluded so the retry picks the transcode`() {
        val result = SourceSelector.select(
            sources = listOf(direct("d"), hls("t")),
            capabilities = fireStick4k,
            failedSourceIds = setOf("d"),
        )
        assertThat(result.source?.id).isEqualTo("t")
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.TRANSCODE)
    }

    @Test
    fun `when every source has failed the caller is told so explicitly`() {
        val result = SourceSelector.select(
            sources = listOf(direct("d"), hls("t")),
            capabilities = fireStick4k,
            failedSourceIds = setOf("d", "t"),
        )
        assertThat(result.source).isNull()
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.ALL_SOURCES_FAILED)
    }

    @Test
    fun `direct only preference never returns a transcode`() {
        val capped = fireStick4k.copy(
            videoDecoders = listOf(
                VideoDecoderCapability("video/avc", 1920, 1080, 60),
                VideoDecoderCapability("video/hevc", 1920, 1080, 60),
            ),
        )
        val result = SourceSelector.select(
            sources = listOf(direct("d"), hls("t")),
            capabilities = capped,
            preference = QualityPreference.DIRECT_ONLY,
        )
        assertThat(result.source).isNull()
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.NO_COMPATIBLE_SOURCE)
    }

    @Test
    fun `cap 1080p demotes but still plays a 4K only library item`() {
        val demoted = SourceSelector.select(
            sources = listOf(direct("uhd"), hls("hd", height = 1080)),
            capabilities = fireStick4k,
            preference = QualityPreference.CAP_1080P,
        )
        assertThat(demoted.source?.id).isEqualTo("hd")

        val onlyUhd = SourceSelector.select(
            sources = listOf(direct("uhd")),
            capabilities = fireStick4k,
            preference = QualityPreference.CAP_1080P,
        )
        assertThat(onlyUhd.source?.id).isEqualTo("uhd")
    }

    @Test
    fun `empty source list is reported distinctly from an incompatible one`() {
        assertThat(SourceSelector.select(emptyList(), fireStick4k).reason)
            .isEqualTo(SourceSelector.Reason.NO_SOURCES)
    }

    @Test
    fun `unknown codec name is not silently trusted`() {
        val weird = direct("weird", codec = "cinepak")
        assertThat(SourceSelector.canPlay(weird, fireStick4k)).isFalse()
    }

    @Test
    fun `source without codec metadata is attempted rather than blocked`() {
        val unknown = MediaSource(id = "u", kind = SourceKind.DIRECT, url = "http://x")
        assertThat(SourceSelector.canPlay(unknown, fireStick4k)).isTrue()
    }

    // --- Torrents: the only kind of source the catalogue actually has -------------------------------

    private val fireStick1080 = fireStick4k.copy(maxDisplayWidth = 1920, maxDisplayHeight = 1080)

    /** A catalogue magnet as the parser builds it: no codec, a height read from the quality label. */
    private fun torrent(id: String, height: Int?) = MediaSource(
        id = "torrent-$id",
        kind = SourceKind.TORRENT,
        url = "magnet:?xt=urn:btih:$id",
        magnetUri = "magnet:?xt=urn:btih:$id",
        height = height,
    )

    @Test
    fun `direct only still plays torrents, which are the file itself`() {
        val result = SourceSelector.select(
            sources = listOf(torrent("a", 720), torrent("b", 1080)),
            capabilities = fireStick1080,
            preference = QualityPreference.DIRECT_ONLY,
        )
        assertThat(result.source?.id).isEqualTo("torrent-b")
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.DIRECT_PLAY)
    }

    @Test
    fun `direct only still refuses the transcode when torrents are on offer too`() {
        val result = SourceSelector.select(
            sources = listOf(hls("t", height = 1080), torrent("a", 720)),
            capabilities = fireStick1080,
            preference = QualityPreference.DIRECT_ONLY,
        )
        assertThat(result.source?.id).isEqualTo("torrent-a")
    }

    @Test
    fun `a torrent counts as direct play, not as a transcode`() {
        val result = SourceSelector.select(listOf(torrent("a", 1080)), fireStick1080)
        assertThat(result.reason).isEqualTo(SourceSelector.Reason.DIRECT_PLAY)
    }

    @Test
    fun `auto never downloads a picture taller than the display when a smaller one exists`() {
        val result = SourceSelector.select(
            sources = listOf(torrent("uhd", 2160), torrent("hd", 1080), torrent("sd", 720)),
            capabilities = fireStick1080,
        )
        assertThat(result.source?.id).isEqualTo("torrent-hd")
    }

    @Test
    fun `auto takes the 2160p torrent on a 4K display`() {
        val result = SourceSelector.select(
            sources = listOf(torrent("hd", 1080), torrent("uhd", 2160)),
            capabilities = fireStick4k,
        )
        assertThat(result.source?.id).isEqualTo("torrent-uhd")
    }

    @Test
    fun `a torrent taller than the display still plays when it is the only one`() {
        val result = SourceSelector.select(listOf(torrent("uhd", 2160)), fireStick1080)
        assertThat(result.source?.id).isEqualTo("torrent-uhd")
    }

    @Test
    fun `a torrent of unknown quality ranks below a known one within the ceiling`() {
        val result = SourceSelector.select(
            sources = listOf(torrent("unknown", null), torrent("sd", 720)),
            capabilities = fireStick1080,
        )
        assertThat(result.source?.id).isEqualTo("torrent-sd")
    }

    @Test
    fun `an unknown display size imposes no ceiling`() {
        val unknownDisplay = fireStick4k.copy(maxDisplayWidth = 0, maxDisplayHeight = 0)
        assertThat(SourceSelector.heightCeiling(QualityPreference.AUTO, unknownDisplay)).isEqualTo(Int.MAX_VALUE)
        val result = SourceSelector.select(listOf(torrent("hd", 1080), torrent("uhd", 2160)), unknownDisplay)
        assertThat(result.source?.id).isEqualTo("torrent-uhd")
    }

    @Test
    fun `the ceiling is the display's short side, and cap 1080p never raises it`() {
        val sd = fireStick4k.copy(maxDisplayWidth = 1280, maxDisplayHeight = 720)
        assertThat(SourceSelector.heightCeiling(QualityPreference.AUTO, fireStick4k)).isEqualTo(2160)
        assertThat(SourceSelector.heightCeiling(QualityPreference.CAP_1080P, fireStick4k)).isEqualTo(1080)
        assertThat(SourceSelector.heightCeiling(QualityPreference.CAP_1080P, sd)).isEqualTo(720)
        val rotated = fireStick4k.copy(maxDisplayWidth = 2160, maxDisplayHeight = 3840)
        assertThat(SourceSelector.heightCeiling(QualityPreference.AUTO, rotated)).isEqualTo(2160)
    }

    private fun pack(id: String, height: Int) = torrent(id, height).copy(
        id = "pack-$id",
        fileSelection = FileSelection.Episode(EpisodeFileMatcher.Target(1, 1, 0, 10)),
    )

    @Test
    fun `at the same quality an episode's own torrent beats its season pack`() {
        val result = SourceSelector.select(listOf(pack("p", 1080), torrent("own", 1080)), fireStick1080)
        assertThat(result.source?.id).isEqualTo("torrent-own")
    }

    @Test
    fun `a season pack still wins on quality, within the display's ceiling`() {
        assertThat(SourceSelector.select(listOf(torrent("own", 720), pack("p", 1080)), fireStick1080).source?.id)
            .isEqualTo("pack-p")
        assertThat(SourceSelector.select(listOf(torrent("own", 1080), pack("p", 2160)), fireStick1080).source?.id)
            .isEqualTo("torrent-own")
    }

    @Test
    fun `portrait encoded video still matches a landscape decoder limit`() {
        val portrait = direct("p", codec = "hevc", width = 2160, height = 3840)
        assertThat(SourceSelector.canPlay(portrait, fireStick4k)).isTrue()
    }
}
