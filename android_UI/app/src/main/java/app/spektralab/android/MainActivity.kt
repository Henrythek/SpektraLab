// MainActivity.kt -- the one screen: the library until a photo is open, then
// the editor. Photos also arrive from other apps ("Open with", "Share").
package app.spektralab.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import app.spektralab.android.model.EditorViewModel
import app.spektralab.android.ui.EditorScreen
import app.spektralab.android.ui.LibraryScreen
import app.spektralab.android.ui.Palette
import app.spektralab.android.ui.SpektraLabTheme

class MainActivity : ComponentActivity() {
    private val model: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming(intent)?.let { model.open(it) }
        setContent {
            SpektraLabTheme {
                val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { model.open(it) }
                }
                val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { model.open(it) }
                }
                val pick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Box(Modifier.fillMaxSize().background(Palette.canvas).safeDrawingPadding()) {
                    if (model.showsEditor) {
                        EditorScreen(model, onPickPhoto = pick)
                    } else {
                        // RAW files are often typed application/octet-stream, so every type is offered.
                        LibraryScreen(model, onPickPhoto = pick, onOpenFile = { openFile.launch(arrayOf("image/*", "*/*")) })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming(intent)?.let { model.open(it) }
    }

    private fun incoming(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}
