package com.torfilx.tv

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.Logger
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.catalog.CatalogUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.HiltAndroidApp
import com.torfilx.tv.net.trustModernCertificateAuthorities
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

private const val TAG = "App"

/**
 * Application entry point.
 *
 * Image cache sizes are chosen for a 1.5 GB Fire Stick: 25% of the heap in memory and 256 MB on
 * disk, with `RGB_565`-friendly inexact sizing done at the call sites (plan.md §4, §9).
 */
@HiltAndroidApp
class TorfilxApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var catalog: Catalog

    @Inject
    lateinit var catalogUpdater: dagger.Lazy<CatalogUpdater>

    @Inject
    lateinit var torrentEngine: dagger.Lazy<com.torfilx.core.torrent.TorrentEngine>

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var crashStore: com.torfilx.core.common.log.CrashStore

    override fun onCreate() {
        super.onCreate()
        TorfilxLog.debugEnabled = BuildConfig.DEBUG
        TorfilxLog.i(TAG, "TORFILX ${BuildConfig.VERSION_NAME} starting")

        // Installed first: a failure anywhere after this point restarts the app instead of showing
        // the system.s "keeps stopping" dialog on the TV, and is recorded durably for the log export.
        installCrashGuard(crashStore)

        // If the app runs cleanly for a while, a past crash streak no longer counts against the
        // restart-loop guard.
        applicationScope.launch {
            kotlinx.coroutines.delay(STABILITY_MS)
            markRunStable()
        }

        // Parse and index the catalogue before the first screen asks for it, off the main thread:
        // with a few thousand entries this is tens of milliseconds that must not land on a frame.
        applicationScope.launch {
            catalog.preload()
            // Follows the torrent session to keep the catalogue current from the peer network. It never
            // starts a session itself, so nothing reaches the network before the viewer consents to sharing.
            catalogUpdater.get().start()
        }

        // Say plainly, once per launch, whether the BitTorrent engine can run on this device.
        // Without it the only symptom is a message at the end of the play flow, long after the fact,
        // and it is the first thing worth knowing when an unfamiliar Fire TV model misbehaves.
        applicationScope.launch { reportTorrentEngineSupport() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Registered by hand rather than left to Coil's ServiceLoader discovery: the loader
            // reads META-INF/services entries, and when they go missing every poster silently falls
            // back to its placeholder with nothing in the log to say why.
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient })) }
            // Poster failures are otherwise invisible — a blank card looks identical whether the
            // host is unreachable, the TLS handshake failed, or the URL is simply wrong.
            .logger(
                object : Logger {
                    override var minLevel: Logger.Level = Logger.Level.Warn

                    override fun log(tag: String, level: Logger.Level, message: String?, throwable: Throwable?) {
                        if (level < minLevel) return
                        TorfilxLog.w("Image/$tag", message ?: throwable?.message.orEmpty(), throwable)
                    }
                },
            )
            // Posters decode as RGB_565: half the memory of ARGB_8888, and the banding is invisible
            // on a TV. On a 1.5 GB stick that is the difference between a smooth row and a GC pause
            // every few cards.
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, memoryCachePercent())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under memory pressure the image cache goes first; the player is never touched here.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            TorfilxLog.i(TAG, "Trimming image memory cache (level=$level)")
            SingletonImageLoader.get(this).memoryCache?.clear()
        }
    }

    /**
     * Probes the native BitTorrent engine and records the outcome.
     *
     * Runs off the main thread because the first call is what loads `libtorrent4j.so`, and reports
     * the device's ABI alongside the verdict: when a library fails to load it is almost always
     * because the build for *that* architecture wants a newer libc than the device has.
     */
    private fun reportTorrentEngineSupport() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI
        val available = runCatching { torrentEngine.get().isAvailable() }.getOrDefault(false)
        if (available) {
            TorfilxLog.i(TAG, "Torrent engine available (abi=$abi, api=${Build.VERSION.SDK_INT})")
        } else {
            TorfilxLog.e(
                TAG,
                "Torrent engine UNAVAILABLE (abi=$abi, api=${Build.VERSION.SDK_INT}) — nothing will play",
            )
        }
    }

    /**
     * The client posters are fetched with.
     *
     * Built lazily so the CA bundle is parsed on whichever background thread first needs an image,
     * never during startup on the main thread.
     */
    private val imageHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .trustModernCertificateAuthorities(this)
            .build()
    }

    /** Low-RAM devices (1.5 GB Fire Sticks) get a smaller share of a much smaller heap. */
    private fun memoryCachePercent(): Double {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val lowRam = activityManager?.isLowRamDevice == true ||
            (activityManager?.memoryClass ?: 0) <= LOW_MEMORY_CLASS_MB
        return if (lowRam) LOW_RAM_CACHE_PERCENT else MEMORY_CACHE_PERCENT
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val LOW_RAM_CACHE_PERCENT = 0.15
        const val LOW_MEMORY_CLASS_MB = 128
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
