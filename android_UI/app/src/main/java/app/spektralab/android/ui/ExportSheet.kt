// ExportSheet.kt -- print at full size, then save to the gallery or share.
//
// "Full" is the frame as the engine developed it: the photo capped at
// Settings -> Develop size (and at what the GPU allows); the smaller sizes
// scale the finished print. The iOS app's `ExportSheet`.
@file:OptIn(ExperimentalMaterial3Api::class)

package app.spektralab.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.S
import app.spektralab.android.model.EditorViewModel
import app.spektralab.android.model.ExportFormat
import java.io.File
import kotlinx.coroutines.launch

private val SIZES = listOf(0, 4096, 2048)

@Composable
fun ExportSheet(model: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ExportFormat.Jpeg) }
    var longEdge by rememberSaveable { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(then: suspend (File) -> Unit) {
        working = true
        message = null
        scope.launch {
            try {
                then(model.export(format, longEdge))
            } catch (e: Exception) {
                message = L(S.ExportFailed, e.message ?: e.toString())
            } finally {
                working = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                     containerColor = Palette.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L(S.ActionExport), color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L(S.ExportFormat), color = Palette.text, modifier = Modifier.weight(1f))
                Menu(format.title, ExportFormat.entries.map { it.title to it }) { format = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L(S.ExportSize), color = Palette.text, modifier = Modifier.weight(1f))
                Menu(sizeTitle(longEdge), SIZES.map { sizeTitle(it) to it }) { longEdge = it }
            }
            Button(
                onClick = { run { file -> model.saveToGallery(file, format); message = L(S.ExportSaved) } },
                enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(L(S.ExportSaveToGallery))
            }
            OutlinedButton(
                onClick = { run { file -> context.startActivity(model.shareIntent(file, format)) } },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, null, tint = Palette.text)
                Spacer(Modifier.width(8.dp))
                Text(L(S.ExportShare), color = Palette.text)
            }
            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Palette.dim, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(L(S.StatusPrinting), color = Palette.dim, fontSize = 13.sp)
                }
            } else {
                message?.let { Text(it, color = Palette.dim, fontSize = 13.sp) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

private fun sizeTitle(edge: Int) = if (edge == 0) L(S.ExportSizeFull) else "$edge px"
