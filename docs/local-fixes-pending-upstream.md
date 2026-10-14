# Local fixes pending upstream submission

Original inventory checked on 2026-10-13 against `MeChen618/AstaPS` main `4ae9884d`, using all-author open/closed/merged PR history and actual source comparisons. Seelie submission and fork synchronization updated on 2026-10-14. Commit ancestry alone is not sufficient because several fixes were integrated or squash-merged.

## Submitted now

[PR 141: 7.1 achievement claims and saved-team response fields](https://github.com/MeChen618/AstaPS/pull/141) is open from `Daedie-git:fix/7.1-achievement-team-responses`, commit `9fab7c45`.

It contains individual achievement request/response command and field corrections, complete request/batch validation, explicit routing, saved-team acknowledgement fields, tests and generation documentation. It does not include category claims, account test resets, Seelie changes, local customizations or protocol extraction artifacts. The current upstream already has team response command 29488; the PR corrects its stale fields rather than resubmitting that opcode change. Local validation: 852 tests pass, 4 skip, generated-code lint passes; all 27 new cases fail on the unmodified upstream baseline.

No server deployment, restart or account mutation accompanies PR 141's submission.

[PR 149: Seelie configured-route timing, stops and initial handoff](https://github.com/MeChen618/AstaPS/pull/149) is open from `Daedie-git:fix/seelie-config-route`, commit `04179329`. It includes route-change synchronization, the empty near-platform guard, route-resource aliases, ordered/cancellable scene-clock deadlines, authored stop markers and a continuous distance-based initial approach. The user confirms the final movement works correctly in live play. All 49 focused tests pass; the upstream-based non-integration suite runs 878 tests with zero failures/errors and four skipped. Temporary logging, capture tools, account resets and fork-only instructions are excluded.

The deployed server's temporary logging is removed without changing the accepted gameplay or resetting progress. The customization fork includes this fix and latest `origin/main` (`4ae9884d`); its merged non-integration suite runs 945 tests with zero failures/errors and four skipped.

## Small deployed changes with no matching PR

| Fix | Local source | Important limit |
|---|---|---|
| Point-array platform notification fallback | `ScriptLib.SetPlatformPointArray`, `tools/check-platform-point-array.sh` | Fixes dropped opcode-zero notification, not the unfinished point-array movement/serializer. |
| Whole-payload handbook claim validation | `HandlerTakeInvestigationRewardReq`, `HandlerTakeInvestigationTargetRewardReq` | Rejects malformed suffixes/wire types before rewards; chapter request remains unmapped. |
| Canonical Adventure Rank request schema | `protocol/7.1/compat/TakePlayerLevelRewardReq.proto` | Only the field-15 generated descriptor remains pending; opcode and guarded handwritten handler already merged in PR 107. |
| Achievement category/goal response | `TakeAchievementGoalRewardRsp.proto`, response opcode 22054 | Fields 7/8/9 are verified; request is still unmapped, so category claims are not fully enabled. |
| Rotation-specific Abyss reward selection | local commit `fa8ce7b4`, `TowerRewards` and tower schedule/reward callers | Distinct from merged floor-loading PR 99; extract without reverting newer tower changes. |
| `/finishquest` chain-preservation correction | `CurrentQuestCompletion`, `FinishQuestCommand` | Correctness fix to an optional custom command; keep unfinished successors on their normal progression path. |

The report groups these for human use; it is not a count of independent submission-ready patches. The deployed integration also retains handbook resource/unlock/delivery protection beyond the parser fixes above.

## Larger unsubmitted bundles

These four branches have no upstream PR. Some subsets are deployed through the trimmed integration; the latest full branches are not all deployed or ready to stack together.

- **Login handoff:** newest login wins; wait for previous saves/loaders; detach unfinished players without stale logout saves; retire old writers. Deployed trimmed variant lives in `captures/fix-integ`; `fix/audit-login` contains a heavier alternative lease/fencing implementation.
- **Inventory, payment, crafting and TPS lifecycle:** capacity preflight, duplicate/overflow/currency checks, full-bag queue/refund preservation, owned strongbox inputs and accessory lifetime corrections. Branch: `fix/audit-inventory`.
- **Reward delivery and claim boundaries:** remaining rank/BP/tower/expedition/housing/trial/mail/weekly reward safeguards and failure replies, plus handbook missing-data/unlock/delivery checks. Branch: `fix/audit-rewards`; deployed `ClaimBoundary`/`ClaimRewardDelivery` live in trimmed integration. Preserve separately merged reward admission, journal and domain-transaction work.
- **Protocol/operator safety:** frame/allocation and full UnionCmd batch bounds, mutation-routing/registry validation, handbook amount limits, per-host CORS, configuration/migration atomic saves and secret-safe command logging. Branch: `fix/audit-protocol-ops`.

## Follow-ups beyond already-merged audit PRs

Do not resubmit original audit bundles wholesale. Their earlier PRs are merged, but later local changes remain:

- `fix/audit-combat`: queued ability owner/entity epoch and lifetime/cleanup follow-ups beyond PR 124. The complete queued-work layer is not deployed in the trimmed build.
- `fix/audit-world-quests`: additional authorization, deferred script final saves and lifecycle hooks beyond PR 123 and narrower quest/scene-lock merges. Complete latest branch deployment is not established.
- `fix/audit-persistence`: acknowledged completions, failure/barrier/shutdown results and account-write fencing beyond PR 122; the full latest layer was trimmed from deployment.
- `fix/audit-multiplayer`: host-world admission, boss lifecycle and friend-transition follow-ups beyond PR 125; current per-hunk deployment is not fully established.

These need fresh current-main extraction and tests. Broad old branch diffs also contain reversions of newer upstream behavior; they are not safe automatic cherry-picks.

## Already merged or excluded

Already merged: PRs 98 (Experience target opcode), 99 (Abyss schedule key), 107 (rank handler), 118 (skill particle positions), 122–125 (initial persistence/world/combat/multiplayer work), 116/117/120 (reward admission/receipts/domain transactions), and 127–130 (related quest/Morphia/scene-save/open-state fixes).

Intentional banner/odds/character tuning, `/catalog`, Paimon's shop policy, Primogem conversion, artifact odds/substats and announcements are customizations, not automatically pending bugfix PRs. Protocol mapping/export/research tools are separate tooling work. Heap dumps, client/capture artifacts and account test resets must not enter bugfix submissions.

Detailed per-branch comparisons and installed/source qualifications: `captures/upstream-submission/LOCAL-FIX-INVENTORY.md`.
