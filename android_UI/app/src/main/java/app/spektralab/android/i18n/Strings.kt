// Strings.kt -- every user-visible string in the Android app, in both languages.
//
// The iOS app's table (ios_UI/.../Localization/Strings.swift), whose rules are
// the Mac's and apply unchanged:
//
//   * Names stay English. Film and paper names, format names (135, 120,
//     Super 8), CINE, EV, CC, K, MP, px, JPEG/PNG/TIFF are identifiers.
//   * The Mac's Chinese is reused where the concept is the same, so a reader
//     who knows one app knows the others: 胶片, 印相, 颗粒, 光晕, 耀光,
//     DIR 成色剂, 测光方式, 胶片曝光, 拍摄时设置, 不使用相纸配置, 放大机...
//   * Every key carries both languages; the constructor will not take one.
//
// Where Android names a thing differently from iOS (the gallery rather than
// Photos), the string says what Android says.
package app.spektralab.android.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class S(val en: String, val zh: String) {
    // library
    LibraryPrompt("Choose a photo to print it on film.", "选择一张照片，用胶片冲印它。"),
    LibraryFormats("RAW, DNG, JPEG, HEIC, PNG and TIFF", "RAW、DNG、JPEG、HEIC、PNG 与 TIFF"),
    ActionChoosePhoto("Choose from Gallery", "从相册中选择"),
    ActionOpenFile("Open from Files", "从文件打开"),

    // editor
    TabFilm("Film", "胶片"),
    TabPrint("Print", "印相"),
    TabCamera("Camera", "相机"),
    TabEffects("Effects", "效果"),
    TabFinish("Finish", "调整"),
    ActionExport("Export", "导出"),
    ActionOriginal("Original", "原图"),
    ActionDone("Done", "完成"),
    ActionReset("Reset", "重置"),
    ActionResetLook("Reset Exposure and Effects", "重置曝光与效果"),
    StatusStarting("Starting the engine…", "正在启动引擎…"),
    StatusDeveloping("Developing…", "显影中…"),
    StatusPrinting("Printing at full size…", "正在以完整尺寸冲印…"),
    HelpHoldForOriginal("Press and hold to see the original.", "按住可查看原图。"),

    // film
    FilmGroupNegative("Negative", "负片"),
    FilmGroupPositive("Positive", "正片"),
    FilmGroupBlackAndWhite("Black & White", "黑白"),
    FilmGroupCine("Cine", "电影"),
    FilmFormat("Film Format", "胶片画幅"),
    FilmFormatCaption(
        "Sets the scale of grain, halation and couplers. The short side is the format's; the long side follows the photo.",
        "决定颗粒、光晕与成色剂的尺度。短边取画幅尺寸，长边随照片比例。"),

    // print
    PrintNone("No Print Profile (scan the film)", "不使用相纸配置（直接扫描胶片）"),
    PrintPaper("Paper", "相纸"),
    PrintDeclared("The film's own paper", "胶片指定相纸"),
    PrintGroupCine("Cine", "电影"),
    SectionEnlarger("Enlarger", "放大机"),
    PrintBrightness("Print Brightness", "印相亮度"),
    PrintYellow("Yellow", "黄"),
    PrintMagenta("Magenta", "品红"),

    // camera
    CameraMetering("Metering", "测光方式"),
    MeteringCustom("Custom", "自定义"),
    MeteringBalanced("Balanced", "均衡"),
    MeteringCenter("Center", "中央主体"),
    MeteringProtectHighlights("Protect Highlights", "保护高光"),
    MeteringProtectShadows("Protect Shadows", "保护阴影"),
    CameraFilmExposure("Film Exposure", "胶片曝光"),
    CameraMetered("The meter chose %s EV; the slider adds to it.", "测光结果为 %s EV，滑块在此基础上增减。"),
    SectionWhiteBalance("White Balance", "白平衡"),
    CameraAsShot("As Shot", "拍摄时设置"),
    CameraTemperature("Temperature", "色温"),
    CameraTint("Tint", "色调"),
    CameraFlatWhiteBalance(
        "This photo is not RAW, so white balance is a correction on top of the camera's.",
        "这张照片不是 RAW，白平衡将在相机结果之上进行校正。"),

    // effects
    FilmGrain("Grain", "颗粒"),
    FilmHalation("Halation", "光晕"),
    FilmCouplers("DIR Couplers", "DIR 成色剂"),
    FilmGlare("Glare", "耀光"),
    EffectStrength("Strength", "强度"),

    // finish
    FinishCaption(
        "Applied to the finished print, like adjusting a scan of it. Not part of the film.",
        "作用于冲印完成的照片，相当于调整它的扫描件，不属于胶片本身。"),
    FinishExposure("Exposure", "曝光"),
    FinishContrast("Contrast", "对比度"),
    FinishSaturation("Saturation", "饱和度"),
    FinishHighlights("Highlights", "高光"),
    FinishShadows("Shadows", "阴影"),
    FinishVignette("Vignette", "暗角"),

    // export
    ExportFormat("Format", "格式"),
    ExportSize("Size", "尺寸"),
    ExportSizeFull("Full", "完整"),
    ExportSaveToGallery("Save to Gallery", "存储到相册"),
    ExportShare("Share…", "分享…"),
    ExportSaved("Saved to Pictures/SpektraLab.", "已存储到“图片/SpektraLab”。"),
    ExportFailed("Export failed: %s", "导出失败：%s"),

    // settings
    SettingsTitle("Settings", "设置"),
    SetLanguage("Language", "语言"),
    LanguageFollowSystem("Follow the system", "跟随系统"),
    SetRendering("Rendering", "渲染"),
    SetPreviewSize("Preview Size", "预览尺寸"),
    SetDevelopSize("Develop Size", "显影尺寸"),
    SetDevelopSizeCaption(
        "The largest frame the engine develops, and the full size of an export. Larger keeps more detail and needs more memory; 12 MP suits most phones. The GPU may cap it lower.",
        "引擎显影的最大画面，也是导出的完整尺寸。越大细节越多，占用内存也越多；12 MP 适合大多数手机。GPU 可能会将其限制得更低。"),
    SetAbout("About", "关于"),
    SetAboutText(
        "SpektraLab is free software under the GNU GPL v3 or later. It uses the spektrafilm engine; film and paper profiles © Andrea Volpato, CC BY-SA 4.0. RAW decoding by LibRaw (LGPL 2.1).",
        "SpektraLab 是遵循 GNU GPL v3 或更高版本的自由软件，使用 spektrafilm 引擎；胶片与相纸配置 © Andrea Volpato，CC BY-SA 4.0。RAW 解码使用 LibRaw（LGPL 2.1）。"),
    SetEngine("Engine", "引擎"),
    SetGpu("GPU", "GPU"),

    // errors
    ErrorEngine("The render engine could not start on this phone's GPU: %s", "渲染引擎无法在这台手机的 GPU 上启动：%s"),
    ErrorOpen("Could not open the photo: %s", "无法打开照片：%s"),
}

enum class LanguageSetting(val code: String) {
    System("system"), English("en"), Chinese("zh-Hans");

    val label: String
        get() = when (this) {
            System -> L(S.LanguageFollowSystem)
            English -> "English"
            Chinese -> "简体中文"
        }
}

/** The app's language, switched live from Settings and kept across launches. */
object Localization {
    private const val PREFS = "spektralab"
    private const val KEY = "language"
    private var context: Context? = null

    var language by mutableStateOf(LanguageSetting.System)
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        language = LanguageSetting.entries.firstOrNull { it.code == saved } ?: LanguageSetting.System
    }

    fun select(setting: LanguageSetting) {
        language = setting
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, setting.code)?.apply()
    }

    val isChinese: Boolean
        get() = when (language) {
            LanguageSetting.English -> false
            LanguageSetting.Chinese -> true
            LanguageSetting.System -> Locale.getDefault().language == "zh"
        }
}

/** A string in the current language; `args` fill its `%s`. */
fun L(key: S, vararg args: Any): String {
    val text = if (Localization.isChinese) key.zh else key.en
    return if (args.isEmpty()) text else String.format(Locale.ROOT, text, *args)
}
