// Controls.kt -- the controls every panel uses, the iOS app's `ParamSlider`,
// `EffectRow` and `PanelHeading`.
package app.spektralab.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.S

/**
 * A labelled slider with its value, reset on double-tap of the label.
 *
 * `commitOnRelease` holds the value locally while the finger is down and
 * writes it once on release: for a control whose every change costs a
 * re-decode (white balance), not a reprint.
 */
@Composable
fun ParamSlider(
    title: String,
    value: Double,
    onChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    defaultValue: Double = 0.0,
    format: (Double) -> String = { "%+.1f".format(it) },
    commitOnRelease: Boolean = false,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf<Double?>(null) }
    val shown = dragging ?: value
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().pointerInput(enabled, defaultValue) {
                detectTapGestures(onDoubleTap = { if (enabled) onChange(defaultValue) })
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = if (enabled) Palette.text else Palette.dim, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(format(shown), color = Palette.dim, fontSize = 14.sp)
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { v -> if (commitOnRelease) dragging = v.toDouble() else onChange(v.toDouble()) },
            onValueChangeFinished = {
                if (commitOnRelease) dragging?.let { onChange(it) }
                dragging = null
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.rule,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun LabeledSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.text),
        )
    }
}

/** A row that switches an effect on and off, with its strength below it. */
@Composable
fun EffectRow(
    title: String,
    active: Boolean,
    onActive: (Boolean) -> Unit,
    strength: Double,
    onStrength: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LabeledSwitch(title, active, onActive)
        Column(Modifier.padding(start = 12.dp)) {
            ParamSlider(L(S.EffectStrength), strength, onStrength, range, defaultValue = 1.0,
                        format = { "%.2f".format(it) }, enabled = active)
        }
    }
}

/** A section heading inside a panel. */
@Composable
fun PanelHeading(text: String) {
    Text(
        text.uppercase(),
        color = Palette.dim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
fun Caption(text: String) {
    Text(text, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp)
}
