// SpektraLab for Android: a Compose editor over the repository's own engine.
//
// The native library (src/main/cpp) is the engine's C++ on its Vulkan backend,
// LibRaw and the desktop host's decoders and writers, built by the NDK from
// the same sources the Linux and Windows desktop build uses. Two tasks here
// put the rest of the engine into the APK:
//
//   setupEngineDeps   engine/setup-deps.sh: LibRaw 0.22.2 and the Vulkan
//                     headers, pinned and verified, into <repo>/build/deps.
//   bundleEngine      the engine's data and kernels as assets -- the baked
//                     resources, the Vulkan kernels compiled to SPIR-V, the
//                     product's black-and-white profiles beside spektrafilm's,
//                     the Mac app's stock catalogue and film covers, and the
//                     licence texts the binary must carry. The iOS app's
//                     Tools/bundle-engine.sh, for Android.
//
// The app unpacks the `engine/` assets to its files directory on first launch
// (EngineAssets.kt), because the engine opens its resources from a directory.

import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repoRoot: File = rootProject.projectDir.parentFile
val engineDepsDir: File = File(repoRoot, "build/deps")

android {
    namespace = "app.spektralab.android"
    compileSdk = 35
    ndkVersion = providers.gradleProperty("spektralab.ndkVersion").getOrElse("27.2.12479018")

    defaultConfig {
        applicationId = "app.spektralab.android"
        // Android 10: scoped storage, so saving to the gallery needs no
        // permission; and every phone since has a Vulkan 1.0 compute queue.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Every phone of the last several years; the engine is too heavy
            // for the 32-bit devices that remain.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DSPEKTRALAB_DEPS_DIR=${engineDepsDir.absolutePath}",
                    "-DSPEKTRALAB_LIBRAW_SOURCE=${engineDepsDir.absolutePath}/LibRaw-0.22.2",
                    "-DSPEKTRALAB_VULKAN_HEADERS=${engineDepsDir.absolutePath}/Vulkan-Headers/include",
                )
                glslang()?.let { arguments += "-DSPEKTRALAB_GLSLANG=$it" }
            }
        }
    }

    // One debug key in the repository, so every build -- CI's or anyone's --
    // installs over the last one instead of being refused as a different app.
    // It signs debug builds only and is not a secret (the password is Android's
    // default); a store release needs its own key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// --- the engine's dependencies and data --------------------------------------

/** glslangValidator: a -Pspektralab.glslang path, the Vulkan SDK's, or PATH's. */
fun glslang(): String? {
    providers.gradleProperty("spektralab.glslang").orNull?.let { return it }
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "glslangValidator.exe" else "glslangValidator"
    val dirs = buildList {
        System.getenv("VULKAN_SDK")?.let { add(File(it, "bin")); add(File(it, "Bin")) }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(File(it)) }
    }
    return dirs.map { File(it, exe) }.firstOrNull { it.canExecute() }?.absolutePath
}

val setupEngineDeps by tasks.registering(Exec::class) {
    description = "Fetches and verifies LibRaw 0.22.2 and the Vulkan headers (engine/setup-deps.sh)"
    workingDir = repoRoot
    commandLine("bash", "engine/setup-deps.sh")
    onlyIf {
        !File(engineDepsDir, "LibRaw-0.22.2/libraw/libraw.h").exists() ||
            !File(engineDepsDir, "Vulkan-Headers/include/vulkan/vulkan_core.h").exists()
    }
}

abstract class BundleEngine : DefaultTask() {
    @get:InputDirectory abstract val engineDir: DirectoryProperty
    @get:InputDirectory abstract val macResources: DirectoryProperty
    @get:InputDirectory abstract val licenseDir: DirectoryProperty
    @get:Internal abstract val depsDir: DirectoryProperty
    @get:Input abstract val glslang: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val engine = engineDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dest = File(out, "engine")
        // Baked resources: constants, print LUTs, colour profiles, io/sRGB.icc.
        File(engine, "resources").copyRecursively(dest)
        // The product's black-and-white films and Multigrade paper go beside
        // spektrafilm's, because the engine opens a stock from one `profiles/`
        // directory -- as engine/build.sh bundle does for the Mac.
        File(engine, "resources_product/profiles").listFiles { f -> f.extension == "json" }!!.forEach {
            it.copyTo(File(dest, "profiles/${it.name}"), overwrite = true)
        }
        File(engine, "resources_product/NOTICE.md").copyTo(File(dest, "PRODUCT_PROFILES_NOTICE.md"))

