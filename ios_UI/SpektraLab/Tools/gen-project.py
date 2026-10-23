#!/usr/bin/env python3
"""Regenerate SpektraLab.xcodeproj (the iOS / iPadOS app) from the filesystem.

    Tools/gen-project.py            # write
    Tools/gen-project.py --check    # exit 1 if the checked-in files are stale

The iOS twin of `modern_UI/Spektrafilm/Tools/gen-project.py`, and deliberately
the same shape: object ids are sha1s of paths *relative to the project*, so two
checkouts agree and a second run is byte-identical; every Swift file under
`SpektraLab/` joins the app target; the C++ engine joins it as sources.

Standard library only, so it runs on the Windows machine and the Linux
container this project was started from, not just on a Mac.

What differs from the Mac generator, and why:

  * **SDKROOT = iphoneos**, iPhone and iPad (TARGETED_DEVICE_FAMILY 1,2),
    iOS 18 and later. 18 is the floor because the app uses `@Observable`,
    `PhotosPicker` with `preferredItemEncoding` and Swift 6's concurrency
    checking without availability fences everywhere.
  * **The engine's kernels and data are copied in by a build phase**
    (`Tools/bundle-engine.sh`) instead of being synced into a Resources folder
    beforehand. The Mac app ships one metallib for one platform, so
    `engine/build.sh bundle` can make it once. An iOS build is for the device
    *or* the simulator, and a metallib built for one does not load on the
    other, so the kernels have to be compiled for `$PLATFORM_NAME` at build
    time. Same compiler flags as `engine/build.sh` (RFC-014 §5.1 trap 1: safe
    math, precise fp32), kept in that script next to the reason.
  * **`StockCatalog.json`, the film covers and the licence texts are read
    from the Mac app's Resources**, by the same build phase, so there is one
    catalogue and one set of licence files in the repository rather than two
    that drift.
  * `*.lproj/` folders become variant groups, for `InfoPlist.strings`: the
    permission prompt for saving to Photos is shown by iOS, not by the app, so
    it cannot go through the app's own string table.

Signing comes from the environment, as on the Mac. With nothing set the
project uses automatic signing with no team, which is what Xcode needs to let
a person pick their own team in the Signing tab, and what CI overrides with
CODE_SIGNING_ALLOWED=NO:

    SPEKTRALAB_IOS_TEAM_ID=ABCDE12345 Tools/gen-project.py
"""
from __future__ import annotations

import hashlib
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
ENGINE = REPO / "engine"
MAC_APP = REPO / "modern_UI" / "Spektrafilm" / "Spektrafilm"

APP = "SpektraLab"
PRODUCT_NAME = "SpektraLab"
# The Mac app's identifier, so the two can be one App Store record (universal
# purchase) if that is ever wanted. A personal team that cannot register it
# overrides it here or in Xcode's Signing tab.
BUNDLE_ID = os.environ.get("SPEKTRALAB_IOS_BUNDLE_ID", "com.hanze.filmify")
IOS = "18.0"
MARKETING_VERSION = "1.0.0"
BUILD_NUMBER = "1"
TEAM_ID = os.environ.get("SPEKTRALAB_IOS_TEAM_ID", "")

# The Icon Composer document the Mac app is built with. Xcode 26's `actool`
# compiles one document into every platform's icon set.
ICON = MAC_APP / "SpektraLab.icon"


def uid(key: str) -> str:
    return hashlib.sha1(key.encode()).hexdigest()[:24].upper()


def rel(path: Path) -> str:
    return Path(os.path.relpath(path, ROOT)).as_posix()


def engine_sources() -> list[Path]:
    """The engine's Apple translation units: the Mac generator's list exactly."""
    windows_dirs = {"io", "desktop"}
    windows_files = {"gpu/vulkan_gpu.cpp", "pipeline/windows_unported.cpp"}
    root = ENGINE / "src"
    return sorted(p for p in root.rglob("*.cpp")
                  if p.relative_to(root).parts[0] not in windows_dirs
                  and p.relative_to(root).as_posix() not in windows_files)


