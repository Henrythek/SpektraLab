// FilmSettings.kt -- Layer 1, the engine's parameters, as the phone holds them.
//
// The iOS app's `FilmSettings`, field for field, with the same wire: every name
// below is a schema name (`engine/src/core/params.cpp`, API-SPEC §3), and every
// value is derived the way the Mac's `FilmParams.wire` derives it, so a frame
// printed on Android, iOS and the Mac with the same settings asks the engine
// for the same thing. The reasons for each rule are written down once, in
// `modern_UI/.../Model/Params.swift`.
//
// Left out for now, as on iOS: Scene Placement, the Tone Mask, the film edge
// and date back, the Digital Intermediate, crop and half-frame pairs.
package app.spektralab.android.model

import kotlin.math.max
import kotlin.math.pow

/** The four metering intents plus Custom, which is the meter switched off. */
enum class Metering(val wire: String) {
    Custom("custom"), Balanced("balanced"), Center("center"),
    ProtectHighlights("protect_highlights"), ProtectShadows("protect_shadows"),
}

/** A film frame by its short side; the wire's long edge follows the photo's aspect. */
data class FilmFrame(val id: String, val shortSideMM: Double, val isCine: Boolean) {
    companion object {
        val all = listOf(
            FilmFrame("110", 13.0, false),
            FilmFrame("APS", 16.7, false),
            FilmFrame("135", 24.0, false),
            FilmFrame("120", 56.0, false),
            FilmFrame("Super 8", 4.01, true),
            FilmFrame("16mm", 7.49, true),
            FilmFrame("Super 16", 7.41, true),
            FilmFrame("Super 35", 14.0, true),
        )
        fun named(id: String) = all.firstOrNull { it.id == id } ?: all[2]
    }
}

data class WhiteBalance(
    val asShot: Boolean = true,
    /** Kelvin, on the DNG SDK's scale (engine/host/white_balance.hpp). */
    val temperature: Double = 5500.0,
    val tint: Double = 0.0,
) {
    /** The desktop host's `decode.white_balance`, which studio.cpp reads. */
    fun json(): String = if (asShot) """{"mode":"as_shot"}"""
    else """{"mode":"custom","temperature_k":$temperature,"tint":$tint}"""
}

