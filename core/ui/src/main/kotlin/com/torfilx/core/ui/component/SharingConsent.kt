package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType

/**
 * The sharing (seeding) consent gate.
 *
 * BitTorrent is not a download — while you watch, your device also *uploads* to other people. That
 * is said in plain language before anything plays, and never implied by pressing Play. Every title
 * plays over BitTorrent, so sharing is a condition of using the app: at launch the only other answer
 * is to leave it ([declineLabel] "Exit").
 */
@Composable
fun SharingConsentDialog(
    storageSummary: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Share while you watch?",
    acceptLabel: String = "Enable sharing",
    declineLabel: String = "Not now",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .panel(LocalTorfilxDimens.current.hairline),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Kicker(text = "Before you watch")
            Text(
                text = title,
                style = TorfilxType.Headline,
                color = TorfilxColors.TextPrimary,
            )
            Text(
                text = "Streaming a title over BitTorrent also uploads it to other people, and your " +
                    "home IP address is visible to everyone else sharing that title. Only share what " +
                    "you have the right to redistribute.",
                style = TorfilxType.Reading,
                color = TorfilxColors.TextSecondary,
            )
            Text(
                text = storageSummary,
                style = TorfilxType.Reading,
                color = TorfilxColors.TextSecondary,
            )
            Text(
                // The app is torrent-only: there is no other source to fall back on.
                text = "Every title here plays over BitTorrent, so sharing is needed to watch anything.",
                style = TorfilxType.Caption,
                color = TorfilxColors.TextTertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                TvButton(text = acceptLabel, onClick = onAccept, autoFocus = true)
                TvButton(text = declineLabel, onClick = onDecline, primary = false)
            }
        }
    }
}

/** Formats bytes for the consent copy and the Settings screen. */
fun formatBytes(bytes: Long): String {
    val gb = 1024.0 * 1024 * 1024
    val mb = 1024.0 * 1024
    val kb = 1024.0
    return when {
        bytes >= gb -> String.format(java.util.Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(java.util.Locale.US, "%.0f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
