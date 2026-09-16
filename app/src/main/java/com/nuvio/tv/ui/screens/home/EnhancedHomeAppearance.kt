package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.BitmapImage
import coil3.Image
import com.nuvio.tv.data.local.EnhancedSettings
import com.nuvio.tv.ui.screens.settings.EnhancedSettingsViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.*

internal val LocalHomeAppearance = staticCompositionLocalOf<HomeAppearance?> { null }

@Stable
internal class HomeAppearance(private val scope: CoroutineScope) {
    var settings by mutableStateOf(EnhancedSettings())
    var color by mutableStateOf(Color.Transparent)
    private val colors = LinkedHashMap<String, Color>()
    private var sampling: Job? = null
    fun sample(image: Image, key: String) {
        if (!settings.dynamicBackground) return
        sampling?.cancel()
        colors[key]?.let { color = it; return }
        val bitmap = (image as? BitmapImage)?.bitmap ?: return
        if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.isRecycled) return
        sampling = scope.launch {
            val tint = withContext(Dispatchers.Default) {
                var red = 0L; var green = 0L; var blue = 0L; var count = 0
                // Sample 1,024 pixels from artwork already decoded by Coil. No second image request.
                for (y in 0 until 32) for (x in 0 until 32) {
                    ensureActive()
                    val pixel = bitmap.getPixel(x * bitmap.width / 32, y * bitmap.height / 32)
                    val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    if (r + g + b in 75..690) { red += r; green += g; blue += b; count++ }
                }
                if (count == 0) Color.Transparent else Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
            }
            colors[key] = tint
            if (colors.size > 16) colors.remove(colors.keys.first())
            color = tint
        }
    }
}

@Composable
internal fun EnhancedHomeAppearance(content: @Composable () -> Unit) {
    val viewModel: EnhancedSettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val appearance = remember(scope) { HomeAppearance(scope) }
    SideEffect { appearance.settings = settings }
    CompositionLocalProvider(LocalHomeAppearance provides appearance, content = content)
}

@Composable
internal fun Modifier.homeBackground(): Modifier {
    val appearance = LocalHomeAppearance.current
    val base = NuvioTheme.colors.Background
    val tint = if (appearance?.settings?.dynamicBackground == true) appearance.color else Color.Transparent
    return background(base).background(Brush.verticalGradient(listOf(tint.copy(alpha = if (tint == Color.Transparent) 0f else 0.32f), Color.Transparent)))
}

@Composable
internal fun Modifier.catalogAccent(): Modifier {
    if (LocalHomeAppearance.current?.settings?.catalogUnderline != true) return this
    val accent = NuvioTheme.colors.Primary
    return padding(bottom = 6.dp).drawBehind {
        drawLine(accent, Offset(0f, size.height), Offset(minOf(size.width, 72.dp.toPx()), size.height), 2.dp.toPx())
    }
}
