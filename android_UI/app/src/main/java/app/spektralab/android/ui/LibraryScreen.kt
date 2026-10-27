// LibraryScreen.kt -- the first screen, and the two ways a photo arrives: the
// system photo picker (no permission needed) or the document browser, which
// also reaches RAW files the picker does not list. The iOS app's `LibraryView`.
package app.spektralab.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.S
import app.spektralab.android.model.EditorViewModel

@Composable
fun LibraryScreen(model: EditorViewModel, onPickPhoto: () -> Unit, onOpenFile: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Palette.canvas).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Outlined.Settings, contentDescription = L(S.SettingsTitle), tint = Palette.text)
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.Theaters, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(64.dp))
        Text("SpektraLab", color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Text(L(S.LibraryPrompt), color = Palette.dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPickPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Color.Black),
                modifier = Modifier.widthIn(min = 260.dp).height(48.dp),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null)
                Spacer(Modifier.width(8.dp))
                Text(L(S.ActionChoosePhoto))
            }
            OutlinedButton(onClick = onOpenFile, modifier = Modifier.widthIn(min = 260.dp).height(48.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, tint = Palette.text)
                Spacer(Modifier.width(8.dp))
                Text(L(S.ActionOpenFile), color = Palette.text)
            }
        }
        Text(L(S.LibraryFormats), color = Palette.dim, fontSize = 12.sp)
        val phase = model.phase
        if (phase is EditorViewModel.Phase.Failed) {
            Text(phase.message, color = Color(0xFFE57373), fontSize = 13.sp, textAlign = TextAlign.Center,
                 modifier = Modifier.padding(horizontal = 24.dp))
        }
        Spacer(Modifier.weight(1f))
    }
    if (showSettings) SettingsSheet(model) { showSettings = false }
}
