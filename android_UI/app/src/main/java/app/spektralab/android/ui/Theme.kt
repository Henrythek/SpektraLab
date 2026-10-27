// Theme.kt -- the Mac's palette (v3 sample), as the iOS app uses it, so the
// three apps read as one product: a near-black card, warm white text, the
// amber accent. Always dark: it is a darkroom.
package app.spektralab.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val canvas = Color(0xFF1A1A19)
    val card = Color(0xFF2D2D2C)
    val rule = Color(0xFF3A3B39)
    val text = Color(0xFFF9F7F3)
    val dim = Color(0xFF898989)
    val accent = Color(0xFFE1A95F)
}

@Composable
fun SpektraLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.Black,
            secondary = Palette.accent,
            background = Palette.canvas,
            onBackground = Palette.text,
            surface = Palette.card,
            onSurface = Palette.text,
            surfaceVariant = Palette.rule,
            onSurfaceVariant = Palette.dim,
            surfaceContainer = Palette.card,
            surfaceContainerHigh = Palette.card,
            surfaceContainerLow = Palette.card,
            outline = Palette.rule,
        ),
        content = content,
    )
}
