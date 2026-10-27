// Finish.kt -- Layer 2 on the phone: adjustments to the finished print.
//
// The iOS app's `Finish`: the Mac's control names, ranges and order, zero is
// "nothing applied" for every one, never sent to the engine, applied
// identically to the preview and the export. The maths is studio.cpp's
// `apply_finish` on the linear print, which -- like iOS's Core Image filters --
// is not a port of the Mac's `layer2` shader, so the same numbers do not give
// an identical picture on every platform.
package app.spektralab.android.model

data class Finish(
    val exposure: Double = 0.0,     // stops, -3..3
    val contrast: Double = 0.0,     // -50..50
    val saturation: Double = 0.0,   // -100..100
    val highlights: Double = 0.0,   // -100..100
    val shadows: Double = 0.0,      // -100..100
    val temperature: Double = 0.0,  // -100..100, blue <-> amber
    val tint: Double = 0.0,         // -100..100, green <-> magenta
    val vignette: Double = 0.0,     // -100..100, negative darkens the corners
) {
    val isNeutral get() = this == Finish()

    /** The order jni.cpp's `finish_of` reads. */
    fun toArray() = doubleArrayOf(exposure, contrast, saturation, highlights, shadows, temperature, tint, vignette)
}
