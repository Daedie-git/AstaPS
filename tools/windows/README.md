# AstaPS client on native Windows

This private kit supports the **Global 7.1.0 build** pinned in `client-manifest.json`.
It contains the same redirect/encryption proxy used by the working Linux client,
its complete GPL-3.0 source and license, guarded PowerShell scripts, and a stock
bootstrap recovery copy. Native Windows login/gameplay still needs a real test;
passing synthetic installer tests is not a claim that the native game works.

## Quick start

1. Use a **separate private-server copy** of your matching game installation.
   Close Genshin and HoYoPlay. Do not use the official launcher to update this copy.
   This kit is not a game download and does not include the 140 GiB game data.
2. Start the intended AstaPS server. The proxy is compiled for **127.0.0.1:8088**.
   A Linux server on another computer is not automatically reachable at that address.
   A TCP-only SSH tunnel is insufficient for gameplay: the dispatch also advertises
   a game endpoint using UDP (normally port 22101). No tunnel, listening-address,
   firewall, hosts, certificate or server configuration change is made by this kit.
3. Double-click `Client.cmd`, enter the folder containing `GenshinImpact.exe`, and
   choose **1 Install**. The path is remembered locally. Choose **2 Launch** later.
   `Client.cmd` bypasses execution policy only for this invocation; it does not
   change the machine's policy or request elevation.
4. If you copied the Linux installation, choose **5** instead. It restores only the
   *exact recognized Linux-modified* `mhypbase.dll` to the bundled stock DLL, after
   a verified backup. The Linux kernel-driver skip must **not** carry over to native
   Windows. This is not a request to disable Windows security or anti-cheat services.
5. Log in with your **private-server account only**, never HoYoverse credentials.
   Keep the patch console open. Check `.astaps-windows/logs` inside the game folder
   for initialization and local redirects before entering any credentials.

The installer checks the executable, original plugin, proxy and bootstrap SHA-256
hashes. Another client version, an unknown existing patch, a linked/junction game
path, or a running client is refused before any game files are written.
The original `GenshinImpact_Data/Plugins/Astrolabe.dll` is never changed. The root
proxy uses `Astrolabe_orig.dll` to forward the game's original plugin exports.
Do not rename the proxy to another DLL or put it in System32.

If Windows reports `VCRUNTIME140.dll` missing, install the official Microsoft
Visual C++ 2015–2022 x64 runtime from https://aka.ms/vc14/vc_redist.x64.exe.
Do not download individual runtime DLLs from third-party sites. Do not disable
Defender or add broad exclusions to make this kit run. Unknown native failures
need investigation, not security-policy changes.

## Check and undo

Use menu **3 Check** or **4 Undo** with the client closed. The installer preserves
existing root files and, when requested, the Linux bootstrap in verified backups
under `.astaps-windows/backup-*`, with a recovery journal `state.json`. A second
installation keeps the original backup. Undo refuses to overwrite files changed
since installation. Keep this directory until rollback is complete.

Undo restores the **exact pre-install state**, including the Linux driver skip if
that was present before installation. Such a restored Linux copy is not ready for
native Windows launch until option 5 is reapplied. If installation/undo is
interrupted or disk writes fail, retain the journal and backup, close the game and
recover those fixed root files manually; do not delete the game's plugin or data.
No account, database, reward, quest, game progress or server archive is modified.

Direct commands, from PowerShell in this kit directory:

```powershell
.\Patch-Client.ps1 -GameDirectory 'D:\Games\PrivateGenshin'
.\Patch-Client.ps1 -GameDirectory 'D:\Games\PrivateGenshin' -Action Check
.\Start-Client.ps1 -GameDirectory 'D:\Games\PrivateGenshin'
.\Patch-Client.ps1 -GameDirectory 'D:\Games\PrivateGenshin' -Action Undo
.\Test-ClientPatch.ps1
```

## Provenance and rebuilding

Proxy source: https://github.com/capyb2222/animegamepatch.git at
`562248bf212518872e3027d579fa511716f39294`. The kit includes every tracked source
file at that revision in `patch-source/`, including `LICENSE`. It also retains
the exact locally built `Cargo.lock` (ignored by the patch's own repository),
whose SHA-256 is separately pinned in `client-manifest.json`.
The binary is unchanged from the working local installation; its SHA-256 is
`b067813ef3fa643f6fdc861a447780b7bcfc2a194885298c412d94c79a3bd3f3`.
The original build used official Rust 1.98.1, target `x86_64-pc-windows-msvc`,
`RUSTC_BOOTSTRAP=1` for the source's unstable UTF-16 feature, Cargo's locked
sources, LLVM lld-link and Microsoft CRT/SDK libraries. Those SDK binaries and
private game DLLs are not committed to the server repository.

On Windows, install Rust's MSVC toolchain and Visual Studio C++ Build Tools/SDK,
then build the supplied source from a Developer PowerShell:

```powershell
cd patch-source
$env:RUSTC_BOOTSTRAP = '1'
cargo build --locked --release --target x86_64-pc-windows-msvc
Remove-Item Env:RUSTC_BOOTSTRAP
```

The output is `target/x86_64-pc-windows-msvc/release/ext.dll`. Different compiler
or linker versions need not reproduce the shipped binary. Do not silently replace
the pinned binary/hash: revalidate a rebuilt proxy before updating the manifest.
The patch changes process behavior and redirects network requests; it is not a
security-audited or official HoYoverse tool.

The repository tracks scripts, hash facts and these instructions, **not** the
proprietary stock bootstrap or game executable. `assemble_bundle.py` assembles
this local kit from an existing verified setup; it never overwrites an existing
output directory. `SHA256SUMS.txt` and `package-manifest.json` enumerate the bundle.
The saved server source and these tools are pushed to the fork's `windows-ready`
branch:

```powershell
git clone --branch windows-ready https://github.com/Daedie-git/AstaPS.git
```

Read `docs/windows-handoff.md` in that checkout before rebuilding the server.
The complete server's deployed private source composition is separate from this
client kit; a plain checkout build must not be assumed to reproduce it.
