// Panels.kt -- the five tool panels under (or beside) the print.
//
// Film, Print, Camera and Effects are Layer 1: every control writes a
// `FilmSettings` field and so a schema field, and the engine renders it.
// Finish is Layer 2 (`Finish.kt`): it never reaches the engine.
@file:OptIn(ExperimentalMaterial3Api::class)

package app.spektralab.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.S
import app.spektralab.android.model.EditorViewModel
import app.spektralab.android.model.FilmFrame
import app.spektralab.android.model.FilmSettings
import app.spektralab.android.model.Metering
import app.spektralab.android.model.Stock
import app.spektralab.android.model.StockCatalog

enum class Tool(val icon: ImageVector) {
    Film(Icons.Outlined.Movie),
    Print(Icons.Outlined.Image),
    Camera(Icons.Outlined.Camera),
    Effects(Icons.Outlined.AutoAwesome),
    Finish(Icons.Outlined.Tune);

    val title: String
        get() = when (this) {
            Film -> L(S.TabFilm)
            Print -> L(S.TabPrint)
            Camera -> L(S.TabCamera)
            Effects -> L(S.TabEffects)
            Finish -> L(S.TabFinish)
        }
}

@Composable
fun ToolPanel(model: EditorViewModel, tool: Tool, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (tool) {
            Tool.Film -> FilmPanel(model)
            Tool.Print -> PrintPanel(model)
            Tool.Camera -> CameraPanel(model)
            Tool.Effects -> EffectsPanel(model)
            Tool.Finish -> FinishPanel(model)
        }
    }
}

// --- Film ----------------------------------------------------------------------

