package com.torfilx.core.catalogue.release

/** Why a catalogue release was refused. Each value is a distinct failure worth its own log line. */
enum class CatalogueRejectReason {
    /** This build trusts no publisher key, so no release can ever be accepted. */
    NO_TRUSTED_KEYS,

    /** A required release file is absent. */
    MISSING_FILE,

    /** The manifest's signature is malformed, or was not made by a trusted key. */
    BAD_SIGNATURE,

    /** The manifest is signed but unreadable, or carries impossible values. */
    BAD_MANIFEST,

    /** The release uses a format this build does not understand. */
    SCHEMA_UNSUPPORTED,

    /** The publisher marked the release as needing a newer app build. */
    NEEDS_NEWER_APP,

    /** The release is larger than this build is prepared to load. */
    SIZE_CAP,

    /** The compressed catalogue's size differs from the signed manifest. */
    SIZE_MISMATCH,

    /** The compressed catalogue's hash differs from the signed manifest. */
    SHA256_MISMATCH,

    /** The compressed catalogue is not valid gzip. */
    GZIP_INVALID,

    /** The catalogue decompresses to a different size than the signed manifest declares. */
    JSON_SIZE_MISMATCH,

    /** The number of titles disagrees with the signed manifest. */
    TITLE_COUNT_MISMATCH,

    /** The catalogue does not decode, or breaks a publishing rule about titles or ids. */
    INVALID_ENTRIES,

    /** The release verified, but the app could not turn every entry into a title. */
    INCOMPLETE,

    /** The DHT pointer and the release it names disagree about the version. */
    POINTER_MISMATCH,

    /** The DHT pointer is in a format this build does not understand. */
    UNSUPPORTED_POINTER,
}
