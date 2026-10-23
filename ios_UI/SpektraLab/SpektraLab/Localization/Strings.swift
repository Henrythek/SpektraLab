//  Strings.swift — every user-visible string in the iOS app, in both languages.
//
//  The Mac's rules (`modern_UI/.../Localization/Strings.swift` and
//  `design/LOCALIZATION-zh-Hans.md`) apply unchanged:
//
//    * **Names stay English.** Film and paper names, format names (135, 120,
//      Super 8), CINE, EV, CC, K, MP, px, JPEG/HEIC/TIFF are identifiers.
//    * **The Mac's Chinese is reused where the concept is the same**, so a
//      reader who knows one app knows the other: 胶片, 印相, 颗粒, 光晕, 耀光,
//      DIR 成色剂, 测光方式, 胶片曝光, 拍摄时设置, 不使用相纸配置, 放大机…
//    * There is no `default:` — a key without both languages does not compile.

import Foundation

enum S: String, CaseIterable, Sendable {
    // library
    case libraryPrompt, libraryFormats, actionChoosePhoto, actionOpenFile
    // editor
    case tabFilm, tabPrint, tabCamera, tabEffects, tabFinish
    case actionExport, actionOriginal, actionDone, actionReset, actionResetLook
    case statusDeveloping, statusPrinting, helpHoldForOriginal
    // film
    case filmGroupNegative, filmGroupPositive, filmGroupBlackAndWhite, filmGroupCine
    case filmFormat, filmFormatCaption
    // print
    case printNone, printPaper, printDeclared, printGroupCine, sectionEnlarger
    case printBrightness, printYellow, printMagenta
    // camera
    case cameraMetering, meteringCustom, meteringBalanced, meteringCenter
    case meteringProtectHighlights, meteringProtectShadows
    case cameraFilmExposure, cameraMetered
    case sectionWhiteBalance, cameraAsShot, cameraTemperature, cameraTint, cameraFlatWhiteBalance
    // effects
    case filmGrain, filmHalation, filmCouplers, filmGlare, effectStrength
    // finish
    case finishCaption, finishExposure, finishContrast, finishSaturation
    case finishHighlights, finishShadows, finishVignette
    // export
    case exportFormat, exportSize, exportSizeFull, exportSaveToPhotos, exportShare
    case exportSaved, exportFailed, exportPhotosDenied
    // settings
    case settingsTitle, setLanguage, languageFollowSystem, setRendering
    case setPreviewSize, setDevelopSize, setDevelopSizeCaption
    case setAbout, setAboutText, setEngine

