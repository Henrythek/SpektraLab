// SettingsSheet.kt -- language, the two performance settings, and the credits.
@file:OptIn(ExperimentalMaterial3Api::class)

package app.spektralab.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.LanguageSetting
import app.spektralab.android.i18n.Localization
import app.spektralab.android.i18n.S
import app.spektralab.android.model.AppSettings
import app.spektralab.android.model.EditorViewModel
import app.spektralab.engine.NativeStudio

@Composable
fun SettingsSheet(model: EditorViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                     containerColor = Palette.card) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(L(S.SettingsTitle), color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

            PanelHeading(L(S.SetLanguage))
            SettingRow(L(S.SetLanguage)) {
                Menu(Localization.language.label, LanguageSetting.entries.map { it.label to it }) { Localization.select(it) }
            }

            PanelHeading(L(S.SetRendering))
            SettingRow(L(S.SetPreviewSize)) {
                Menu("${AppSettings.previewLongEdge} px", AppSettings.previewChoices.map { "$it px" to it }) {
                    AppSettings.selectPreview(it)
                    model.applyPreviewSize()
                }
            }
            SettingRow(L(S.SetDevelopSize)) {
                Menu("${AppSettings.developMegapixels} MP", AppSettings.developChoices.map { "$it MP" to it }) {
                    AppSettings.selectDevelop(it)
                    model.applyDevelopSize()
                }
            }
            Caption(L(S.SetDevelopSizeCaption))

            PanelHeading(L(S.SetAbout))
            Text(L(S.SetAboutText), color = Palette.text, fontSize = 13.sp)
            val info = model.engineInfo
            Caption("${L(S.SetEngine)}: ${runCatching { NativeStudio.buildInfo() }.getOrDefault("—")}")
            if (info != null) {
                Caption("${L(S.SetGpu)}: ${info.optString("gpu")}")
                Caption(info.optString("math_mode"))
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SettingRow(title: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.text, modifier = Modifier.weight(1f))
        control()
    }
}
