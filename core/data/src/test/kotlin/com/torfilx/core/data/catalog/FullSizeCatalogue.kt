package com.torfilx.core.data.catalog

import java.io.File

/** Catalogue files the parser tests share. Paths are relative to the module, as Gradle runs tests there. */
internal object FullSizeCatalogue {

    /**
     * Two thousand generated films with well-formed magnets: the size a real catalogue release reaches,
     * for the tests about scale, speed and chopped-up streams. The bundled catalogue is too small for
     * those since the APK ships a public-domain release.
     */
    val FILE = File("../../catalogs/catalog-2000-synthetic.json")
}
