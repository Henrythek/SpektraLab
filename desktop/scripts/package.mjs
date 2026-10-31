// package.mjs — build the installers.
//
//   node scripts/package.mjs linux   → release/linux/*.deb, *.AppImage
//   node scripts/package.mjs win     → release/windows/*-setup.exe (NSIS, cross-built)
//                                      + release/windows/SpektraLab_<ver>_x64-portable.zip
//
// Both stage the engine host the backend built (`build/host-<os>-x64/`):
//   spektralab-host[.exe] → src-tauri/binaries/spektralab-host-<triple>[.exe]
//                            (Tauri `bundle.externalBin`: installed beside the app)
//   engine/, licenses/    → src-tauri/host-staging/ → `<resources>/host/…`
// and pass that as an extra config (`tauri.bundle.json`), so the base config
// builds and `cargo check`s without a staged host.
//
// The Windows build **refuses** to run without build/host-win-x64/spektralab-host.exe:
// an installer without its engine would install an app that cannot develop
// anything.
//
// On a Windows machine (CI) `win` builds natively with MSVC
// (x86_64-pc-windows-msvc, no cross runner, the system PATH untouched).
// On Linux, Windows is cross-built with the x86_64-pc-windows-gnu target and mingw-w64
// (Tauri's documented cargo-xwin/MSVC path is behind SPEKTRALAB_WIN_TOOLCHAIN=msvc;
// its CRT download was refused here), NSIS from the system (`makensis`).
// Env: SPEKTRALAB_HOST_BUILD overrides the repo's build/ directory.
//
// The portable zip is the same app without the installer: unzip anywhere and
// run SpektraLab.exe. Its layout is the installed one, because the app finds
// its engine the same way in both (src-tauri/src/host.rs, resolve_launch):
//   SpektraLab.exe, spektralab-host.exe   side by side
//   host/engine/, host/licenses/          Tauri's resource_dir on Windows is the exe's folder
// It needs the WebView2 runtime, which Windows 10/11 ship; the installer can
// bootstrap it, a zip cannot (README.txt in the zip says so).

import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const desktop = resolve(here, '..');
const tauriDir = join(desktop, 'src-tauri');
const repo = resolve(desktop, '..');
const buildDir = process.env.SPEKTRALAB_HOST_BUILD ?? join(repo, 'build');
const which = process.argv[2];

const TARGETS = {
  linux: {
    hostDir: join(buildDir, 'host-linux-x64'),
    exe: 'spektralab-host',
    triple: 'x86_64-unknown-linux-gnu',
    bundles: 'deb,appimage',
    out: 'linux',
    extra: [],
  },
  // MinGW by default: cargo-xwin's MSVC CRT download (aka.ms) is refused by
  // this build machine's egress policy. SPEKTRALAB_WIN_TOOLCHAIN=msvc uses
  // Tauri's documented cargo-xwin path where that download is allowed.
  win:
    // Native on Windows (CI's windows-latest): MSVC, no cross runner.
    process.platform === 'win32'
      ? {
          hostDir: join(buildDir, 'host-win-x64'),
          exe: 'spektralab-host.exe',
          triple: 'x86_64-pc-windows-msvc',
          bundles: 'nsis',
          out: 'windows',
          extra: ['--target', 'x86_64-pc-windows-msvc'],
        }
      : process.env.SPEKTRALAB_WIN_TOOLCHAIN === 'msvc'
      ? {
          hostDir: join(buildDir, 'host-win-x64'),
          exe: 'spektralab-host.exe',
          triple: 'x86_64-pc-windows-msvc',
          bundles: 'nsis',
          out: 'windows',
          extra: ['--runner', 'cargo-xwin', '--target', 'x86_64-pc-windows-msvc'],
        }
      : {
          hostDir: join(buildDir, 'host-win-x64'),
          exe: 'spektralab-host.exe',
          triple: 'x86_64-pc-windows-gnu',
          bundles: 'nsis',
          out: 'windows',
          extra: ['--target', 'x86_64-pc-windows-gnu'],
        },
};

function die(msg) {
  console.error(`package: ${msg}`);
  process.exit(1);
}

function stage(t) {
  const exe = join(t.hostDir, t.exe);
  if (!existsSync(exe)) die(`no engine host at ${exe}. Build it first (engine/host, see engine/WINDOWS.md / the backend's notes); refusing to package an app without its engine.`);
  if (!existsSync(join(t.hostDir, 'engine'))) die(`no engine resources at ${join(t.hostDir, 'engine')}`);
  const bin = join(tauriDir, 'binaries');
  const staging = join(tauriDir, 'host-staging');
  rmSync(bin, { recursive: true, force: true });
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(bin, { recursive: true });
  const ext = t.exe.endsWith('.exe') ? '.exe' : '';
  cpSync(exe, join(bin, `spektralab-host-${t.triple}${ext}`));
  cpSync(join(t.hostDir, 'engine'), join(staging, 'engine'), { recursive: true });
  if (existsSync(join(t.hostDir, 'licenses'))) cpSync(join(t.hostDir, 'licenses'), join(staging, 'licenses'), { recursive: true });
  const conf = {
    bundle: {
      externalBin: ['binaries/spektralab-host'],
      resources: { 'host-staging/engine': 'host/engine', 'host-staging/licenses': 'host/licenses' },
    },
  };
  const confPath = join(tauriDir, 'tauri.bundle.json');
  writeFileSync(confPath, JSON.stringify(conf, null, 2));
  return confPath;
}

