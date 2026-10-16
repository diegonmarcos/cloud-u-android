package com.diegonmarcos.mediacenter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.diegonmarcos.mediacenter.R

val GoogleSansFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold),
    Font(R.font.google_sans_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/**
 * Material's roles carry a fixed lineHeight (bodyLarge 24.sp) and letterSpacing that every Text inherits, so dense
 * rows swell to 24.sp lines (the "huge padding"). Sizes and weights stay Material's; the line and tracking are the
 * font's own, like a View TextView.
 */
private fun TextStyle.dense(): TextStyle =
    copy(lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified, lineHeightStyle = null)

private fun Typography.dense(): Typography = copy(
    displayLarge = displayLarge.dense(), displayMedium = displayMedium.dense(), displaySmall = displaySmall.dense(),
    headlineLarge = headlineLarge.dense(), headlineMedium = headlineMedium.dense(), headlineSmall = headlineSmall.dense(),
    titleLarge = titleLarge.dense(), titleMedium = titleMedium.dense(), titleSmall = titleSmall.dense(),
    bodyLarge = bodyLarge.dense(), bodyMedium = bodyMedium.dense(), bodySmall = bodySmall.dense(),
    labelLarge = labelLarge.dense(), labelMedium = labelMedium.dense(), labelSmall = labelSmall.dense(),
)

private val defaultTypography = Typography().dense()

val Typography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = GoogleSansFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = GoogleSansFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = GoogleSansFontFamily),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = GoogleSansFontFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = GoogleSansFontFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = GoogleSansFontFamily),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = GoogleSansFontFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = GoogleSansFontFamily),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = GoogleSansFontFamily),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = GoogleSansFontFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = GoogleSansFontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = GoogleSansFontFamily),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = GoogleSansFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = GoogleSansFontFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = GoogleSansFontFamily),
)

val SystemTypography = Typography().dense()