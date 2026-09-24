package com.torfilx.core.ui.theme

/**
 * What each colour is for (plan.md §4). The inks themselves live in [Palette].
 *
 * A plain object rather than a CompositionLocal: the app has one look and never switches it, so a
 * colour is a static field read, with nothing for Compose to track.
 */
object TorfilxColors {
    // The page and the panels laid on it.
    val Background = Palette.Ink
    val SurfaceLow = Palette.InkRaised
    val Surface = Palette.InkRaised
    val SurfaceHigh = Palette.InkHigh
    val SurfaceHighest = Palette.InkHighest

    // Headlines and focused text; reading text and captions; labels, metadata and hints.
    val TextPrimary = Palette.Ivory
    val TextSecondary = Palette.IvorySoft
    val TextTertiary = Palette.Dim
    val TextDisabled = Palette.Faint

    /**
     * The accent is the ivory itself: selection, progress and focus are drawn in the same ink as the
     * headlines, never in a brand colour.
     */
    val Accent = Palette.Ivory
    val TextOnAccent = Palette.Ink

    /** Hairline rules between sections, facts and list rows. */
    val Rule = Palette.IvoryRule

    /** The solid edge of a focused control, and a focused underline. */
    val Focus = Palette.Ivory

    /** The thin frame that stands off a focused poster. */
    val FocusFrame = Palette.IvoryFrame

    /** The faint wash behind a focused list row. */
    val FocusWash = Palette.IvoryWash

    /** Row numbers on posters ("01", "02"), before the poster is focused. */
    val Numeral = Palette.IvoryNumeral

    val Success = Palette.Sage
    val Warning = Palette.Ochre
    val Error = Palette.Terracotta

    val ScrimStrong = Palette.InkScrim
    val ScrimSoft = Palette.InkVeil
    val Transparent = Palette.Clear

    /** Behind video: true black, so letterbox bars disappear into the TV's own black. */
    val VideoBackground = Palette.Black

    val ProgressTrack = Palette.IvoryTrack
    val ProgressFill = Accent
}
