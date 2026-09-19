package ai.withmurph.companion.ui.theme

import ai.withmurph.companion.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class MurphPalette(
    val Cream: Color = Color(0xFFF5F0E8),
    val Card: Color = Color(0xFFFFFCF6),
    val Slate: Color = Color(0xFF2D3436),
    val SlateMuted: Color = Color(0xFF736A58),
    val Sand: Color = Color(0xFFD4C4A8),
    val Amber: Color = Color(0xFFC4A882),
    val Sage: Color = Color(0xFF7A8C6E),
    val SageDark: Color = Color(0xFF5A6E32),
    val Ring: Color = Color(0xFF5A6E3E),
    val Sienna: Color = Color(0xFF8B5D3F),
    val BorderWarm: Color = Color(0x40C4A882),
    val MutedSurface: Color = Color(0x26C4A882),
    val MutedSurfaceOpaque: Color = Color(0xFFF6EFE5),
    val SelectedSurface: Color = Color(0xFFE9E6DC),
    val NavigationSurface: Color = Color(0xF7FFFCF6),
    val OnPrimary: Color = Color(0xFFFFFFFF),
)

private val lightPalette = MurphPalette()
private val darkPalette = MurphPalette(
    Cream = Color(0xFF211F1B),
    Card = Color(0xFF2D2A24),
    Slate = Color(0xFFF0EBE2),
    SlateMuted = Color(0xFFB8AF9E),
    Sand = Color(0xFF443D31),
    Amber = Color(0xFFCBB18D),
    Sage = Color(0xFFA8B895),
    SageDark = Color(0xFFAAC28A),
    Ring = Color(0xFFAAC28A),
    Sienna = Color(0xFFD4A17D),
    BorderWarm = Color(0x40D0B58F),
    MutedSurface = Color(0x26D0B58F),
    MutedSurfaceOpaque = Color(0xFF3B352C),
    SelectedSurface = Color(0xFF4B493F),
    NavigationSurface = Color(0xF72D2A24),
    OnPrimary = Color(0xFF202919),
)
internal val LocalMurphPalette = staticCompositionLocalOf { lightPalette }

object MurphColors {
    val Cream: Color @Composable get() = LocalMurphPalette.current.Cream
    val Card: Color @Composable get() = LocalMurphPalette.current.Card
    val Slate: Color @Composable get() = LocalMurphPalette.current.Slate
    val SlateMuted: Color @Composable get() = LocalMurphPalette.current.SlateMuted
    val Sand: Color @Composable get() = LocalMurphPalette.current.Sand
    val Amber: Color @Composable get() = LocalMurphPalette.current.Amber
    val Sage: Color @Composable get() = LocalMurphPalette.current.Sage
    val SageDark: Color @Composable get() = LocalMurphPalette.current.SageDark
    val Ring: Color @Composable get() = LocalMurphPalette.current.Ring
    val Sienna: Color @Composable get() = LocalMurphPalette.current.Sienna
    val BorderWarm: Color @Composable get() = LocalMurphPalette.current.BorderWarm
    val MutedSurface: Color @Composable get() = LocalMurphPalette.current.MutedSurface
    val MutedSurfaceOpaque: Color @Composable get() = LocalMurphPalette.current.MutedSurfaceOpaque
    val SelectedSurface: Color @Composable get() = LocalMurphPalette.current.SelectedSurface
    val NavigationSurface: Color @Composable get() = LocalMurphPalette.current.NavigationSurface
    val OnPrimary: Color @Composable get() = LocalMurphPalette.current.OnPrimary
}

val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
)

val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
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
fun MurphTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) darkPalette else lightPalette
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = palette.SageDark,
        onPrimary = palette.OnPrimary,
        primaryContainer = palette.MutedSurfaceOpaque,
        onPrimaryContainer = palette.Slate,
        secondary = palette.Sage,
        background = palette.Cream,
        onBackground = palette.Slate,
        surface = palette.Card,
        onSurface = palette.Slate,
        surfaceVariant = palette.MutedSurfaceOpaque,
        onSurfaceVariant = palette.SlateMuted,
        surfaceContainer = palette.Card,
        surfaceContainerHigh = palette.Card,
        surfaceContainerHighest = palette.MutedSurfaceOpaque,
        surfaceTint = Color.Transparent,
        outline = palette.BorderWarm,
        error = palette.Sienna,
    )
    CompositionLocalProvider(LocalMurphPalette provides palette) {
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
}