def file_type(p: Path) -> str:
    return {".swift": "sourcecode.swift", ".h": "sourcecode.c.h",
            ".plist": "text.plist.xml", ".strings": "text.plist.strings",
            ".xcassets": "folder.assetcatalog", ".icon": "folder.iconcomposer.icon",
            ".md": "net.daringfireball.markdown",
            ".py": "text.script.python", ".sh": "text.script.sh"}.get(p.suffix, "text")


class Project:
    def __init__(self):
        self.objects: list[str] = []

    def add(self, id_: str, body: str, comment: str = ""):
        c = f" /* {comment} */" if comment else ""
        self.objects.append(f"\t\t{id_}{c} = {body};")

    def group_body(self, children: list[tuple[str, str]], extra: str) -> str:
        kids = ",\n".join(f"\t\t\t\t{cid} /* {cname} */" for cid, cname in children)
        return (f"{{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n{kids}\n\t\t\t);\n"
                f"{extra}\t\t\tsourceTree = \"<group>\";\n\t\t}}")

    def group(self, dir_: Path, files: list[tuple[str, Path]]) -> str:
        gid = uid("group:" + rel(dir_))
        children: list[tuple[str, str]] = []
        variants: dict[str, list[Path]] = {}
        for entry in sorted(dir_.iterdir(), key=lambda p: (p.is_file(), p.name.lower())):
            if entry.name.startswith(".") or entry.suffix == ".xcodeproj":
                continue
            if entry.is_dir() and entry.suffix == ".lproj":
                for f in sorted(entry.iterdir()):
                    variants.setdefault(f.name, []).append(f)
                continue
            if entry.is_dir() and entry.suffix not in (".xcassets",):
                if entry.name in ("build", "DerivedData"):
                    continue
                children.append((self.group(entry, files), entry.name))
                continue
            fid = uid("file:" + rel(entry))
            self.add(fid, f"{{isa = PBXFileReference; lastKnownFileType = {file_type(entry)}; "
                          f"path = \"{entry.name}\"; sourceTree = \"<group>\"; }}", entry.name)
            children.append((fid, entry.name))
            files.append((fid, entry))
        for name, paths in sorted(variants.items()):
            vid = uid("variant:" + rel(dir_ / name))
            kids = []
            for f in paths:
                region = f.parent.stem
                fid = uid("file:" + rel(f))
                self.add(fid, f"{{isa = PBXFileReference; lastKnownFileType = {file_type(f)}; "
                              f"name = \"{region}\"; path = \"{f.parent.name}/{f.name}\"; "
                              f"sourceTree = \"<group>\"; }}", region)
                kids.append(f"\t\t\t\t{fid} /* {region} */")
            self.add(vid, "{\n\t\t\tisa = PBXVariantGroup;\n\t\t\tchildren = (\n" + ",\n".join(kids)
                          + f"\n\t\t\t);\n\t\t\tname = \"{name}\";\n\t\t\tsourceTree = \"<group>\";\n\t\t}}", name)
            children.append((vid, name))
            files.append((vid, dir_ / name))
        self.add(gid, self.group_body(children, f"\t\t\tpath = \"{dir_.name}\";\n"), dir_.name)
        return gid