@Composable
private fun FilmPanel(model: EditorViewModel) {
    val catalog = StockCatalog.shared
    var group by rememberSaveable { mutableStateOf(StockCatalog.FilmGroup.Negative) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val groups = StockCatalog.FilmGroup.entries
        groups.forEachIndexed { i, g ->
            SegmentedButton(
                selected = g == group,
                onClick = { group = g },
                shape = SegmentedButtonDefaults.itemShape(i, groups.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Palette.accent,
                    activeContentColor = Color.Black,
                    inactiveContainerColor = Palette.card,
                    inactiveContentColor = Palette.text,
                ),
                icon = {},
            ) {
                Text(groupTitle(g), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(catalog.films(group), key = { it.id }) { stock ->
            StockCard(stock, selected = stock.id == model.settings.filmStock) {
                model.update { it.selectFilm(stock.id) }
            }
        }
    }
    PanelHeading(L(S.FilmFormat))
    Menu(
        label = FilmFrame.named(model.settings.frame).let { if (it.isCine) "${it.id} · CINE" else it.id },
        options = FilmFrame.all.map { (if (it.isCine) "${it.id} · CINE" else it.id) to it.id },
        onSelect = { id -> model.update { it.copy(frame = id) } },
    )
    Caption(L(S.FilmFormatCaption))
}

private fun groupTitle(g: StockCatalog.FilmGroup) = when (g) {
    StockCatalog.FilmGroup.Negative -> L(S.FilmGroupNegative)
    StockCatalog.FilmGroup.Positive -> L(S.FilmGroupPositive)
    StockCatalog.FilmGroup.BlackAndWhite -> L(S.FilmGroupBlackAndWhite)
    StockCatalog.FilmGroup.Cine -> L(S.FilmGroupCine)
}

/** A film box: its cover where the catalogue has one, its name otherwise. Names are never translated. */
@Composable
private fun StockCard(stock: Stock, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover: ImageBitmap? = remember(stock.cover) {
        stock.cover?.let { name ->
            runCatching {
                context.assets.open("FilmCovers/$name").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(92.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(92.dp).clip(shape).background(Palette.card)
                .border(BorderStroke(2.dp, if (selected) Palette.accent else Color.Transparent), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(cover, contentDescription = stock.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(if (stock.isMonochrome) Icons.Outlined.Tonality else Icons.Outlined.Movie, null,
                     tint = Palette.dim, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stock.name, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center,
            color = if (selected) Palette.text else Palette.dim, modifier = Modifier.height(30.dp),
        )
    }
}

// --- Print ---------------------------------------------------------------------

@Composable
private fun PrintPanel(model: EditorViewModel) {
    val catalog = StockCatalog.shared
    val s = model.settings
    LabeledSwitch(L(S.PrintNone), s.scanFilm) { v -> model.update { it.copy(scanFilm = v) } }
    if (s.scanFilm) return
    PanelHeading(L(S.PrintPaper))
    for (paper in catalog.papers.filter { catalog.canPrint(s.filmStock, it.id) }) {
        Row(
            Modifier.fillMaxWidth().clickable { model.update { it.copy(printStock = paper.id) } }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(paper.name, color = Palette.text, fontSize = 15.sp)
                if (catalog.isDeclaredPairing(s.filmStock, paper.id)) {
                    Text(L(S.PrintDeclared), color = Palette.dim, fontSize = 11.sp)
                } else if (paper.isCine) {
                    Text(L(S.PrintGroupCine), color = Palette.dim, fontSize = 11.sp)
                }
            }
            if (paper.id == s.printStock) Icon(Icons.Filled.Check, null, tint = Palette.accent)
        }
    }
    PanelHeading(L(S.SectionEnlarger))
    ParamSlider(L(S.PrintBrightness), s.printBrightness, { v -> model.update { it.copy(printBrightness = v) } },
                -2.0..2.0, format = { "%+.2f EV".format(it) })
    ParamSlider(L(S.PrintYellow), s.yellowShift, { v -> model.update { it.copy(yellowShift = v) } }, -1.0..1.0,
                format = { "%+.0f CC".format(it * FilmSettings.FILTER_SHIFT_CC) })
    ParamSlider(L(S.PrintMagenta), s.magentaShift, { v -> model.update { it.copy(magentaShift = v) } }, -1.0..1.0,
                format = { "%+.0f CC".format(it * FilmSettings.FILTER_SHIFT_CC) })
}

// --- Camera --------------------------------------------------------------------

@Composable
private fun CameraPanel(model: EditorViewModel) {
    val s = model.settings
    PanelHeading(L(S.CameraMetering))
    Menu(
        label = meteringTitle(s.metering),
        options = Metering.entries.map { meteringTitle(it) to it },
        onSelect = { m -> model.update { it.copy(metering = m) } },
    )
    ParamSlider(L(S.CameraFilmExposure), s.exposureEV, { v -> model.update { it.copy(exposureEV = v) } }, -4.0..4.0,
                format = { "%+.1f EV".format(it) })
    val metered = model.meteredEV
    if (s.metering != Metering.Custom && metered != null) Caption(L(S.CameraMetered, "%+.1f".format(metered)))

    PanelHeading(L(S.SectionWhiteBalance))
    LabeledSwitch(L(S.CameraAsShot), s.whiteBalance.asShot) { asShot ->
        model.update {
            val wb = if (asShot) it.whiteBalance.copy(asShot = true)
            else it.whiteBalance.copy(asShot = false, temperature = model.asShotTemperature ?: 6500.0,
                                      tint = model.asShotTint ?: 0.0)
            it.copy(whiteBalance = wb)
        }
    }
    ParamSlider(L(S.CameraTemperature), s.whiteBalance.temperature,
                { v -> model.update { it.copy(whiteBalance = it.whiteBalance.copy(temperature = v)) } },
                2000.0..12000.0, defaultValue = model.asShotTemperature ?: 6500.0, format = { "%.0f K".format(it) },
                commitOnRelease = true, enabled = !s.whiteBalance.asShot)
    ParamSlider(L(S.CameraTint), s.whiteBalance.tint,
                { v -> model.update { it.copy(whiteBalance = it.whiteBalance.copy(tint = v)) } },
                -150.0..150.0, defaultValue = model.asShotTint ?: 0.0, format = { "%+.0f".format(it) },
                commitOnRelease = true, enabled = !s.whiteBalance.asShot)
    if (!model.isRaw) Caption(L(S.CameraFlatWhiteBalance))
}

private fun meteringTitle(m: Metering) = when (m) {
    Metering.Custom -> L(S.MeteringCustom)
    Metering.Balanced -> L(S.MeteringBalanced)
    Metering.Center -> L(S.MeteringCenter)
    Metering.ProtectHighlights -> L(S.MeteringProtectHighlights)
    Metering.ProtectShadows -> L(S.MeteringProtectShadows)
}

// --- Effects -------------------------------------------------------------------

@Composable
private fun EffectsPanel(model: EditorViewModel) {
    val s = model.settings
    EffectRow(L(S.FilmGrain), s.grainActive, { v -> model.update { it.copy(grainActive = v) } },
              s.grain, { v -> model.update { it.copy(grain = v) } }, 0.0..2.0)
    EffectRow(L(S.FilmHalation), s.halationActive, { v -> model.update { it.copy(halationActive = v) } },
              s.halation, { v -> model.update { it.copy(halation = v) } }, 0.0..4.0)
    EffectRow(L(S.FilmCouplers), s.couplersActive, { v -> model.update { it.copy(couplersActive = v) } },
              s.couplers, { v -> model.update { it.copy(couplers = v) } }, 0.0..1.5)
    EffectRow(L(S.FilmGlare), s.glareActive, { v -> model.update { it.copy(glareActive = v) } },
              s.glare, { v -> model.update { it.copy(glare = v) } }, 0.0..10.0)
    TextButton(onClick = { model.resetLook() }, modifier = Modifier.padding(top = 8.dp)) {
        Text(L(S.ActionResetLook), color = Color(0xFFE57373))
    }
}

// --- Finish --------------------------------------------------------------------

@Composable
private fun FinishPanel(model: EditorViewModel) {
    val f = model.finish
    val signed = { v: Double -> "%+.0f".format(v) }
    Caption(L(S.FinishCaption))
    ParamSlider(L(S.FinishExposure), f.exposure, { v -> model.updateFinish { it.copy(exposure = v) } }, -3.0..3.0,
                format = { "%+.2f EV".format(it) })
    ParamSlider(L(S.FinishContrast), f.contrast, { v -> model.updateFinish { it.copy(contrast = v) } }, -50.0..50.0, format = signed)
    ParamSlider(L(S.FinishSaturation), f.saturation, { v -> model.updateFinish { it.copy(saturation = v) } }, -100.0..100.0, format = signed)
    ParamSlider(L(S.FinishHighlights), f.highlights, { v -> model.updateFinish { it.copy(highlights = v) } }, -100.0..100.0, format = signed)
    ParamSlider(L(S.FinishShadows), f.shadows, { v -> model.updateFinish { it.copy(shadows = v) } }, -100.0..100.0, format = signed)
    ParamSlider(L(S.CameraTemperature), f.temperature, { v -> model.updateFinish { it.copy(temperature = v) } }, -100.0..100.0, format = signed)
    ParamSlider(L(S.CameraTint), f.tint, { v -> model.updateFinish { it.copy(tint = v) } }, -100.0..100.0, format = signed)
    ParamSlider(L(S.FinishVignette), f.vignette, { v -> model.updateFinish { it.copy(vignette = v) } }, -100.0..100.0, format = signed)
    TextButton(onClick = { model.updateFinish { app.spektralab.android.model.Finish() } }) {
        Text(L(S.ActionReset), color = Palette.accent)
    }
}

// --- shared ----------------------------------------------------------------------

/** A button showing the current choice that opens a menu of the others. */
@Composable
fun <T> Menu(label: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, border = BorderStroke(1.dp, Palette.rule)) {
            Text(label, color = Palette.accent)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((text, value) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}
