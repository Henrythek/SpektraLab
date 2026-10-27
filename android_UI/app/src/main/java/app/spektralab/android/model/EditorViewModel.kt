// EditorViewModel.kt -- one photo, its film settings, and the print on screen.
//
// The iOS app's `EditorModel`, for Compose. One frame at a time, no library,
// no sidecars; what it keeps from the Mac's `Session` is the flow --
//
//     decode  ->  engine open (with the full wire)  ->  meter  ->  print at the live tier
//
// -- and the rule that an edit sends only the fields it changed, so the engine
// re-develops the negative only when a shoot-layer field moved and otherwise
// reprints the cached one (API-SPEC §3's layers).
//
// Edits arriving while a render runs are coalesced: the loop renders the
// latest settings when the current render finishes, never every tick of a
// slider. White balance is the exception that costs a re-decode, because it
// happens before the engine, so its sliders commit when they are released.
// Finish never reaches the engine: it re-finishes the last print natively.
package app.spektralab.android.model

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spektralab.android.i18n.L
import app.spektralab.android.i18n.S
import app.spektralab.engine.EngineAssets
import app.spektralab.engine.EngineException
import app.spektralab.engine.EngineThread
import app.spektralab.engine.NativeStudio
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ExportFormat(val wire: String, val title: String, val extension: String, val mime: String) {
    Jpeg("jpeg", "JPEG", "jpg", "image/jpeg"),
    Png("png", "PNG", "png", "image/png"),
    Tiff("tiff16", "TIFF 16-bit", "tif", "image/tiff"),
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface Phase {
        data object Empty : Phase
        data object Opening : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Empty)
        private set
    /** The print, finished: what the canvas shows. */
    var displayImage by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The photo as the decoder renders it: what "hold for original" shows. */
    var originalImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var isRendering by mutableStateOf(false)
        private set
    var meteredEV by mutableStateOf<Double?>(null)
        private set
    var lastRenderMs by mutableStateOf<Double?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var asShotTemperature by mutableStateOf<Double?>(null)
        private set
    var asShotTint by mutableStateOf<Double?>(null)
        private set
    var isRaw by mutableStateOf(false)
        private set
    var engineInfo by mutableStateOf<JSONObject?>(null)
        private set

    var settings by mutableStateOf(FilmSettings())
        private set
    var finish by mutableStateOf(Finish())
        private set

    private val context get() = getApplication<Application>()
    private var source: PhotoSource? = null
    /** The platform's decode of a non-native photo, kept for white-balance re-opens. */
    private var platformBitmap: Bitmap? = null
    private var platformMetadata = "{}"
    private var aspect = 1.5
    /** The wire as the engine last received it. */
    private var sent: Map<String, Any> = emptyMap()
    private var renderJob: Job? = null
    private var dirty = false
    private var needsRedecode = false
    private var finishGeneration = 0
    private var engineStarted = false

    /** Every edit goes through here, so the render loop sees it. */
    fun update(transform: (FilmSettings) -> FilmSettings) {
        val old = settings
        val new = transform(old)
        if (new == old) return
        settings = new
        if (new.whiteBalance != old.whiteBalance) needsRedecode = true
        scheduleRender()
    }

    fun updateFinish(transform: (Finish) -> Finish) {
        val new = transform(finish)
        if (new == finish) return
        finish = new
        refreshFinish()
    }

    val showsEditor: Boolean
        get() = when (phase) {
            Phase.Empty -> false
            is Phase.Failed -> displayImage != null
            Phase.Opening, Phase.Ready -> true
        }

    // --- open ---------------------------------------------------------------

    fun open(uri: Uri) {
        phase = Phase.Opening
        // A new photo keeps the film and print but starts from its own white balance.
        settings = settings.copy(whiteBalance = WhiteBalance())
        needsRedecode = false
        displayImage = null
        originalImage = null
        errorMessage = null
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { PhotoSource.copy(context, uri) }
                source = photo
                platformBitmap?.recycle()
                platformBitmap = null
                develop()
            } catch (e: Exception) {
                phase = Phase.Failed(L(S.ErrorOpen, e.message ?: e.toString()))
            }
        }
    }

    private suspend fun startEngine() {
        if (engineStarted) return
        val info = withContext(EngineThread) {
            val dir = EngineAssets.ensure(context)
            NativeStudio.start(dir.absolutePath)
        }
        engineInfo = runCatching { JSONObject(info) }.getOrNull()
        engineStarted = true
    }

    /** Decode the source (again) and open it in the engine with the whole wire. */
    private suspend fun develop() {
        val photo = source ?: return
        isRendering = true
        try {
            try {
                startEngine()
            } catch (e: EngineException) {
                phase = Phase.Failed(L(S.ErrorEngine, e.message ?: ""))
                return
            }
            val wb = settings.whiteBalance.json()
            val maxPixels = AppSettings.developMegapixels * 1_000_000L
            val edge = AppSettings.previewLongEdge
            val wire = settings.wire(aspect)
            val openDelta = (wire + SessionWire.fields(edge)).toJson()
            val result = withContext(EngineThread) {
                val reply = openSource(photo, wb, openDelta, maxPixels, edge)
                val original = NativeStudio.original()
                val ev = NativeStudio.meteredEv()
                Triple(JSONObject(reply), original, ev)
            }
            val (reply, original, ev) = result
            val w = reply.optDouble("width", 3.0)
            val h = reply.optDouble("height", 2.0)
            aspect = max(w, h) / max(1.0, min(w, h))
            isRaw = reply.optBoolean("is_raw", false)
            val asShot = reply.optJSONObject("metadata")?.optJSONObject("as_shot")
            asShotTemperature = asShot?.optDouble("temperature_k")
            asShotTint = asShot?.optDouble("tint")
            originalImage = original?.asImageBitmap()
            meteredEV = ev.takeUnless { it.isNaN() }
            sent = wire
            // The first print, with whatever the real aspect changed in the wire.
            val delta = settings.delta(sent, aspect)
            val print = withContext(EngineThread) { NativeStudio.render(delta.toJson(), finish.toArray()) }
            sent = sent + delta
            lastRenderMs = withContext(EngineThread) { NativeStudio.lastRenderMs() }
            displayImage = print.asImageBitmap()
            errorMessage = null
            phase = Phase.Ready
        } catch (e: Exception) {
            phase = Phase.Failed(e.message ?: e.toString())
        } finally {
            isRendering = false
        }
        // Edits made while the frame was opening were not in the open's wire.
        if (dirty) scheduleRender()
    }

    /** On EngineThread: the native decoders where they apply, else the platform's. */
    private fun openSource(photo: PhotoSource, wb: String, delta: String, maxPixels: Long, edge: Int): String {
        if (photo.isNative && platformBitmap == null) {
            try {
                return NativeStudio.openFile(photo.file.absolutePath, wb, delta, maxPixels, edge)
            } catch (e: EngineException) {
                // A RAW the decoder cannot read has nothing to fall back to.
                if (photo.isRawName) throw e
            }
        }
        val bitmap = platformBitmap ?: PlatformDecoder.decode(photo.file, maxPixels).also {
            platformBitmap = it
            platformMetadata = PlatformDecoder.metadata(photo.file)
        }
        return NativeStudio.openPixels(bitmap, platformMetadata, wb, delta, maxPixels, edge)
    }

    // --- edits ----------------------------------------------------------------

    private fun scheduleRender() {
        dirty = true
        if (renderJob != null || phase != Phase.Ready) return
        renderJob = viewModelScope.launch {
            try {
                renderLoop()
            } finally {
                renderJob = null
            }
        }
    }

    private suspend fun renderLoop() {
        while (dirty) {
            dirty = false
            // A new photo opening takes over; its open sends the whole wire.
            if (phase != Phase.Ready) return
            if (needsRedecode) {
                needsRedecode = false
                develop()
                continue
            }
            val delta = settings.delta(sent, aspect)
            if (delta.isEmpty()) continue
            isRendering = true
            try {
                val finishNow = finish
                val print = withContext(EngineThread) { NativeStudio.render(delta.toJson(), finishNow.toArray()) }
                sent = sent + delta
                if (delta.keys.any { it in METER_KEYS }) {
                    meteredEV = withContext(EngineThread) { NativeStudio.meteredEv() }.takeUnless { it.isNaN() }
                }
                lastRenderMs = withContext(EngineThread) { NativeStudio.lastRenderMs() }
                displayImage = print.asImageBitmap()
                errorMessage = null
                // A finish change made during the render is not in this print.
                if (finishNow != finish) refreshFinish()
            } catch (e: EngineException) {
                errorMessage = e.message
                // The engine refused the delta; resend the whole wire next time.
                sent = emptyMap()
            } finally {
                isRendering = false
            }
        }
    }

    private fun refreshFinish() {
        if (phase != Phase.Ready) return
        val generation = ++finishGeneration
        val f = finish
        viewModelScope.launch {
            val out = withContext(EngineThread) {
                if (generation != finishGeneration) null else NativeStudio.refinish(f.toArray())
            }
            if (out != null && generation == finishGeneration) displayImage = out.asImageBitmap()
        }
    }

    /** The preview size changed in Settings: a session field, sent on its own. */
    fun applyPreviewSize() {
        if (phase != Phase.Ready) return
        viewModelScope.launch {
            runCatching {
                val print = withContext(EngineThread) {
                    NativeStudio.render(SessionWire.fields(AppSettings.previewLongEdge).toJson(), finish.toArray())
                }
                displayImage = print.asImageBitmap()
            }
        }
    }

    /** The develop size changed in Settings: decode and open again. */
    fun applyDevelopSize() {
        if (phase != Phase.Ready) return
        platformBitmap?.recycle()
        platformBitmap = null
        needsRedecode = true
        scheduleRender()
    }

    fun resetLook() {
        val s = settings
        update { FilmSettings(filmStock = s.filmStock, printStock = s.printStock, scanFilm = s.scanFilm,
                              whiteBalance = s.whiteBalance) }
        updateFinish { Finish() }
    }

    // --- export -----------------------------------------------------------------

    /** The print at the frame's full develop size, finished and encoded into the cache. */
    suspend fun export(format: ExportFormat, longEdge: Int): File {
        val photo = source ?: throw EngineException("no photo is open")
        if (phase != Phase.Ready) throw EngineException("no photo is open")
        // Whatever the canvas has not sent yet goes with the export.
        val delta = settings.delta(sent, aspect)
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val base = photo.displayName.substringBeforeLast('.').ifEmpty { "Photo" }
        val file = File(folder, "$base SpektraLab.${format.extension}")
        isRendering = true
        try {
            withContext(EngineThread) {
                NativeStudio.export(file.absolutePath, delta.toJson(), finish.toArray(), format.wire, longEdge, 92)
            }
            sent = sent + delta
        } finally {
            isRendering = false
        }
        return file
    }

    /** Add a finished file to the gallery, in Pictures/SpektraLab. No permission on Android 10+. */
    suspend fun saveToGallery(file: File, format: ExportFormat) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektraLab")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("the gallery refused the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("cannot write to the gallery")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** A chooser to send a finished file to another app. */
    fun shareIntent(file: File, format: ExportFormat): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here; the engine thread outlives it.
        CoroutineScope(EngineThread).launch { runCatching { NativeStudio.close() } }
        super.onCleared()
    }

    private companion object {
        val METER_KEYS = setOf("film_stock", "auto_exposure", "auto_exposure_method")
    }
}
