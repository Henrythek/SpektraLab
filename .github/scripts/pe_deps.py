#!/usr/bin/env python3
"""Check that every .exe/.dll in a folder needs nothing a stock Windows lacks.

    python pe_deps.py <folder>

Walks the folder, reads each PE's import table (and delay-load imports) and
classifies every DLL it names:

  bundled    the folder itself carries it
  system     an API set (api-ms-win-*, ext-ms-*) or present in System32
  redist     a runtime that is *not* part of Windows (MSVC, MinGW, Vulkan
             loader, WebView2 loader, ...) -- these fail the check unless bundled

A DLL that is neither bundled nor in System32 fails too. The runner's System32
has redistributables installed, so the redist list is checked first: being on
the runner is not proof that a user's PC has it.

vulkan-1.dll is the GPU driver's loader and is always present where a Vulkan
driver is; the host loads it at run time (LoadLibrary), so it must not appear
as an import at all.
"""
import os
import re
import sys

import pefile

REDIST = re.compile(
    r"^(vcruntime\d+(_\d+)?|msvcp\d+(_\w+)?|concrt\d+|vccorlib\d+|ucrtbased|"
    r"libstdc\+\+-\d+|libgcc_s_\w+|libwinpthread-\d+|vulkan-1|webview2loader)\.dll$",
    re.I,
)
SYSTEM32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")


def imports(path):
    pe = pefile.PE(path, fast_load=True)
    pe.parse_data_directories(directories=[
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT"],
    ])
    names = set()
    for attr in ("DIRECTORY_ENTRY_IMPORT", "DIRECTORY_ENTRY_DELAY_IMPORT"):
        for entry in getattr(pe, attr, []):
            names.add(entry.dll.decode("ascii", "replace"))
    return sorted(names, key=str.lower)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = sys.argv[1]
    pes = []
    for d, _, files in os.walk(root):
        pes += [os.path.join(d, f) for f in files if f.lower().endswith((".exe", ".dll"))]
    if not pes:
        sys.exit(f"no .exe or .dll under {root}")
    bundled = {os.path.basename(p).lower() for p in pes}
    bad = []
    for p in sorted(pes):
        rel = os.path.relpath(p, root)
        found = imports(p)
        print(f"{rel}: {', '.join(found)}")
        for dll in found:
            low = dll.lower()
            if low in bundled:
                continue
            if REDIST.match(low):
                bad.append(f"{rel} needs {dll}, a redistributable a stock Windows may not have")
            elif low.startswith(("api-ms-win-", "ext-ms-")) or os.path.isfile(os.path.join(SYSTEM32, dll)):
                continue
            else:
                bad.append(f"{rel} needs {dll}, which is neither in the folder nor in System32")
    if bad:
        print("\n".join(["", "FAIL:"] + bad))
        sys.exit(1)
    print(f"\nok: {len(pes)} PE files need only system DLLs or what the folder carries")


if __name__ == "__main__":
    main()
