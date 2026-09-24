package com.torfilx.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.torfilx.core.ui.R

/**
 * The faces: Fraunces, a soft old-style serif, for everything that is read; Inter, in spaced capitals,
 * for everything that labels.
 *
 * Static cuts subset to Latin (res/font, OFL; licences in assets/licenses), because variable fonts
 * need Android 8 and Fire OS 5 is Android 5.1. A glyph outside the subset falls back to the system
 * font. About 280 KB in all.
 */
object TorfilxFonts {
    /**
     * A face that fails to load falls back to the system font instead of throwing. On Android 5 to 7 the
     * font is copied into the cache directory to be loaded, and with the storage full that copy fails:
     * the default, blocking strategy then threw at the first line of text, on every launch, until
     * something freed space. Reading the text in another face is better than not opening at all.
     */
    private val LOCAL = FontLoadingStrategy.OptionalLocal

    /** Fraunces Light at its largest optical size: hairline contrast that only works big. Titles. */
    val Display = FontFamily(Font(R.font.fraunces_display, FontWeight.Light, loadingStrategy = LOCAL))

    /** Fraunces at reading sizes: roman for reading text and headings, light italic for decks and captions. */
    val Serif = FontFamily(
        Font(R.font.fraunces_regular, FontWeight.Normal, loadingStrategy = LOCAL),
        Font(R.font.fraunces_light_italic, FontWeight.Light, FontStyle.Italic, loadingStrategy = LOCAL),
    )

    /** Inter: labels, navigation, metadata and small print. */
    val Sans = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal, loadingStrategy = LOCAL),
        Font(R.font.inter_semibold, FontWeight.SemiBold, loadingStrategy = LOCAL),
    )
}

/**
 * The editorial styles, named for what they set, not for how big they are.
 *
 * Sizes are for a 10-foot screen (plan.md §4): body copy at 18 sp or more, and nothing under 14 sp
 * except [MetaCaps], whose 12 sp capitals stand as tall as the x-height of 16 sp text.
 *
 * Styles that end in `Caps` expect upper-case text: set them with
 * [CapsText][com.torfilx.core.ui.component.CapsText], which upper-cases once, since Compose has no
 * text-transform.
 */
object TorfilxType {
    /** The italic wordmark in the masthead. */
    val Masthead = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Light,
        fontStyle = FontStyle.Italic,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.01).em,
    )

    /** A feature's title: the hero, the details spread. */
    val Display = TextStyle(
        fontFamily = TorfilxFonts.Display,
        fontWeight = FontWeight.Light,
        fontSize = 60.sp,
        lineHeight = 62.sp,
        letterSpacing = (-0.025).em,
    )

    /** [Display] for a title too long to set that large. */
    val DisplayCompact = Display.copy(fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em)

    /** [Display], or [DisplayCompact] for a title too long to fit two lines at full size. */
    fun displayFor(title: String): TextStyle = if (title.length > DISPLAY_MAX_CHARS) DisplayCompact else Display

    private const val DISPLAY_MAX_CHARS = 16

    /** The display face at a size for a dialog or a full-screen message. */
    val Headline = Display.copy(fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.015).em)

    /** The italic line under a title that says what it is: genres, seasons. */
    val Deck = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Light,
        fontStyle = FontStyle.Italic,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    )

    /** Reading text: overviews and explanations. */
    val Reading = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 29.sp,
    )

    /** The spaced line above a title: "THE FEATURE · AMC, 2008". */
    val KickerCaps = TextStyle(
        fontFamily = TorfilxFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.28.em,
    )

    /** Actions, navigation and chips. */
    val LabelCaps = TextStyle(
        fontFamily = TorfilxFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.em,
    )

    /** Counts, runtimes, years and the facts table. Tracked tighter, so "2015 · 6 SEASONS" fits a poster. */
    val MetaCaps = TextStyle(
        fontFamily = TorfilxFonts.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.em,
    )

    /** A section's title, after its numeral. */
    val SectionTitle = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.01).em,
    )

    /** A section's numeral, "iv.", and an episode's number. */
    val SectionNumeral = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Light,
        fontStyle = FontStyle.Italic,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )

    /** A card's caption title. */
    val CardTitle = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Light,
        fontStyle = FontStyle.Italic,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    )

    /** The big italic "01" that overlaps a poster in a numbered row. */
    val Numeral = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Light,
        fontStyle = FontStyle.Italic,
        fontSize = 60.sp,
        lineHeight = 60.sp,
    )

    /** The italic caption under a plate. */
    val Caption = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Light,
        fontStyle = FontStyle.Italic,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    )

    /** An episode's name in the list. */
    val ListTitle = TextStyle(
        fontFamily = TorfilxFonts.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 28.sp,
    )

    /** Small sans text: an episode's summary, hints. */
    val Small = TextStyle(
        fontFamily = TorfilxFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
}

/**
 * The Material slots, for everything that asks `MaterialTheme.typography` (Settings, the player,
 * dialogs): headings and reading text in Fraunces, labels in Inter.
 */
internal val TorfilxTypography = Typography(
    displayLarge = TorfilxType.Display.copy(fontSize = 56.sp, lineHeight = 60.sp),
    displayMedium = TorfilxType.DisplayCompact,
    displaySmall = TorfilxType.Headline,
    headlineLarge = serif(32, 40),
    headlineMedium = serif(28, 36),
    headlineSmall = serif(24, 32),
    titleLarge = serif(24, 32),
    titleMedium = serif(20, 28),
    titleSmall = serif(18, 26),
    bodyLarge = serif(20, 32),
    bodyMedium = serif(18, 28),
    bodySmall = sans(14, 20),
    labelLarge = sans(16, 22),
    labelMedium = sans(14, 20),
    labelSmall = TorfilxType.MetaCaps,
)

private fun serif(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = TorfilxFonts.Serif,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

private fun sans(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = TorfilxFonts.Sans,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)