function collect(t) {
  const release = join(desktop, 'release', t.out);
  rmSync(release, { recursive: true, force: true });
  mkdirSync(release, { recursive: true });
  // A cross build lands under target/<triple>/; a native one under target/release/.
  const roots = which === 'linux' ? [join(tauriDir, 'target', 'release', 'bundle')] : [join(tauriDir, 'target', t.triple, 'release', 'bundle')];
  const found = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const kind of readdirSync(root)) {
      for (const f of readdirSync(join(root, kind))) {
        const p = join(root, kind, f);
        if (statSync(p).isFile() && /\.(deb|AppImage|exe|rpm)$/.test(f)) {
          cpSync(p, join(release, f));
          found.push(`${join('release', t.out, f)} (${(statSync(p).size / 1e6).toFixed(1)} MB)`);
        }
      }
    }
  }
  console.log('package: wrote\n  ' + found.join('\n  '));
}

const PORTABLE_README = (version) => `SpektraLab ${version} for Windows (x64), portable
=================================================

Unzip this folder anywhere (a USB stick works too) and run SpektraLab.exe.
Nothing is installed and nothing outside this folder is needed, except:

- Microsoft Edge WebView2 Runtime. Windows 11 and up-to-date Windows 10 have it.
  If SpektraLab opens no window, install the Evergreen runtime from
  https://developer.microsoft.com/microsoft-edge/webview2/
- A Vulkan GPU driver (any current NVIDIA, AMD or Intel driver) to develop images.

Keep the files together: SpektraLab.exe starts spektralab-host.exe beside it,
which reads its film and paper profiles from host\\engine.

Settings and edits are kept per user in %APPDATA%\\app.spektralab.desktop, as
with the installed app. To remove SpektraLab, delete this folder.

Licenses: LICENSE.txt (GPL-3.0-or-later) and host\\licenses.
`;

// The installed layout, in a folder, zipped. Built from what the installer was
// built from: the app exe cargo produced and the staged host.
function portable(t) {
  const version = JSON.parse(readFileSync(join(tauriDir, 'tauri.conf.json'), 'utf8')).version;
  const name = `SpektraLab_${version}_x64-portable`;
  const release = join(desktop, 'release', t.out);
  const dir = join(release, name);
  const appExe = join(tauriDir, 'target', t.triple, 'release', 'spektralab.exe');
  if (!existsSync(appExe)) die(`no app exe at ${appExe}`);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(join(dir, 'host'), { recursive: true });
  cpSync(appExe, join(dir, 'SpektraLab.exe'));
  cpSync(join(t.hostDir, t.exe), join(dir, t.exe));
  cpSync(join(t.hostDir, 'engine'), join(dir, 'host', 'engine'), { recursive: true });
  if (existsSync(join(t.hostDir, 'licenses'))) cpSync(join(t.hostDir, 'licenses'), join(dir, 'host', 'licenses'), { recursive: true });
  cpSync(join(repo, 'license', 'LICENSE'), join(dir, 'LICENSE.txt'));
  writeFileSync(join(dir, 'README.txt'), PORTABLE_README(version).replace(/\n/g, '\r\n'));
  const zip = join(release, `${name}.zip`);
  rmSync(zip, { force: true });
  // Windows 10+ ships bsdtar as tar.exe, which writes zips with -a; elsewhere, zip(1).
  if (process.platform === 'win32') execFileSync('tar.exe', ['-a', '-c', '-f', zip, name], { cwd: release, stdio: 'inherit' });
  else execFileSync('zip', ['-r', '-q', '-X', zip, name], { cwd: release, stdio: 'inherit' });
  console.log(`package: wrote\n  ${join('release', t.out, `${name}.zip`)} (${(statSync(zip).size / 1e6).toFixed(1)} MB)`);
}

const t = TARGETS[which];
if (!t) die('usage: node scripts/package.mjs linux|win');
const conf = stage(t);
const env = { ...process.env };
const onWindows = process.platform === 'win32';
if (which === 'win' && !onWindows) {
  // Cross build: cargo-xwin's clang-cl/lld-link, and llvm-rc for the resource script.
  env.PATH = `${process.env.HOME}/.cargo/bin:/usr/lib/llvm-18/bin:${env.PATH}`;
}
if (which === 'linux') env.NO_STRIP = env.NO_STRIP ?? 'true'; // linuxdeploy's strip cannot read newer ELF notes
// On Windows `npx` is npx.cmd, which needs a shell; the shell then needs a
// quoted path if the checkout's path has a space in it.
const arg = (a) => (onWindows && /\s/.test(a) ? `"${a}"` : a);
execFileSync('npx', ['tauri', 'build', '--config', conf, '--bundles', t.bundles, ...t.extra].map(arg), { cwd: desktop, stdio: 'inherit', env, shell: onWindows });
collect(t);
if (which === 'win') portable(t);
