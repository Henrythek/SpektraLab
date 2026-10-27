// EngineAssets.kt -- the engine's data and kernels, from the APK to a directory.
//
// `spk_engine_create` opens its resources from a directory, and an APK's
// assets are not one. So the `engine/` assets app/build.gradle.kts bundled
// (baked resources, SPIR-V kernels, every film and paper profile) are copied to
// the app's files directory once per installed build, keyed by the package's
// update time so a new APK replaces them and a relaunch does not.
package app.spektralab.engine

import android.content.Context
import java.io.File

object EngineAssets {
    private const val ROOT = "engine"

    fun ensure(context: Context): File {
        val dir = File(context.filesDir, ROOT)
        val stampFile = File(dir, ".stamp")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val stamp = "${info.longVersionCode}-${info.lastUpdateTime}"
        if (stampFile.isFile && stampFile.readText() == stamp) return dir
        dir.deleteRecursively()
        copy(context, ROOT, dir)
        stampFile.writeText(stamp)
        return dir
    }

    private fun copy(context: Context, asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copy(context, "$asset/$child", File(dest, child))
    }
}
