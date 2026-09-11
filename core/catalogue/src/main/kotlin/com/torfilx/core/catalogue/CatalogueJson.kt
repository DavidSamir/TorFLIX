package com.torfilx.core.catalogue

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** The JSON dialects the catalogue format uses, defined once so the app and the tool cannot drift. */
@OptIn(ExperimentalSerializationApi::class)
object CatalogueJson {

    /**
     * Catalogue content. Lenient, exactly as the bundled asset has always been read: an unknown field
     * or a number where a string was expected must not cost the library a title.
     */
    val content: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /**
     * Manifests. Strict about types, because a signed manifest is machine-written and a malformed one
     * is a broken release rather than a hand-editing slip. Unknown fields are tolerated so a later
     * revision of the same schema can add one without breaking apps already in the field.
     */
    val manifest: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** How the publisher writes `catalog.json`: the same two-space layout as the hand-maintained file. */
    val catalogWriter: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
        encodeDefaults = false
    }
}
