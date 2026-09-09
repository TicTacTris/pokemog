package dev.pokemog.android

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

data class PokeMogPalette(
    val background: Int, val surface: Int, val elevated: Int,
    val primary: Int, val onPrimary: Int, val text: Int, val muted: Int,
    val outline: Int, val gold: Int, val error: Int,
)

fun warmPalette(dark: Boolean) = if (dark) PokeMogPalette(
    0xFF191614.toInt(), 0xFF25201C.toInt(), 0xFF332B25.toInt(),
    0xFFFF9C86.toInt(), 0xFF3B160D.toInt(), 0xFFFFF2E5.toInt(), 0xFFCCBBAA.toInt(),
    0xFFA18B74.toInt(), 0xFFECC16C.toInt(), 0xFFFFB4A5.toInt(),
) else PokeMogPalette(
    0xFFFBF5EC.toInt(), 0xFFFFFCF7.toInt(), 0xFFF1E7D8.toInt(),
    0xFFB6422D.toInt(), 0xFFFFF9F2.toInt(), 0xFF30251F.toInt(), 0xFF746051.toInt(),
    0xFF88715D.toInt(), 0xFF86590E.toInt(), 0xFFAB352D.toInt(),
)

object PokeMogAppearance {
    const val DARK_MODE = "dark_mode"
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun isDark(context: Context): Boolean {
        val prefs = preferences(context)
        return if (prefs.contains(DARK_MODE)) prefs.getBoolean(DARK_MODE, false)
        else context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    fun setDark(context: Context, dark: Boolean) { preferences(context).edit().putBoolean(DARK_MODE, dark).apply() }
    fun palette(context: Context) = warmPalette(isDark(context))
}

@Composable
fun PokeMogTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = warmPalette(dark)
    val scheme = if (dark) darkColorScheme(
        primary = Color(p.primary), onPrimary = Color(p.onPrimary),
        secondary = Color(p.gold), background = Color(p.background), onBackground = Color(p.text),
        surface = Color(p.surface), onSurface = Color(p.text),
        surfaceVariant = Color(p.elevated), onSurfaceVariant = Color(p.muted),
        secondaryContainer = Color(p.elevated), onSecondaryContainer = Color(p.text),
        outline = Color(p.outline), error = Color(p.error),
    ) else lightColorScheme(
        primary = Color(p.primary), onPrimary = Color(p.onPrimary),
        secondary = Color(p.gold), background = Color(p.background), onBackground = Color(p.text),
        surface = Color(p.surface), onSurface = Color(p.text),
        surfaceVariant = Color(p.elevated), onSurfaceVariant = Color(p.muted),
        secondaryContainer = Color(p.elevated), onSecondaryContainer = Color(p.text),
        outline = Color(p.outline), error = Color(p.error),
    )
    val context = LocalContext.current
    val typography = remember(context) { pokemogTypography(FontFamily(retroTypeface(context))) }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** Use pixel type for every app text role without reducing Material sizes or line heights. */
fun pokemogTypography(pixel: FontFamily): Typography {
    val base = Typography()
    fun androidx.compose.ui.text.TextStyle.pixel() = copy(fontFamily = pixel, fontWeight = FontWeight.Normal)
    return base.copy(
        displayLarge = base.displayLarge.pixel(), displayMedium = base.displayMedium.pixel(), displaySmall = base.displaySmall.pixel(),
        headlineLarge = base.headlineLarge.pixel(), headlineMedium = base.headlineMedium.pixel(), headlineSmall = base.headlineSmall.pixel(),
        titleLarge = base.titleLarge.pixel(), titleMedium = base.titleMedium.pixel(), titleSmall = base.titleSmall.pixel(),
        bodyLarge = base.bodyLarge.pixel(), bodyMedium = base.bodyMedium.pixel(), bodySmall = base.bodySmall.pixel(),
        labelLarge = base.labelLarge.pixel(), labelMedium = base.labelMedium.pixel(), labelSmall = base.labelSmall.pixel(),
    )
}