        // Kernels: exactly the list engine/CMakeLists.txt compiles, read from
        // it so the two cannot drift, with the same compiler and flags.
        val cmake = File(engine, "CMakeLists.txt").readText()
        val list = Regex("""set\(SPEKTRALAB_VULKAN_KERNELS(.*?)CACHE INTERNAL""", RegexOption.DOT_MATCHES_ALL)
            .find(cmake)?.groupValues?.get(1) ?: throw GradleException("no SPEKTRALAB_VULKAN_KERNELS in engine/CMakeLists.txt")
        val kernels = list.split(Regex("\\s+")).filter { it.isNotBlank() }
        val compiler = glslang.get()
        if (compiler.isEmpty()) {
            throw GradleException(
                "glslangValidator was not found. Install the Vulkan SDK (or `apt install glslang-tools`), " +
                    "or pass -Pspektralab.glslang=/path/to/glslangValidator")
        }
        val shaders = File(engine, "src/shaders/vulkan")
        File(dest, "vulkan").mkdirs()
        for (kernel in kernels) {
            val log = ByteArrayOutputStream()
            val process = ProcessBuilder(compiler, "-V", "--target-env", "vulkan1.0", "-I${shaders.absolutePath}",
                "-o", File(dest, "vulkan/$kernel.spv").absolutePath, File(shaders, "$kernel.comp").absolutePath)
                .redirectErrorStream(true).start()
            process.inputStream.copyTo(log)
            if (process.waitFor() != 0) throw GradleException("glslangValidator failed on $kernel:\n$log")
        }

        // One catalogue for the Mac, iOS and Android apps.
        val mac = macResources.get().asFile
        File(mac, "StockCatalog.json").copyTo(File(out, "StockCatalog.json"))
        File(mac, "FilmCovers").copyRecursively(File(out, "FilmCovers"))

        // What the APK carries, and therefore what it must carry the
        // licences of: engine/stage-host.sh's list, for the same binary.
        val lic = File(out, "licenses")
        val license = licenseDir.get().asFile
        val deps = depsDir.get().asFile
        File(license, "LICENSE").copyTo(File(lic, "SpektraLab-GPL-3.0.txt"))
        File(license, "SPEKTRAFILM_LICENSE.txt").copyTo(File(lic, "spektrafilm-LICENSE.txt"))
        File(mac, "Licenses/Profiles-and-LUTs-CC-BY-SA-4.0.txt").copyTo(File(lic, "Profiles-and-LUTs-CC-BY-SA-4.0.txt"))
        File(mac, "Licenses/Profiles-and-LUTs-CHANGELOG.txt").copyTo(File(lic, "Profiles-and-LUTs-CHANGELOG.txt"))
        File(deps, "LibRaw-0.22.2/LICENSE.LGPL").copyTo(File(lic, "LibRaw-LGPL-2.1.txt"))
        File(deps, "LibRaw-0.22.2/COPYRIGHT").copyTo(File(lic, "LibRaw-COPYRIGHT.txt"))
        File(engine, "third_party/stb/LICENSE").copyTo(File(lic, "stb-LICENSE.txt"))
        File(engine, "third_party/volk/LICENSE.md").copyTo(File(lic, "volk-LICENSE.txt"))
        File(deps, "Vulkan-Headers/LICENSE.md").copyTo(File(lic, "Vulkan-Headers-LICENSE.txt"))
        File(engine, "resources_product/NOTICE.md").copyTo(File(lic, "Black-and-white-profiles-NOTICE.md"))
    }
}

val bundleEngine by tasks.registering(BundleEngine::class) {
    dependsOn(setupEngineDeps)
    engineDir.set(File(repoRoot, "engine"))
    macResources.set(File(repoRoot, "modern_UI/Spektrafilm/Spektrafilm/Resources"))
    licenseDir.set(File(repoRoot, "license"))
    depsDir.set(engineDepsDir)
    glslang.set(glslang() ?: "")
    outputDir.set(layout.buildDirectory.dir("generated/engineAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleEngine, BundleEngine::outputDir)
    }
}

// CMake needs LibRaw and the headers before it configures.
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(setupEngineDeps)
}
