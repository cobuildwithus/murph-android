package ai.withmurph.companion.ui.theme

import ai.withmurph.companion.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MurphColors {
    val Cream = Color(0xFFF5F0E8)
    val Card = Color(0xFFFFFCF6)
    val Slate = Color(0xFF2D3436)
    val SlateMuted = Color(0xFF736A58)
    val Sand = Color(0xFFD4C4A8)
    val Amber = Color(0xFFC4A882)
    val Sage = Color(0xFF7A8C6E)
    val SageDark = Color(0xFF5A6E32)
    val Ring = Color(0xFF5A6E3E)
    val Sienna = Color(0xFF8B5D3F)
    val BorderWarm = Color(0x40C4A882)
    val MutedSurface = Color(0x26C4A882)
    val MutedSurfaceOpaque = Color(0xFFF6EFE5)
    val SelectedSurface = Color(0xFFE9E6DC)
    val NavigationSurface = Color(0xF7FFFCF6)
}

val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
)

val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
)

private val colors = lightColorScheme(
    primary = MurphColors.SageDark,
    onPrimary = Color.White,
    primaryContainer = MurphColors.MutedSurface,
    onPrimaryContainer = MurphColors.Slate,
    secondary = MurphColors.Sage,
    background = MurphColors.Cream,
    onBackground = MurphColors.Slate,
    surface = MurphColors.Card,
    onSurface = MurphColors.Slate,
    surfaceVariant = MurphColors.MutedSurface,
    onSurfaceVariant = MurphColors.SlateMuted,
    outline = MurphColors.BorderWarm,
    error = MurphColors.Sienna,
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun MurphTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}
