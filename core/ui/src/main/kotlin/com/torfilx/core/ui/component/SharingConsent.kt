package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.ui.theme.TorfilxColors

/**
 * The sharing (seeding) consent gate.
 *
 * BitTorrent is not a download — while you watch, your device also *uploads* to other people. That
 * is a meaningful decision, so it is asked once, in plain language, with a real "no" that still
 * leaves the server playback path working. It is never pre-ticked and never implied by pressing
 * Play (plan.md — sharing section).
 */
@Composable
fun SharingConsentDialog(
    storageSummary: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
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
                .clip(RoundedCornerShape(12.dp))
                .background(TorfilxColors.Surface)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Share while you watch?",
                style = MaterialTheme.typography.headlineMedium,
                color = TorfilxColors.TextPrimary,
            )
            Text(
                text = "Streaming a title over BitTorrent also uploads it to other people, and your " +
                    "home IP address is visible to everyone else sharing that title. Only share what " +
                    "you have the right to redistribute.",
                style = MaterialTheme.typography.bodyMedium,
                color = TorfilxColors.TextSecondary,
            )
            Text(
                text = storageSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = TorfilxColors.TextSecondary,
            )
            Text(
                // Accurate since the app became torrent-only: there is no other source to fall back
                // on, so declining here means nothing plays until sharing is turned on again.
                text = "You can turn sharing off at any time in Settings. Every title here is played " +
                    "over BitTorrent, so playback stops working while sharing is off.",
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextTertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(text = "Enable sharing", onClick = onAccept, autoFocus = true)
                TvButton(text = "Not now", onClick = onDecline, primary = false)
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
