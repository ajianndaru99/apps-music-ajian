package app.podium.ytm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.core.designsystem.artwork.LocalArtworkLoader

@Immutable
data class YtmColors(
    val background: Color = Color(0xFF030303),
    val surface: Color = Color(0xFF212121),
    val surfaceVariant: Color = Color(0xFF161616),
    val surfaceElevated: Color = Color(0xFF2A2A2A),
    val onSurface: Color = Color(0xFFFFFFFF),
    val onSurfaceVariant: Color = Color(0xFFAAAAAA),
    val onSurfaceMuted: Color = Color(0xFF717171),
    val primary: Color = Color(0xFFFF0000),
    val accent: Color = Color(0xFFFFFFFF),
    val divider: Color = Color(0x26FFFFFF),
    val chipSelectedBg: Color = Color(0xFFFFFFFF),
    val chipSelectedFg: Color = Color(0xFF030303),
    val chipUnselectedBg: Color = Color(0x1FFFFFFF),
    val chipUnselectedFg: Color = Color(0xFFFFFFFF),
)

@Immutable
data class YtmType(
    val headlineLarge: TextStyle = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
    ),
    val headlineMedium: TextStyle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 26.sp,
    ),
    val titleLarge: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    ),
    val titleMedium: TextStyle = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
    val bodyLarge: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    ),
    val labelMedium: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    ),
    val labelSmall: TextStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
    ),
)

val LocalYtmColors = staticCompositionLocalOf { YtmColors() }
val LocalYtmType = staticCompositionLocalOf { YtmType() }

object YtmTheme {
    val colors: YtmColors
        @Composable get() = LocalYtmColors.current
    val type: YtmType
        @Composable get() = LocalYtmType.current
}

@Composable
fun YtmTheme(
    artworkLoader: ArtworkLoader,
    content: @Composable () -> Unit,
) {
    val ytmColors = YtmColors()
    val ytmType = YtmType()
    val materialColors = darkColorScheme(
        primary = ytmColors.primary,
        onPrimary = Color.White,
        background = ytmColors.background,
        onBackground = ytmColors.onSurface,
        surface = ytmColors.surface,
        onSurface = ytmColors.onSurface,
        surfaceVariant = ytmColors.surfaceVariant,
        onSurfaceVariant = ytmColors.onSurfaceVariant,
    )
    val materialType = Typography(
        headlineMedium = ytmType.headlineMedium.copy(color = ytmColors.onSurface),
        titleLarge = ytmType.titleLarge.copy(color = ytmColors.onSurface),
        titleMedium = ytmType.titleMedium.copy(color = ytmColors.onSurface),
        bodyLarge = ytmType.bodyLarge.copy(color = ytmColors.onSurface),
        bodyMedium = ytmType.bodyMedium.copy(color = ytmColors.onSurfaceVariant),
        labelMedium = ytmType.labelMedium.copy(color = ytmColors.onSurface),
        labelSmall = ytmType.labelSmall.copy(color = ytmColors.onSurfaceVariant),
    )

    CompositionLocalProvider(
        LocalYtmColors provides ytmColors,
        LocalYtmType provides ytmType,
        LocalArtworkLoader provides artworkLoader,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = materialType,
            content = content,
        )
    }
}
