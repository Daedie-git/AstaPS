# Local fixes pending upstream submission

Original inventory checked on 2026-10-13 against `MeChen618/AstaPS` main `4ae9884d`, using all-author open/closed/merged PR history and actual source comparisons. Seelie submission and fork synchronization updated on 2026-10-14. Commit ancestry alone is not sufficient because several fixes were integrated or squash-merged.

## Submitted now

[PR 141: 7.1 achievement claims and saved-team response fields](https://github.com/MeChen618/AstaPS/pull/141) is open from `Daedie-git:fix/7.1-achievement-team-responses`, commit `9fab7c45`.

It contains individual achievement request/response command and field corrections, complete request/batch validation, explicit routing, saved-team acknowledgement fields, tests and generation documentation. It does not include category claims, account test resets, Seelie changes, local customizations or protocol extraction artifacts. The current upstream already has team response command 29488; the PR corrects its stale fields rather than resubmitting that opcode change. Local validation: 852 tests pass, 4 skip, generated-code lint passes; all 27 new cases fail on the unmodified upstream baseline.

No server deployment, restart or account mutation accompanies PR 141's submission.

[PR 149: Seelie configured-route timing, stops and initial handoff](https://github.com/MeChen618/AstaPS/pull/149) is open from `Daedie-git:fix/seelie-config-route`, commit `04179329`. It includes route-change synchronization, the empty near-platform guard, route-resource aliases, ordered/cancellable scene-clock deadlines, authored stop markers and a continuous distance-based initial approach. The user confirms the final movement works correctly in live play. All 49 focused tests pass; the upstream-based non-integration suite runs 878 tests with zero failures/errors and four skipped. Temporary logging, capture tools, account resets and fork-only instructions are excluded.

Four further focused PRs are open, all directly based on upstream `4ae9884d`:

- [PR 150: canonical Adventure Rank request schema](https://github.com/MeChen618/AstaPS/pull/150), `fix/7.1-rank-request-schema`, commit `472a6329`. Only the generated field-15 descriptor and its captured-wire tests/documentation change; the handler fix is already merged in PR 107. Eight new cases fail before the correction and pass after it; full suite 837 tests, zero failures/errors, four skipped; generated-Java lint passes.
- [PR 151: complete handbook claim payload validation](https://github.com/MeChen618/AstaPS/pull/151), `fix/handbook-whole-payload-validation`, commit `aa88d76f`. Fifty actual-handler tests pass (32 fail before the fix); full suite 879 tests, zero failures/errors, four skipped. No opcode/schema, authorization or delivery changes; chapter request identity remains unmapped.
- [PR 152: point-array notification fallback](https://github.com/MeChen618/AstaPS/pull/152), `fix/platform-point-array-notification`, commit `2e0701bf`. Only the notification block and tests change. Three tests cover unavailable/positive opcode branches, ordered refresh, same-ID silence and state preservation; full suite 832 tests, zero failures/errors, four skipped. Unfinished movement/serializer behavior is excluded.
- [PR 153: rotation-specific Abyss rewards](https://github.com/MeChen618/AstaPS/pull/153), `fix/abyss-rotation-rewards`, commit `b17b215c`. Shared group-aware resolution covers star, chamber and skipped-floor rewards with legacy fallback. Fourteen focused tests pass; full suite 843 tests, zero failures/errors, four skipped. Existing floor-loading and payment guards remain intact; fork-only reward policies are excluded.

These submissions do not redeploy the server or mutate player state. The four fix branches are pushed; unrelated customization-branch work remains uncommitted as before.

The deployed server's temporary logging is removed without changing the accepted gameplay or resetting progress. The customization fork includes this fix and latest `origin/main` (`4ae9884d`); its merged non-integration suite runs 945 tests with zero failures/errors and four skipped.

## Remaining small local work

The four complete smaller upstream candidates are submitted in PRs 150–153. Achievement category/goal response fields remain local (`TakeAchievementGoalRewardRsp.proto`, response opcode 22054), but the request identity is still unmapped, so this is not a complete claiming fix or a submitted PR.

`/finishquest`, including its chain-preservation correction, is explicitly fork-only and is not a pending upstream bugfix. The deployed integration also retains handbook resource/unlock/delivery protection beyond the submitted parser fix.

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

Intentional banner/odds/character tuning, `/catalog`, fork-only `/finishquest`, Paimon's shop policy, Primogem conversion, artifact odds/substats and announcements are customizations, not automatically pending bugfix PRs. Protocol mapping/export/research tools are separate tooling work. Heap dumps, client/capture artifacts and account test resets must not enter bugfix submissions.

Detailed per-branch comparisons and installed/source qualifications: `captures/upstream-submission/LOCAL-FIX-INVENTORY.md`.