def build() -> str:
    p = Project()
    app_files: list[tuple[str, Path]] = []
    app_group = p.group(ROOT / APP, app_files)
    tools_files: list[tuple[str, Path]] = []
    tools_group = p.group(ROOT / "Tools", tools_files)

    # The engine's translation units, referenced where they live.
    engine_files: list[tuple[str, Path]] = []
    engine_children = []
    for src in engine_sources():
        fid = uid("file:engine:" + rel(src))
        p.add(fid, f"{{isa = PBXFileReference; lastKnownFileType = sourcecode.cpp.cpp; "
                   f"name = \"{src.name}\"; path = \"{rel(src)}\"; sourceTree = SOURCE_ROOT; }}", src.name)
        engine_children.append((fid, src.name))
        engine_files.append((fid, src))
    engine_group = uid("group:engine")
    p.add(engine_group, p.group_body(engine_children, "\t\t\tname = Engine;\n"), "Engine")

    # The shared icon document, outside this tree.
    icon_ref = uid("file:icon:" + rel(ICON))
    p.add(icon_ref, f"{{isa = PBXFileReference; lastKnownFileType = folder.iconcomposer.icon; "
                    f"name = \"{ICON.name}\"; path = \"{rel(ICON)}\"; sourceTree = SOURCE_ROOT; }}", ICON.name)
    shared_group = uid("group:shared")
    p.add(shared_group, p.group_body([(icon_ref, ICON.name)], "\t\t\tname = \"Shared with macOS\";\n"),
          "Shared with macOS")

    app_product = uid("product:app")
    p.add(app_product, f"{{isa = PBXFileReference; explicitFileType = wrapper.application; "
                       f"includeInIndex = 0; path = {PRODUCT_NAME}.app; sourceTree = BUILT_PRODUCTS_DIR; }}",
          f"{PRODUCT_NAME}.app")
    products = uid("group:products")
    p.add(products, p.group_body([(app_product, f"{PRODUCT_NAME}.app")], "\t\t\tname = Products;\n"), "Products")
    main_group = uid("group:main")
    p.add(main_group, p.group_body([(app_group, APP), (engine_group, "Engine"),
                                    (shared_group, "Shared with macOS"), (tools_group, "Tools"),
                                    (products, "Products")], ""))

    def phase(kind: str, files: list[tuple[str, Path]], pred, tag: str) -> str:
        ids = []
        for fid, path in files:
            if pred(path):
                bid = uid(f"build:{tag}:{rel(path)}")
                p.add(bid, f"{{isa = PBXBuildFile; fileRef = {fid}; }}", path.name)
                ids.append(f"\t\t\t\t{bid} /* {path.name} */")
        pid = uid(f"phase:{tag}:{kind}")
        p.add(pid, f"{{\n\t\t\tisa = {kind};\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n"
                   + ",\n".join(ids) + "\n\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t}", tag)
        return pid

    is_src = lambda q: q.suffix in (".swift", ".cpp")
    is_res = lambda q: q.suffix in (".xcassets", ".icon", ".strings")
    sources = phase("PBXSourcesBuildPhase", app_files + engine_files, is_src, "app-sources")
    resources = phase("PBXResourcesBuildPhase", app_files + [(icon_ref, ICON)], is_res, "app-resources")
    frameworks = phase("PBXFrameworksBuildPhase", [], lambda q: False, "app-frameworks")

    bundle_engine = uid("phase:app:bundle-engine")
    p.add(bundle_engine,
          "{\n\t\t\tisa = PBXShellScriptBuildPhase;\n\t\t\talwaysOutOfDate = 1;\n\t\t\tbuildActionMask = 2147483647;"
          "\n\t\t\tfiles = (\n\t\t\t);\n\t\t\tinputPaths = (\n\t\t\t);"
          "\n\t\t\tname = \"Bundle engine kernels and data\";\n\t\t\toutputPaths = (\n\t\t\t);"
          "\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t\tshellPath = /bin/sh;"
          "\n\t\t\tshellScript = \"\\\"$SRCROOT/Tools/bundle-engine.sh\\\"\\n\";\n\t\t}",
          "Bundle engine kernels and data")

    engine_cfg = {
        "CLANG_CXX_LANGUAGE_STANDARD": '"c++20"',
        "CLANG_CXX_LIBRARY": '"libc++"',
        "GCC_C_LANGUAGE_STANDARD": "c11",
        "HEADER_SEARCH_PATHS": "(\n" + "".join(
            f'\t\t\t\t\t"$(SRCROOT)/../../engine/{sub}",\n'
            for sub in ("include", "src", "src/core", "third_party/metal-cpp")
        ) + "\t\t\t\t)",
        "SWIFT_OBJC_BRIDGING_HEADER": f'"{APP}/Engine/{APP}-Bridging-Header.h"',
        "OTHER_LDFLAGS": '"-framework Metal -framework QuartzCore -framework CoreText '
                         '-framework CoreGraphics -framework Accelerate"',
        # metal-cpp manages its own retains; see the Mac generator.
        "CLANG_ENABLE_OBJC_ARC": "NO",
    }
    app_cfg = {
        **engine_cfg,
        "SWIFT_VERSION": "6.0",
        "SWIFT_STRICT_CONCURRENCY": "complete",
        "SDKROOT": "iphoneos",
        "SUPPORTED_PLATFORMS": '"iphoneos iphonesimulator"',
        "IPHONEOS_DEPLOYMENT_TARGET": IOS,
        "TARGETED_DEVICE_FAMILY": '"1,2"',
        "CODE_SIGN_STYLE": "Automatic",
        "DEVELOPMENT_TEAM": f'"{TEAM_ID}"',
        "CLANG_ENABLE_MODULES": "YES",
        "MARKETING_VERSION": MARKETING_VERSION,
        "CURRENT_PROJECT_VERSION": BUILD_NUMBER,
        "GENERATE_INFOPLIST_FILE": "NO",
        "INFOPLIST_FILE": f"{APP}/Info.plist",
        "PRODUCT_NAME": PRODUCT_NAME,
        "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE_ID,
        "ASSETCATALOG_COMPILER_APPICON_NAME": PRODUCT_NAME,
        "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
        "LD_RUNPATH_SEARCH_PATHS": '"$(inherited) @executable_path/Frameworks"',
        "ENABLE_PREVIEWS": "YES",
        "SWIFT_EMIT_LOC_STRINGS": "NO",
    }

    def cfg(tag: str, name: str, settings: dict, extra: dict) -> str:
        cid = uid(f"cfg:{tag}:{name}")
        merged = {**settings, **extra}
        body = "\n".join(f"\t\t\t\t{k} = {v};" for k, v in sorted(merged.items()))
        p.add(cid, f"{{\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {{\n{body}\n\t\t\t}};"
                   f"\n\t\t\tname = {name};\n\t\t}}", name)
        return cid

    # -O2 for the engine's C++ in Debug too, as on the Mac (owner, 2026-10-03).
    debug = {"SWIFT_OPTIMIZATION_LEVEL": '"-Onone"', "DEBUG_INFORMATION_FORMAT": "dwarf",
             "ENABLE_TESTABILITY": "YES", "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG",
             "GCC_OPTIMIZATION_LEVEL": "2", "ONLY_ACTIVE_ARCH": "YES"}
    release = {"SWIFT_OPTIMIZATION_LEVEL": '"-O"', "DEBUG_INFORMATION_FORMAT": '"dwarf-with-dsym"',
               "SWIFT_COMPILATION_MODE": "wholemodule", "VALIDATE_PRODUCT": "YES"}

    def cfg_list(tag: str, settings: dict) -> str:
        d = cfg(tag, "Debug", settings, debug)
        r = cfg(tag, "Release", settings, release)
        lid = uid(f"cfglist:{tag}")
        p.add(lid, f"{{\n\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n\t\t\t\t{d} /* Debug */,"
                   f"\n\t\t\t\t{r} /* Release */\n\t\t\t);\n\t\t\tdefaultConfigurationIsVisible = 0;"
                   f"\n\t\t\tdefaultConfigurationName = Release;\n\t\t}}", tag)
        return lid

    app_target = uid("target:app")
    project = uid("project")
    p.add(app_target, f"{{\n\t\t\tisa = PBXNativeTarget;\n\t\t\tbuildConfigurationList = {cfg_list('app', app_cfg)};"
                      f"\n\t\t\tbuildPhases = (\n\t\t\t\t{sources},\n\t\t\t\t{frameworks},\n\t\t\t\t{resources},"
                      f"\n\t\t\t\t{bundle_engine}\n\t\t\t);\n\t\t\tbuildRules = (\n\t\t\t);\n\t\t\tdependencies = (\n\t\t\t);"
                      f"\n\t\t\tname = {APP};\n\t\t\tproductName = {APP};\n\t\t\tproductReference = {app_product};"
                      f"\n\t\t\tproductType = \"com.apple.product-type.application\";\n\t\t}}", APP)

    proj_cfg = cfg_list("project", {"SWIFT_VERSION": "6.0", "IPHONEOS_DEPLOYMENT_TARGET": IOS,
                                    "SDKROOT": "iphoneos", "ALWAYS_SEARCH_USER_PATHS": "NO",
                                    "CLANG_ENABLE_OBJC_ARC": "YES", "ENABLE_STRICT_OBJC_MSGSEND": "YES",
                                    "GCC_NO_COMMON_BLOCKS": "YES",
                                    # The bundle phase reads ../../engine and the Mac app's Resources.
                                    "ENABLE_USER_SCRIPT_SANDBOXING": "NO"})
    p.add(project, f"{{\n\t\t\tisa = PBXProject;\n\t\t\tattributes = {{\n\t\t\t\tBuildIndependentTargetsInParallel = 1;"
                   f"\n\t\t\t\tLastSwiftUpdateCheck = 2600;\n\t\t\t\tLastUpgradeCheck = 2600;\n\t\t\t}};"
                   f"\n\t\t\tbuildConfigurationList = {proj_cfg};\n\t\t\tcompatibilityVersion = \"Xcode 14.0\";"
                   f"\n\t\t\tdevelopmentRegion = en;\n\t\t\thasScannedForEncodings = 0;"
                   f"\n\t\t\tknownRegions = (\n\t\t\t\ten,\n\t\t\t\tBase,\n\t\t\t\t\"zh-Hans\"\n\t\t\t);"
                   f"\n\t\t\tmainGroup = {main_group};\n\t\t\tproductRefGroup = {products};"
                   f"\n\t\t\tprojectDirPath = \"\";\n\t\t\tprojectRoot = \"\";"
                   f"\n\t\t\ttargets = (\n\t\t\t\t{app_target}\n\t\t\t);\n\t\t}}", "Project object")

    body = "\n".join(p.objects)
    return (f"// !$*UTF8*$!\n{{\n\tarchiveVersion = 1;\n\tclasses = {{\n\t}};\n\tobjectVersion = 56;"
            f"\n\tobjects = {{\n{body}\n\t}};\n\trootObject = {project} /* Project object */;\n}}\n")


def scheme() -> str:
    target = uid("target:app")
    ref = (f'            <BuildableReference\n               BuildableIdentifier = "primary"\n'
           f'               BlueprintIdentifier = "{target}"\n               BuildableName = "{PRODUCT_NAME}.app"\n'
           f'               BlueprintName = "{APP}"\n               ReferencedContainer = "container:{APP}.xcodeproj">\n'
           f'            </BuildableReference>\n')
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "2600"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
{ref}         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{ref}      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{ref}      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""


def main() -> None:
    proj = ROOT / f"{APP}.xcodeproj"
    outputs = {proj / "project.pbxproj": build(),
               proj / "xcshareddata" / "xcschemes" / f"{APP}.xcscheme": scheme()}
    if "--check" in sys.argv:
        stale = [str(path.relative_to(ROOT)) for path, text in outputs.items()
                 if not path.exists() or path.read_text(encoding="utf-8") != text]
        if stale:
            print("stale, run Tools/gen-project.py:", ", ".join(stale))
            sys.exit(1)
        print("SpektraLab.xcodeproj is current")
        return
    for path, text in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
