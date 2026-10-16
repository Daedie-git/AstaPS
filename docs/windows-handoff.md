# Windows handoff — 2026-10-16

The fork branch **`windows-ready`** saves the local source/customizations and the
Windows client setup tools. No upstream PR is opened for these local-only files.

```powershell
git clone --branch windows-ready https://github.com/Daedie-git/AstaPS.git
```

## Source included

- Numbered `/catalog`, the curated character/weapon rotation and its generator,
  local `/finishquest` behavior, clone-ID correction, claim safeguards and tests.
- Upstream main at `22248c3e`, the five published fix PRs #165–#169, and their
  subsequent framing, stored-board, replacement-order and monster-tide fixes.
- Current reviewed protocol-checker implementation, rather than the older local
  checker version. Raw native-schema extraction tools remain outside this handoff.
- Both manually resolved private deployment source layers, archived as exact
  patches and a 104-file hash index under
  `customization/deployment-source-2026-10-14/`.
- Windows installer/launcher, integrity checks, undo, kit assembler and tests under
  `tools/windows/`.

The archived private layers are **not yet integrated into the newer normal source
tree**. A plain source build therefore does not reproduce all running private
server safeguards. Do not replace the installed server with it. The source archive
explains historical reconstruction and later-integration requirements.

## Client kit

The local kit is assembled in `~/win-d/AstaPS-7.1-Windows` (the Windows D: drive).
Start `Client.cmd` there. Full instructions are in its `README.md` and
`tools/windows/README.md` in this repository.

The kit includes the pinned proxy DLL, its complete GPL source/license, scripts,
hashes and the stock bootstrap recovery copy for an installation copied from
Linux. It excludes the Linux-only driver skip, Wine/Proton, full game data,
credentials, accounts, server config, MongoDB data and diagnostic captures.
Proprietary recovery binaries stay in that private local kit, not in Git.

The game must match the pinned Global 7.1.0 executable and original plugin. Native
Windows gameplay is not yet tested. Installer tests use synthetic files and do not
claim successful native login or rendering.

The proxy expects dispatch on **127.0.0.1:8088**, with a reachable advertised UDP
game endpoint. Running Windows on this same computer stops the Linux-hosted
server. A Windows server migration or a separate reachable server is therefore
still required; the kit does not expose any ports or move/reset account state.
The current Linux server and pending commission-notification experiment remain
unchanged. A server migration must separately preserve the live database/config,
private source composition and earned progress, not create an empty replacement.

## Validation

- Integrated Java build: 1,191 tests, 1,187 passed, four existing skips, no failures;
  integration-tagged tests require external services/resources and are excluded.
- Protocol checker: 84 Python tests passed; catalog: 13 passed.
- Windows installer: 40 synthetic PowerShell assertions passed on PowerShell 7.6.7
  Linux, including failed-copy rollback, stock/Wine bootstrap guards, unknown
  builds, running-client refusal, junction/symlink rejection and safe undo.
- Private-source reconstruction: all 104 final file hashes match the archived
  resolved source. This verifies preservation, not integration with newer source.
- Packaging tests verify source/binary pinning, complete hashes, refusal to overwrite
  an existing handoff, and failure cleanup. Windows PowerShell 5.1/native runtime
  still needs an actual Windows test.
