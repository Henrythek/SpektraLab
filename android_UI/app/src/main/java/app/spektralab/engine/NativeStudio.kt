// NativeStudio.kt -- the engine, in this process, through libspektralab.so.
//
// The iOS app's `Engine` actor, for Android: the same engine (`engine/src`),
// reached through the same C surface (`spk_engine.h`), here on its Vulkan
// backend. The native side is `src/main/cpp/studio.cpp`; this file only
// declares it. Every call blocks on the GPU or the decoder, so nothing here
// may run on the main thread: the app makes every call from one worker
// thread (`EngineThread`), the way the iOS actor serialises them.
//
// Parameters cross as JSON (the engine's own rule 3, `spk_engine.h`); pixels
// come back as ARGB_8888 bitmaps in sRGB, the space the engine is asked to
// render into.
package app.spektralab.engine

import android.graphics.Bitmap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** A failure the engine, a decoder or a writer reported, with its own message. */
class EngineException(message: String) : RuntimeException(message)

object NativeStudio {
    init {
        System.loadLibrary("spektralab")
    }

    /** Version, build stamp and kernel math mode of the engine compiled in. */
    external fun buildInfo(): String

    /** Start the engine on `resources` (EngineAssets.kt). Returns engine info JSON. */
    external fun start(resources: String): String

    /** Open a RAW, TIFF, JPEG or PNG the native decoders read. Returns the open reply JSON. */
    external fun openFile(path: String, whiteBalance: String, delta: String, maxPixels: Long, previewEdge: Int): String

    /** Open an ARGB_8888 sRGB bitmap the platform decoded (HEIC, WebP, AVIF...). */
    external fun openPixels(bitmap: Bitmap, metadata: String, whiteBalance: String, delta: String,
                            maxPixels: Long, previewEdge: Int): String

    /** Apply `delta` and print at the live tier, finished. */
    external fun render(delta: String, finish: DoubleArray): Bitmap

    /** The last live print with another finish; null before the first render. */
    external fun refinish(finish: DoubleArray): Bitmap?

    /** The photo before the film: the decoder's display rendering. */
    external fun original(): Bitmap?

    /** Milliseconds the engine took for the last `render`. */
    external fun lastRenderMs(): Double

    /** What the meter would choose for the open frame, or NaN. */
    external fun meteredEv(): Double

    /** Render at full size, finish, scale and encode to `path`. Returns JSON: width, height, bytes. */
    external fun export(path: String, delta: String, finish: DoubleArray, format: String, longEdge: Int,
                        quality: Int): String

    /** Drop the open frame and everything the engine cached for it. */
    external fun close()
}

/** The one thread every NativeStudio call runs on. */
val EngineThread = Executors.newSingleThreadExecutor { r -> Thread(r, "spektralab-engine") }.asCoroutineDispatcher()