    var text: (en: String, zh: String) {
        switch self {
        case .libraryPrompt: ("Choose a photo to print it on film.", "选择一张照片，用胶片冲印它。")
        case .libraryFormats: ("RAW, ProRAW, HEIC, JPEG, PNG and TIFF", "RAW、ProRAW、HEIC、JPEG、PNG 与 TIFF")
        case .actionChoosePhoto: ("Choose from Photos", "从照片中选择")
        case .actionOpenFile: ("Open from Files", "从文件打开")

        case .tabFilm: ("Film", "胶片")
        case .tabPrint: ("Print", "印相")
        case .tabCamera: ("Camera", "相机")
        case .tabEffects: ("Effects", "效果")
        case .tabFinish: ("Finish", "调整")
        case .actionExport: ("Export", "导出")
        case .actionOriginal: ("Original", "原图")
        case .actionDone: ("Done", "完成")
        case .actionReset: ("Reset", "重置")
        case .actionResetLook: ("Reset Exposure and Effects", "重置曝光与效果")
        case .statusDeveloping: ("Developing…", "显影中…")
        case .statusPrinting: ("Printing at full size…", "正在以完整尺寸冲印…")
        case .helpHoldForOriginal: ("Press and hold to see the original.", "按住可查看原图。")

        case .filmGroupNegative: ("Negative", "负片")
        case .filmGroupPositive: ("Positive", "正片")
        case .filmGroupBlackAndWhite: ("Black & White", "黑白")
        case .filmGroupCine: ("Cine", "电影")
        case .filmFormat: ("Film Format", "胶片画幅")
        case .filmFormatCaption:
            ("Sets the scale of grain, halation and couplers. The short side is the format's; the long side follows the photo.",
             "决定颗粒、光晕与成色剂的尺度。短边取画幅尺寸，长边随照片比例。")

        case .printNone: ("No Print Profile (scan the film)", "不使用相纸配置（直接扫描胶片）")
        case .printPaper: ("Paper", "相纸")
        case .printDeclared: ("The film's own paper", "胶片指定相纸")
        case .printGroupCine: ("Cine", "电影")
        case .sectionEnlarger: ("Enlarger", "放大机")
        case .printBrightness: ("Print Brightness", "印相亮度")
        case .printYellow: ("Yellow", "黄")
        case .printMagenta: ("Magenta", "品红")

        case .cameraMetering: ("Metering", "测光方式")
        case .meteringCustom: ("Custom", "自定义")
        case .meteringBalanced: ("Balanced", "均衡")
        case .meteringCenter: ("Center", "中央主体")
        case .meteringProtectHighlights: ("Protect Highlights", "保护高光")
        case .meteringProtectShadows: ("Protect Shadows", "保护阴影")
        case .cameraFilmExposure: ("Film Exposure", "胶片曝光")
        case .cameraMetered: ("The meter chose %@ EV; the slider adds to it.", "测光结果为 %@ EV，滑块在此基础上增减。")
        case .sectionWhiteBalance: ("White Balance", "白平衡")
        case .cameraAsShot: ("As Shot", "拍摄时设置")
        case .cameraTemperature: ("Temperature", "色温")
        case .cameraTint: ("Tint", "色调")
        case .cameraFlatWhiteBalance:
            ("This photo is not RAW, so white balance is a correction on top of the camera's.",
             "这张照片不是 RAW，白平衡将在相机结果之上进行校正。")

        case .filmGrain: ("Grain", "颗粒")
        case .filmHalation: ("Halation", "光晕")
        case .filmCouplers: ("DIR Couplers", "DIR 成色剂")
        case .filmGlare: ("Glare", "耀光")
        case .effectStrength: ("Strength", "强度")

        case .finishCaption:
            ("Applied to the finished print, like adjusting a scan of it. Not part of the film.",
             "作用于冲印完成的照片，相当于调整它的扫描件，不属于胶片本身。")
        case .finishExposure: ("Exposure", "曝光")
        case .finishContrast: ("Contrast", "对比度")
        case .finishSaturation: ("Saturation", "饱和度")
        case .finishHighlights: ("Highlights", "高光")
        case .finishShadows: ("Shadows", "阴影")
        case .finishVignette: ("Vignette", "暗角")

        case .exportFormat: ("Format", "格式")
        case .exportSize: ("Size", "尺寸")
        case .exportSizeFull: ("Full", "完整")
        case .exportSaveToPhotos: ("Save to Photos", "存储到照片")
        case .exportShare: ("Share…", "分享…")
        case .exportSaved: ("Saved to Photos.", "已存储到照片。")
        case .exportFailed: ("Export failed: %@", "导出失败：%@")
        case .exportPhotosDenied:
            ("SpektraLab is not allowed to add to your photo library. Allow it in Settings › Privacy › Photos.",
             "SpektraLab 没有添加到照片图库的权限。请在“设置 › 隐私与安全性 › 照片”中允许。")

        case .settingsTitle: ("Settings", "设置")
        case .setLanguage: ("Language", "语言")
        case .languageFollowSystem: ("Follow the system", "跟随系统")
        case .setRendering: ("Rendering", "渲染")
        case .setPreviewSize: ("Preview Size", "预览尺寸")
        case .setDevelopSize: ("Develop Size", "显影尺寸")
        case .setDevelopSizeCaption:
            ("The largest frame the engine develops, and the full size of an export. Larger keeps more detail and needs more memory; 12 MP suits most iPhones.",
             "引擎显影的最大画面，也是导出的完整尺寸。越大细节越多，占用内存也越多；12 MP 适合大多数 iPhone。")
        case .setAbout: ("About", "关于")
        case .setAboutText:
            ("SpektraLab is free software under the GNU GPL v3 or later. It uses the spektrafilm engine; film and paper profiles © Andrea Volpato, CC BY-SA 4.0.",
             "SpektraLab 是遵循 GNU GPL v3 或更高版本的自由软件，使用 spektrafilm 引擎；胶片与相纸配置 © Andrea Volpato，CC BY-SA 4.0。")
        case .setEngine: ("Engine", "引擎")
        }
    }
}
