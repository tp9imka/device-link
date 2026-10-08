package dev.devicelink.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Screens consume these semantic tokens; visual changes never require screen edits. */
data class DeviceTokens(
    val hairline: androidx.compose.ui.unit.Dp = 1.dp,
    val tiny: androidx.compose.ui.unit.Dp = 4.dp,
    val small: androidx.compose.ui.unit.Dp = 8.dp,
    val gap: androidx.compose.ui.unit.Dp = 12.dp,
    val inset: androidx.compose.ui.unit.Dp = 16.dp,
    val page: androidx.compose.ui.unit.Dp = 24.dp,
    val section: androidx.compose.ui.unit.Dp = 32.dp,
    val avatar: androidx.compose.ui.unit.Dp = 48.dp,
    val heroMark: androidx.compose.ui.unit.Dp = 64.dp,
    val contentWidth: androidx.compose.ui.unit.Dp = 720.dp,
    val qrSize: androidx.compose.ui.unit.Dp = 248.dp,
    val qrPixels: Int = 768,
    val qrInk: Int = android.graphics.Color.BLACK,
    val qrPaper: Int = android.graphics.Color.WHITE,
    val qrBackground: Color = Color.White,
    val compactBreakpoint: androidx.compose.ui.unit.Dp = 380.dp,
)
val LocalDeviceTokens = staticCompositionLocalOf { DeviceTokens() }

@Composable
fun DeviceTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    }
    val seed = when (appearance.accent) {
        Accent.OCEAN -> if (dark) Color(0xFF6EDBCB) else Color(0xFF006B60)
        Accent.IRIS -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6650A4)
        Accent.FOREST -> if (dark) Color(0xFFACD79B) else Color(0xFF426B35)
    }
    val container = when (appearance.accent) {
        Accent.OCEAN -> if (dark) Color(0xFF005047) else Color(0xFF9FF2E2)
        Accent.IRIS -> if (dark) Color(0xFF4F378B) else Color(0xFFEADDFF)
        Accent.FOREST -> if (dark) Color(0xFF2B5120) else Color(0xFFC7EEB4)
    }
    val scheme = if (appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkColorScheme(
        primary = seed, onPrimary = Color(0xFF082F2B), primaryContainer = container,
        onPrimaryContainer = Color(0xFFE4FFF8), secondary = Color(0xFFBACBC6),
        background = Color(0xFF101513), surface = Color(0xFF101513),
        surfaceContainer = Color(0xFF1C2420), surfaceContainerLow = Color(0xFF171D1A),
        surfaceContainerHigh = Color(0xFF27312C), onSurface = Color(0xFFE0E8E1),
        onSurfaceVariant = Color(0xFFBFC9C1), outlineVariant = Color(0xFF3F4A43),
    ) else lightColorScheme(
        primary = seed, onPrimary = Color.White, primaryContainer = container,
        onPrimaryContainer = Color(0xFF102C26), secondary = Color(0xFF4C635B),
        background = Color(0xFFF5F8F4), surface = Color(0xFFF5F8F4),
        surfaceContainer = Color(0xFFEBF0E9), surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFE2E9E1), onSurface = Color(0xFF19231D),
        onSurfaceVariant = Color(0xFF4C5B51), outlineVariant = Color(0xFFCED8CF),
    )
    val radius = when (appearance.corners) { Corners.SOFT -> 20.dp; Corners.ROUND -> 32.dp; Corners.SQUARE -> 6.dp }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(radius / 3), small = RoundedCornerShape(radius / 2),
        medium = RoundedCornerShape(radius), large = RoundedCornerShape(radius),
        extraLarge = RoundedCornerShape(radius + 8.dp),
    )
    val typography = Typography(
        headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalDeviceTokens provides DeviceTokens()) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}
