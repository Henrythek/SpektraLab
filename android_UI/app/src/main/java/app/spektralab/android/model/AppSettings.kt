// AppSettings.kt -- the phone's two performance settings, persisted.
//
// - Preview size is the engine's `live` tier long edge
//   (`io.preview_long_edge`): every edit renders at it.
// - Develop size caps the frame the engine develops and the export's full
//   size. A memory decision rather than a look, as on iOS; studio.cpp lowers
//   it further when the GPU's storage buffers are smaller than the frame.
package app.spektralab.android.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object AppSettings {
    val previewChoices = listOf(1280, 1600, 2048, 2560)
    val developChoices = listOf(8, 12, 24, 48)

    private const val PREFS = "spektralab"
    private var context: Context? = null

    var previewLongEdge by mutableIntStateOf(1600)
        private set
    var developMegapixels by mutableIntStateOf(12)
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        previewLongEdge = prefs.getInt("previewLongEdge", 1600).takeIf { it in previewChoices } ?: 1600
        developMegapixels = prefs.getInt("developMegapixels", 12).takeIf { it in developChoices } ?: 12
    }

    fun selectPreview(edge: Int) {
        previewLongEdge = edge
        save("previewLongEdge", edge)
    }

    fun selectDevelop(megapixels: Int) {
        developMegapixels = megapixels
        save("developMegapixels", megapixels)
    }

    private fun save(key: String, value: Int) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putInt(key, value)?.apply()
    }
}
