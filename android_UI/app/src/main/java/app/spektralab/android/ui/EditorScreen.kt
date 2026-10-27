// EditorScreen.kt -- the print, and the tools that change it.
//
// Portrait: the print above, the panel below, the tool bar at the bottom, the
// shape of a phone's own photo editor. Landscape and tablets: the panel goes
// to the right, as the Mac's right rail does. The iOS app's `EditorView`.
//
// Press and hold the print to see the original (the decoder's rendering of the
// photo); pinch and double-tap to zoom.
package app.spektralab.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.S
import app.spektralab.android.model.EditorViewModel
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun EditorScreen(model: EditorViewModel, onPickPhoto: () -> Unit) {
    var tool by rememberSaveable { mutableStateOf(Tool.Film) }
    var showSettings by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.canvas)) {
        val wide = maxWidth > maxHeight && maxWidth > 600.dp
        // Read here: inside Row/Column the scopes' own receivers hide these.
        val railWidth = minOf(380.dp, maxWidth * 0.4f)
        val panelHeight = minOf(300.dp, maxHeight * 0.38f)
        Column(Modifier.fillMaxSize()) {
            TopBar(model, onPickPhoto, onSettings = { showSettings = true }, onExport = { showExport = true })
            if (wide) {
                Row(Modifier.weight(1f)) {
                    Canvas(model, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(railWidth).fillMaxHeight().background(Palette.card)) {
                        ToolBar(tool) { tool = it }
                        HorizontalDivider(color = Palette.rule)
                        ToolPanel(model, tool, Modifier.weight(1f))
                    }
                }
            } else {
                Canvas(model, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(panelHeight).background(Palette.card)) {
                    ToolPanel(model, tool)
                }
                Box(Modifier.background(Palette.card)) { ToolBar(tool) { tool = it } }
            }
        }
    }
    if (showSettings) SettingsSheet(model) { showSettings = false }
    if (showExport) ExportSheet(model) { showExport = false }
}

@Composable
private fun TopBar(model: EditorViewModel, onPickPhoto: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onPickPhoto) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = L(S.ActionChoosePhoto), tint = Palette.text)
        }
        Spacer(Modifier.weight(1f))
        val ms = model.lastRenderMs
        if (model.isRendering) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.dim, strokeWidth = 2.dp)
        } else if (ms != null) {
            Text("%.0f ms".format(ms), color = Palette.dim, fontSize = 11.sp)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = L(S.SettingsTitle), tint = Palette.text)
        }
        Button(
            onClick = onExport,
            enabled = model.phase == EditorViewModel.Phase.Ready,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Color.Black),
        ) { Text(L(S.ActionExport)) }
    }
}

@Composable
private fun Canvas(model: EditorViewModel, modifier: Modifier) {
    var showOriginal by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.background(Palette.canvas)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gesture, _ ->
                    zoom = (zoom * gesture).coerceIn(1f, 8f)
                    offset = if (zoom == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoom > 1f) { zoom = 1f; offset = Offset.Zero } else zoom = 2.5f
                    },
                    // Held, not tapped: a press shorter than this is a tap or the
                    // start of a pinch, and shows nothing.
                    onPress = {
                        if (withTimeoutOrNull(150) { tryAwaitRelease() } == null) {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val print = model.displayImage
        val original = model.originalImage
        val shown = if (showOriginal) original ?: print else print
        if (shown != null) {
            Image(
                shown, contentDescription = L(S.HelpHoldForOriginal), contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().padding(12.dp).graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                },
            )
        } else if (original != null) {
            Image(original, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp).alpha(0.5f))
        }
        when (val phase = model.phase) {
            EditorViewModel.Phase.Opening -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.text)
                Spacer(Modifier.height(8.dp))
                Text(L(S.StatusDeveloping), color = Palette.dim, fontSize = 13.sp)
            }
            is EditorViewModel.Phase.Failed -> Text(phase.message, color = Palette.text, fontSize = 13.sp,
                                                    modifier = Modifier.padding(16.dp))
            else -> {}
        }
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showOriginal) {
                Text(L(S.ActionOriginal), color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                         .padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            model.errorMessage?.let {
                Text(it, color = Palette.text, fontSize = 12.sp,
                     modifier = Modifier.background(Color(0x99C62828), RoundedCornerShape(6.dp)).padding(8.dp))
            }
        }
    }
}

@Composable
private fun ToolBar(selected: Tool, onSelect: (Tool) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (t in Tool.entries) {
            val tint = if (t == selected) Palette.accent else Palette.dim
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(3.dp))
                Text(t.title, color = tint, fontSize = 11.sp)
            }
        }
    }
}
