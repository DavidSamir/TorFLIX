package com.torfilx.core.data.catalog

import android.content.Context
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The publisher keys this build trusts, from `BuildConfig.CATALOGUE_PUBLISHER_KEYS`.
 *
 * A list so the key can be rotated: ship a build that trusts both keys, publish under the new one,
 * then drop the old one in a later build.
 */
class CataloguePublisherKeys(val publicKeys: List<ByteArray>) {

    val isConfigured: Boolean get() = publicKeys.isNotEmpty()

    companion object {
        /** Parses comma-separated 64-character hex keys. Blank means no key: updates stay off. */
        fun parse(commaSeparatedHex: String): CataloguePublisherKeys = CataloguePublisherKeys(
            commaSeparatedHex.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { hex ->
                    val bytes = Hex.decodeOrNull(hex)
                    require(bytes != null && bytes.size == Ed25519Keys.PUBLIC_KEY_BYTES) {
                        "A catalogue publisher key is ${Ed25519Keys.PUBLIC_KEY_BYTES * 2} hex characters: \"$hex\""
                    }
                    bytes
                },
        )
    }
}

/** The app's own build number, which a release can require a minimum of. */
interface AppVersionProvider {
    val versionCode: Int
}

@Singleton
class PackageAppVersionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppVersionProvider {
    override val versionCode: Int by lazy {
        runCatching {
            // versionCode is deprecated from API 28 in favour of longVersionCode, which does not exist on
            // the API 22 floor; this app's versionCode never approaches the int limit.
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
    }
}

/** The settings and history the catalogue updater reads and writes. Implemented by the settings store. */
interface CatalogueUpdatePrefs {

    /** Whether the app looks for, and shares, catalogue releases on its own. Default on. */
    val catalogUpdatesEnabled: Flow<Boolean>

    val catalogueUpdateRecord: Flow<CatalogueUpdateRecord>

    /** What a check needs to know before it touches the network. */
    suspend fun catalogueUpdateSettings(): CatalogueUpdateSettings

    suspend fun recordCatalogueCheck(atMs: Long, result: String, successful: Boolean)

    /** Remembers a release that failed, so it is not downloaded again by this build of the app. */
    suspend fun recordRejectedCatalogue(version: Long, appVersionCode: Int)

    suspend fun rejectedCatalogue(): RejectedCatalogue?
}

data class CatalogueUpdateSettings(
    val updatesEnabled: Boolean,
    val sharingConsent: Boolean,
    val useDht: Boolean,
)

data class CatalogueUpdateRecord(
    val lastCheckMs: Long? = null,
    /** `up_to_date:<v>`, `updated:<v>` or `failed:<REASON>`. */
    val lastResult: String? = null,
    val lastSuccessMs: Long? = null,
)

/** A release refused by one build of the app; a different build tries it again. */
data class RejectedCatalogue(val version: Long, val appVersionCode: Int)

/** Brings the peer-network session up for a manual check, if the viewer's consent allows it. */
interface CatalogueSessionGate {
    /** @return true when the session is running afterwards. */
    suspend fun ensureRunning(): Boolean
}