data class FilmSettings(
    val filmStock: String = "kodak_portra_400",
    val printStock: String = "kodak_portra_endura",
    /** No print stage: the negative (or slide) is scanned directly. */
    val scanFilm: Boolean = false,

    val metering: Metering = Metering.Balanced,
    /** Stops, an offset from the meter (or the whole exposure under Custom). */
    val exposureEV: Double = 0.0,
    val whiteBalance: WhiteBalance = WhiteBalance(),

    val frame: String = "135",

    val grainActive: Boolean = true,
    val grain: Double = 1.0,        // 0..2, times the film's own (`ownGrain`)
    val halationActive: Boolean = true,
    val halation: Double = 1.0,     // 0..4
    val couplersActive: Boolean = true,
    val couplers: Double = 1.0,     // 0..1.5
    val glareActive: Boolean = true,
    val glare: Double = 1.0,        // 0..30

    /** Print brightness in stops; the wire's `print_exposure` is 2^-stops. */
    val printBrightness: Double = 0.0,
    val yellowShift: Double = 0.0,  // -1..1 of `filterShiftCC`
    val magentaShift: Double = 0.0,
) {
    /**
     * Choose a film: follow its declared paper, and keep a paper only if it can
     * print the film. A slide film is scanned; leaving one for a negative
     * brings the print stage back. The Mac's `Session.selectFilmStock`.
     */
    fun selectFilm(id: String, catalog: StockCatalog = StockCatalog.shared): FilmSettings {
        val wasPositive = catalog.stock(filmStock)?.isPositive ?: false
        var print = printStock
        val target = catalog.stock(id)?.targetPrint
        if (target != null && catalog.stock(target) != null && !catalog.isDeclaredPairing(id, print)) print = target
        if (!catalog.canPrint(id, print)) print = target ?: FilmSettings().printStock
        val isPositive = catalog.stock(id)?.isPositive ?: false
        val scan = if (isPositive) true else if (wasPositive) false else scanFilm
        return copy(filmStock = id, printStock = print, scanFilm = scan)
    }

    val filmIsMonochrome get() = StockCatalog.shared.stock(filmStock)?.isMonochrome ?: false
    val printIsMonochrome get() = StockCatalog.shared.stock(printStock)?.isMonochrome ?: false

    /**
     * Every field, as `params_delta` names it. `aspect` is the photo's
     * long/short ratio, which turns the frame's short side into the wire's long
     * edge (the Mac's `Session.filmFormatMM`).
     */
    fun wire(aspect: Double): Map<String, Any> {
        val filmFormat = (FilmFrame.named(frame).shortSideMM * max(aspect, 1.0)).coerceIn(4.0, 200.0)
        val fields = linkedMapOf<String, Any>(
            "film_stock" to filmStock,
            "print_stock" to printStock,
            "scan_film" to scanFilm,
            "exposure_compensation_ev" to exposureEV.coerceIn(-8.0, 8.0),
            "auto_exposure" to (metering != Metering.Custom),
            "auto_exposure_method" to (if (metering == Metering.Custom) Metering.Balanced else metering).wire,
            "film_format_mm" to filmFormat,
            "grain_active" to grainActive,
            // Never on a B&W film (owner, 2026-10-09); see the Mac's wire.
            "grain_sublayers_active" to (grainActive && !filmIsMonochrome),
            "grain_amount" to (grain * ownGrain(filmStock)).coerceIn(0.0, 2.0),
            "halation_active" to halationActive,
            "halation_amount" to halation.coerceIn(0.0, 4.0),
            "dir_couplers_active" to couplersActive,
            "dir_couplers_amount" to couplers.coerceIn(0.0, 1.5),
            "glare_active" to glareActive,
            "glare_amount" to glare.coerceIn(0.0, 30.0),
            "print_exposure" to 2.0.pow(-printBrightness).coerceIn(0.05, 20.0),
            "y_filter_shift" to yellowShift.coerceIn(-1.0, 1.0),
            "m_filter_shift" to magentaShift.coerceIn(-1.0, 1.0),
            "filter_shift_scale" to FILTER_SHIFT_CC,
        )
        // B&W on its paper: the enlarger's pack, because the engine's table has
        // no row for a silver paper. Only then, as on the Mac.
        if (filmIsMonochrome && printIsMonochrome) {
            fields["c_filter_neutral"] = MULTIGRADE_FILTER_2[0]
            fields["m_filter_neutral"] = MULTIGRADE_FILTER_2[1]
            fields["y_filter_neutral"] = MULTIGRADE_FILTER_2[2]
        }
        return fields
    }

    /** The fields that differ from `previous`'s wire. */
    fun delta(previous: Map<String, Any>, aspect: Double): Map<String, Any> =
        wire(aspect).filter { (k, v) -> previous[k] != v }

    companion object {
        /** The Mac's `FilmParams.filterShiftCC`: the slider's ends, in CC. */
        const val FILTER_SHIFT_CC = 40.0
        /** Multigrade filter 2 (C, M, Y), the Mac's `FilmParams.multigradeFilter2`. */
        val MULTIGRADE_FILTER_2 = listOf(0.0, 0.0, 68.0)

        /** What a strength of 1 is on each B&W film, the Mac's `FilmParams.ownGrain`. */
        fun ownGrain(stock: String) = when (stock) {
            "kodak_tri_x_400" -> 1.70
            "ilford_hp5_plus_400" -> 1.49
            "kodak_tmax_100" -> 0.74
            "fujifilm_neopan_acros_100_ii" -> 0.64
            else -> 1.0
        }
    }
}

/** Session settings that are not part of a frame's look: the preview's size. */
object SessionWire {
    fun fields(previewLongEdge: Int): Map<String, Any> = mapOf("preview_long_edge" to previewLongEdge.toDouble())
}

/** A wire map as the JSON `params_delta` the engine reads. */
fun Map<String, Any>.toJson(): String = org.json.JSONObject(this).toString()
