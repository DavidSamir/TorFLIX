package com.torfilx.tv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.torfilx.core.ui.component.SharingConsentDialog

/**
 * The sharing question, asked at launch until it is accepted.
 *
 * Every title and every catalogue update travels over BitTorrent, so sharing is a condition of using
 * the app: the answers are to accept, with "Accept and continue" already focused, or to leave. "Exit"
 * and Back both close the app, and the question is put again next time it opens.
 *
 * Shown instead of the app rather than over it, so no screen behind it can take focus.
 */
@Composable
internal fun FirstRunSharingPrompt(onAccept: () -> Unit, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    SharingConsentDialog(
        title = "TORFILX shares while you watch",
        storageSummary = "Sharing is also how the catalogue stays current: new titles and episodes arrive " +
            "from the peer network, signed by the catalogue's publisher, with no server involved. Shared " +
            "titles never use more than half of the free space, and the oldest are cleared first.",
        acceptLabel = "Accept and continue",
        declineLabel = "Exit",
        onAccept = onAccept,
        onDecline = onExit,
    )
}
