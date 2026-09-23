package com.torfilx.tv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.torfilx.core.ui.component.SharingConsentDialog

/**
 * The sharing question, asked once, the first time the app opens.
 *
 * Every title and every catalogue update travels over BitTorrent, so until the viewer answers nothing
 * plays and the catalogue never updates. Asking here, with "Enable sharing" already focused, turns that
 * into one OK press on the first launch instead of a trip into Settings. It is still a question: "Not
 * now" or Back means no, the app opens without sharing, and Play asks again.
 *
 * Shown instead of the app rather than over it, so no screen behind it can take focus.
 */
@Composable
internal fun FirstRunSharingPrompt(onAnswer: (consented: Boolean) -> Unit) {
    BackHandler { onAnswer(false) }
    SharingConsentDialog(
        storageSummary = "Sharing is also how the catalogue stays current: new titles and episodes arrive " +
            "from the peer network, signed by the catalogue's publisher, with no server involved. Shared " +
            "titles never use more than half of the free space, and the oldest are cleared first.",
        onAccept = { onAnswer(true) },
        onDecline = { onAnswer(false) },
    )
}
