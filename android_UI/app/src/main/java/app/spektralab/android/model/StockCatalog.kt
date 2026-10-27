// StockCatalog.kt -- the film and paper library, read from the APK.
//
// The catalogue is the Mac app's own `Resources/StockCatalog.json`, bundled by
// app/build.gradle.kts as the iOS app bundles it, so the three apps cannot
// list different stocks. The rules are the Mac's (and iOS's): grouping on
// `type`, a declared pairing, and black and white printing only on silver
// paper. Black and white is listed: its profiles ship in this APK.
package app.spektralab.android.model

import android.content.Context
import org.json.JSONObject

data class Stock(
    val id: String,
    val name: String,
    val stage: String,          // "filming" | "printing"
    val use: String,            // "still" | "cine"
    val type: String?,
    val targetPrint: String?,
    val cover: String?,
    val isMonochrome: Boolean,
) {
    val isFilm get() = stage == "filming"
    val isPaper get() = stage == "printing"
    val isCine get() = use == "cine"
    /** A reversal (slide) film: already a viewable image, scanned, not printed. */
    val isPositive get() = type == "positive"
}

class StockCatalog(val stocks: List<Stock>) {
    enum class FilmGroup { Negative, Positive, BlackAndWhite, Cine }

    val films get() = stocks.filter { it.isFilm }
    val papers get() = stocks.filter { it.isPaper }
    fun stock(id: String) = stocks.firstOrNull { it.id == id }

    /** Still before cine, as on the Mac; colour negative, slide, then B&W. */
    fun films(group: FilmGroup): List<Stock> = when (group) {
        FilmGroup.Negative -> films.filter { !it.isCine && !it.isPositive && !it.isMonochrome }
        FilmGroup.Positive -> films.filter { !it.isCine && it.isPositive }
        FilmGroup.BlackAndWhite -> films.filter { !it.isCine && it.isMonochrome }
        FilmGroup.Cine -> films.filter { it.isCine }
    }

    /** Silver on silver, colour on colour: a B&W negative on colour paper prints orange. */
    fun canPrint(film: String, paper: String): Boolean {
        val f = stock(film) ?: return true
        val p = stock(paper) ?: return true
        return f.isMonochrome == p.isMonochrome
    }

    fun isDeclaredPairing(film: String, paper: String) = stock(film)?.targetPrint == paper

    companion object {
        @Volatile var shared = StockCatalog(emptyList())
            private set

        fun load(context: Context) {
            val text = context.assets.open("StockCatalog.json").bufferedReader().use { it.readText() }
            val array = JSONObject(text).getJSONArray("stocks")
            shared = StockCatalog((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Stock(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    stage = o.getString("stage"),
                    use = o.optString("use", "still"),
                    type = o.optStringOrNull("type"),
                    targetPrint = o.optStringOrNull("targetPrint"),
                    cover = o.optStringOrNull("cover"),
                    isMonochrome = o.optBoolean("monochrome", false),
                )
            })
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
