# Reward claims

## Adventure Rank

Katheryne's 7.1 claim request is opcode `7321`, with `level` in protobuf field 15. The user confirmed rank 2/3/4 clicks corresponding to captured payloads `7802`, `7803`, `7804` on 2026-10-10. The prior constant was zero, so no handler registered; the legacy generated request descriptor also incorrectly used field 11. The canonical `compat/TakePlayerLevelRewardReq.proto` now uses native/captured field 15; this schema correction is deployed as of 2026-10-13 12:26 Europe/Brussels. Changing only the opcode still failed the captured-click tests.

The handler now decodes field 15, validates the entire message, rejects malformed or missing ranks, and skips well-formed unknown fields. Earned rewards pay once; retries receive a success reply without another payment. Future/unknown ranks and missing reward data receive rejection without consuming the claim. An explicit registration fallback handles Reflections misses after deployment.

The installed patch changes only `PacketOpcodes`, `GameServerPacketHandler` and `HandlerTakePlayerLevelRewardReq`; it removes the temporary capture. Configuration, banners/state, tuning, resources and other reward handlers stay unchanged. The source and staged/installed replay tests pass; live client reward display and response decoding still require a post-deployment claim.

## Handbook

Experience objective claims remain mapped to opcode `2703`; upstream PR #98 is merged. Handbook chapter request `TakeInvestigationRewardReq` remains unmapped. Its unlock/missing-data protections are source-only and are not deployed with the Adventure Rank fix. Real chapter clicks or independent native semantic evidence are still needed before mapping that request. The offline audit confirms matching response layouts but does not recover the chapter request identity.

Both handbook request parsers now validate the entire payload before returning an ID: malformed trailing fields, wrong selected-field wire types and unexpected end-groups reject without payment or consuming a claim. Well-formed unknown fields and protobuf last-scalar-value behavior remain supported. These parser fixes, deployed on 2026-10-13, preserve existing eligibility, retry, missing-data and reward-delivery protections; they do not enable the unmapped chapter request.

## Achievement Categories

The category/goal reward response is independently identified as command 22054, with retcode field 7, packed IDs field 8 and ItemParam messages field 9. Canonical `compat/TakeAchievementGoalRewardRsp.proto` and the response constant now match those native proofs. Real packet-builder golden tests cover success/error bytes without grants or database writes. This response correction is deployed; the goal request remains command zero and unverified, so it alone does not restore category claims.

## Validate

```bash
bash gradlew test --tests '*PlayerLevelRewardClaimTest' --tests '*InvestigationTargetRewardRoutingTest' --tests '*InvestigationChapterRewardClaimTest' --offline --no-daemon
```

Tests replay captured requests through the real router with isolated resources, recording inventory and fake sessions. They cover payment, retry, eligibility, malformed requests and claim preservation without changing player records. A classpath-first installed-archive run excludes the pending chapter tests.

The bounded active-session capture was rearmed with approval for 2026-10-10 16:46–17:16 Europe/Amsterdam and is removed by the rank deployment. It recorded only small unknown requests, without login/auth tracing. Evidence and red/green tests are in ignored `captures/reward-claims-rearmed/`. Installation backup/hashes are in `customization/rank-claims-manifest.json`; persistent deployment changes and rollback are logged in `/home/aim/SYSTEM_CHANGES.md`.
