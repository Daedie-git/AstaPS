# Preserved private server source layers (fork only)

These patches preserve the final, manually resolved Java sources used by the
2026-10-14 private deployment. They are recovery material, **not patches already
applied to this branch** and not an invitation to deploy an old server build.

- Base: `1a1b45f9` (the saved local working source based on `194493df`).
- `private-safety.patch`: 67 source/config files covering login/save handoff,
  inventory/payment/reward delivery, operator/auth/configuration protections.
- `residual-safety.patch`: 37 source files covering the remaining combat,
  lifetime, multiplayer, metadata and eligibility changes.
- `source-index.json`: exact before/after hashes for all 104 source files,
  including unchanged files, plus patch hashes and the full base commit.

To reconstruct that historical source layer, use a **separate clean worktree** at
that base with `core.autocrlf=false`, apply the private patch then the residual
patch, and verify every final source hash against the index. Do not apply these patches wholesale over later
upstream source. Later category-claim, clone, commission lifecycle, completion and
replacement fixes must be retained as well. Private boolean reward-delivery APIs
also need their matching callers/tests; source compilation alone does not prove
live delivery behavior.

The current Windows handoff branch merges newer upstream and the published fixes
into the normal source tree. These archived private layers remain separate until
a reviewed integration reconciles them with that newer tree. Therefore a plain
`gradlew jar` build of this branch is **not a complete reproduction of the current
installed private server** and must not replace it simply to move the client.

No accounts, credentials, MongoDB files, configuration secrets, game assets,
client disassembly or packaged server binaries are included. The running Linux
server is unchanged by this archival commit. Keep this directory on the fork;
it is not part of the upstream pull requests.
