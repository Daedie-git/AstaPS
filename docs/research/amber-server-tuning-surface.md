# Amber: server-side tuning surface

Pre-tuning inspection from 2026-10-09; current behavior and validation are in [Amber support](../amber-support.md). This inspection changed no gameplay resources, installation configuration or deployed code. The nine inspected deployed classes matched this checkout at inspection time. Evidence: `captures/amber-server-controls.json`; collector: `captures/inspect_amber_server_controls.py`.

## Architecture and confidence

AstaPS receives client ability invocations and client-computed hit damage. It settles HP/energy, forwards combat and ability messages, maintains character stats, and runs selected ability actions. Its resource pack is a server-side copy of game configuration, not an automatic update to the client's assets or ability scripts.

Consequently, three routes differ:

1. **Server-owned data:** change values actually consumed by server logic, such as particle count or base stats.
2. **Existing client ability parameters:** change a known numeric parameter and synchronize it to the existing client ability. Resources contain the values, but Java may need to supply/reassert the override.
3. **New server behavior:** add an Amber-scoped hook, runtime state and synchronization. This can add buffs, healing, energy refunds and damage outcomes. It does not automatically create client animations, collision, aura application or native modifier effects.

Statements marked practical describe an existing implementation mechanism, not an in-game test of a new Amber feature. Conditional items need a packet capture or trial implementation. No new Amber behavior had been tested at inspection time.

Sources: `src/main/java/emu/grasscutter/server/packet/recv/HandlerAbilityInvocationsNotify.java:15`, `src/main/java/emu/grasscutter/game/ability/AbilityManager.java:359`, `src/main/java/emu/grasscutter/game/world/Scene.java:474`, `src/main/java/emu/grasscutter/data/ResourceLoader.java:420`.

## Pre-Tuning Baselines

| Feature | Installed data |
|---|---|
| Amber avatar / skill depot | `10000021` / `2101` |
| Normal attack | Skill `10041`, proud-skill group `2131` |
| Baron Bunny | Skill `10032`, group `2132`, base cooldown **15 seconds**, one base charge |
| C4 | Talent `214`, openConfig `Ambor_Talent_211`: one extra bunny charge and `cdRatio=-0.2`, giving a nominal 12-second cooldown |
| Bunny lifetime | `UNIQUE_Ambor_Dummy_Timer.duration=8` |
| Bunny taunt | `TriggerTaunt` every 0.5 seconds, selector `CircleR5`, taunt level 3; some enemies have `MuteTaunt` |
| Bunny explosion | Existing attack sphere radius 3, Fire, element durability 50 |
| Bunny energy | Bundled Amber particle distribution: four particles, 100% chance; client ability action also describes energy generation |
| Fiery Rain | Skill `10017`, group `2139`, cooldown 12 seconds, energy cost 40 |
| Rain field | Main loop duration 2 seconds, interval 0.4 seconds, with additional separate hit loops |
| C6 | Talent `216`, parameters `[0.15, 0.15, 10]`: movement-speed bonus, ATK bonus, duration |
| C6 target selection | Existing ability selects player avatars with a distance predicate of 40 |
| First ascension passive | Proud skill `212101`: burst Crit Rate +10 percentage points and AoE parameter +30% |
| Fourth ascension passive | Proud skill `212201`: weakpoint-triggered self ATK bonus 15%, duration 10 seconds |
| Exploration passive | Proud skill `212301`: grants the existing gliding-stamina ability |

Correction to the earlier suggestion: Baron Bunny is already 15 seconds, not 20. The installed C6 values remain 15% ATK, 15% movement speed, 10 seconds.

Primary resources:
- `/home/aim/.local/share/astaps/resources/ExcelBinOutput/AvatarSkillExcelConfigData.json`, rows keyed by skill IDs above.
- `/home/aim/.local/share/astaps/resources/ExcelBinOutput/AvatarTalentExcelConfigData.json`, talents `211`–`216`.
- `/home/aim/.local/share/astaps/resources/ExcelBinOutput/ProudSkillExcelConfigData.json`, groups `2121`, `2122`, `2123`, `2131`, `2132`, `2139`.
- `/home/aim/.local/share/astaps/resources/BinOutput/Talent/AvatarTalents/ConfigTalent_Ambor.json`.
- `/home/aim/.local/share/astaps/resources/BinOutput/Ability/Temp/AvatarAbilities/ConfigAbility_Avatar_Ambor.json:3890`, `:4295`, `:4782`, `:5143`.
- Deployed JAR entry `defaults/data/SkillParticleGeneration.json`, Amber row `10000021`.

## Practical server-owned changes

| Control | Route | What it permits / limitation |
|---|---|---|
| Skill particle count/probability | External `data/SkillParticleGeneration.json` | Four to another count, guaranteed or weighted generation. The table is per character, not per named attack. Preserve all other rows. It changes count when an energy-generation event arrives, not its timing or hit requirement. |
| Particle element or spawn location | Java energy-generation hook | Amber-specific Pyro/neutral particles or different spawn timing/position. Keep normal pickup, Energy Recharge and off-field sharing rules unless intentionally changing them. |
| Flat energy refund | Java confirmed-cast/hit/bunny-event hook | Refund Amber, active character, full party, Pyro teammates or nearby co-op players. Choose amount, trigger, frequency cap and whether ER scales it. |
| Burst energy deduction | Skill data / Java | Server deduction reads `costElemVal`. Changing it does not necessarily change the client's ready threshold; a post-cast refund is safer than promising a lower native cast requirement. |
| Permanent character stats | Avatar/promote/proud-skill data | Base HP/ATK/DEF, Crit Rate/DMG, EM and ascension bonuses where the corresponding fields are consumed. Stats are recalculated and sent to the client. Shared growth curves must not be edited globally for an Amber-only change. |
| Timed party stat bonuses | Java runtime helper | ATK%, flat ATK, EM, Crit Rate/DMG, Energy Recharge, elemental/physical damage bonus, HP/DEF. Recalculate derived stats, notify clients, preserve across stat recalculation, remove on expiry/team changes/logout. |
| Final damage scaling | Java combat hook | Amber-owned damage, selected teammate damage while a support buff is active, incoming damage reduction or conditional bonuses. Personal final-hit scaling already exists for Keqing/Diluc. Identifying particular Amber hit types still needs live packet validation. |
| Healing | Java entity healing / ability-action mechanism | Immediate or periodic healing on cast/detonation, radius-limited healing, scaling from Amber HP/ATK/EM. No new native healing animation or icon is guaranteed. |
| Custom support rules | Java | Duration, refresh vs stacking, cooldown per trigger, eligibility, range, active/off-field behavior, stat scaling, constellation gate and co-op scope. |
| Progression/unlock conditions | Data and Java | Make a custom support benefit available before C6, scale it with constellations/ascension/talent level, or change passive unlock levels. This is distinct from falsely assigning all native constellations. |

Evidence:
- Particle configuration and ownership: `src/main/java/emu/grasscutter/game/managers/energy/EnergyManager.java:64`, `:90`, `:133`, `:457`; `src/main/java/emu/grasscutter/game/ability/actions/ActionGenerateElemBall.java:15`.
- Confirmed burst deduction: `src/main/java/emu/grasscutter/game/managers/energy/EnergyManager.java:223`, `:281`.
- Stat data, passive unlocks, synchronization and recalc: `src/main/java/emu/grasscutter/game/avatar/Avatar.java:553`.
- Existing timed party ATK helper: `src/main/java/emu/grasscutter/game/ability/SymphonistWeaponHelper.java:85`, `:130`.
- Existing hit-scaling seam: `src/main/java/emu/grasscutter/game/entity/CharacterDamageTuning.java:20`.
- Healing/energy actions: `src/main/java/emu/grasscutter/game/ability/actions/ActionHealHP.java`, `ActionReviveElemEnergy.java`; use validated/clamped entity methods for a new hook rather than assuming every existing generic action is safe.

## Existing kit parameters: promising, but synchronization matters

| Parameter | Where it exists | Expected route |
|---|---|---|
| C6 ATK amount, movement-speed amount, duration | Talent `216` / `Avatar_Ambor_ArrowRain.abilitySpecials` | Data plus a client override bridge; preserve the native C6 modifier rather than blindly adding another copy. |
| Weakpoint self-buff amount/duration | Proud skill `212201`, aiming projectile specials | Data plus existing parameter synchronization; extending this to teammates requires new server support logic. |
| Burst passive Crit Rate/AoE bonus | Proud skill `212101` | Existing native ability parameters; validate delta semantics and which client variables receive updates. |
| Normal/charged/plunge/bunny/burst damage ratios | Proud-skill rows and talent bindings | Data plus variable overrides, or final-hit scaling. Final-hit scaling also scales support additions/reaction damage already inside the hit. |
| Bunny HP scaling | Proud-skill group `2132` / `Ambor_ProudSkill32_P3_Hp_Percentage` | Existing client gadget parameter override is a plausible path; in-game durability testing is required. The stored value is an additive max-HP ratio delta, not simply the displayed percentage. |
| Bunny charge count | Skill data and `ModifySkillPoint` | Existing charge-map notifications provide a route. Check C4, login initialization and simultaneous-bunny limits. |
| Bunny/Fiery Rain cooldown | Skill data, C4 config, cooldown-reduction fight property | A synchronized client-compatible cooldown change is needed. A per-avatar CDR stat is broader than changing only Bunny. Editing `cdTime` or C4's JSON `cdRatio` alone is not proven to change its native timer. |
| Rain attack radius/random spread | `AttackRange`, `MaxRandomRange`, passive range variable | Existing exposed specials are promising override candidates. This differs from modifying hardcoded tick duration/count. |
| C1 second-arrow ratio / C2 manual explosion bonus | Talents `211` and `212`, existing projectile/explosion specials | Data plus client synchronization, or identifiable server hit scaling. Changing the ratio does not itself change projectile behavior or the detonation hitbox. |
| Gliding-stamina reduction | Existing native exploration ability / server stamina consumption | An Amber-scoped server rule is possible; native feedback and party passive stacking need validation. |

The current code populates constellation specials on the server, but scene-entry serialization sends proud-skill numeric variables and constellation unlock flags—not a general numeric constellation-param override map. Gadget override helpers map proud skills; they are not a complete C6 synchronization implementation. Therefore changing talent `216`'s JSON values alone is not an established working C6 buff patch.

Sources: `src/main/java/emu/grasscutter/game/ability/Ability.java:110`, `:130`; `src/main/java/emu/grasscutter/server/packet/send/PacketPlayerEnterSceneInfoNotify.java:146`; `src/main/java/emu/grasscutter/game/ability/AbilityManager.java:752`, `:1436`, `:1583`; `src/main/java/emu/grasscutter/data/binout/OpenConfigEntry.java:27`; `src/main/java/emu/grasscutter/data/ResourceLoader.java:1181`.

Extra charges: `src/main/java/emu/grasscutter/game/avatar/Avatar.java:983`, `:1138`; `src/main/java/emu/grasscutter/server/packet/send/PacketAvatarSkillInfoNotify.java:10`.

## New support interactions we could implement

These are design possibilities, not already-existing Amber knobs:

- Burst supplies an offensive buff; bunny supplies energy or defensive utility, giving the two buttons different jobs.
- A bunny grants an aura to nearby teammates while alive; track gadget ownership, distance and removal, and implement the chosen effect separately.
- Manual detonation grants a stronger reward than timeout, provided the live messages reliably identify that distinction.
- Amber's hit marks a monster; subsequent teammate hits get a bounded damage bonus or trigger energy/healing, with an internal cooldown.
- Charged/weakpoint hits refresh a smaller team benefit, creating an occasional reason to aim instead of only pressing burst.
- A bonus scales from Amber's EM/HP/ATK, changing her support build priorities. Define snapshotting vs live recalculation.
- Enemy resistance/defense debuffs are plausible through stat updates and client synchronization, but must be validated in client-computed damage. A server final-damage amplification can substitute, but is not mechanically the same debuff.
- A bounded incoming-damage reduction is straightforward at the damage-settlement seam. A true shield or interruption resistance is a different, client-facing feature.

A generic `ApplyModifier` does not guarantee a new effect runs or expires correctly. The server handles selected actions, not every action named in ability JSON, and it does not provide a general native modifier timer for arbitrary new client modifiers.

Sources: `src/main/java/emu/grasscutter/game/ability/AbilityManager.java:258`, `:1028`; `src/main/java/emu/grasscutter/game/ability/actions/ActionApplyModifier.java:21`; `src/main/java/emu/grasscutter/game/entity/GameEntity.java:315`; existing scheduler-backed helper in `SymphonistWeaponHelper.java:130`.

## Harder client-controlled behavior

| Desired change | Server-only reality |
|---|---|
| Easier manual detonation hitbox / shoot anywhere on bunny | The client decides projectile collision and detonation trigger. A server substitute may be possible if enough shot information arrives, but changing its server gadget JSON alone does not change that collision. |
| Bunny lasts longer | Its native eight-second timer runs in the client. Keeping a server entity alive is insufficient if the client deletes it. Needs a client-compatible override/refresh strategy or client asset change; no working path established here. |
| Stronger/larger taunt | Native AI selectors, immunity and taunt action are client-driven; the Java action-handler list has no `TriggerTaunt` handler. A new server implementation would need matching client AI behavior. Cannot promise bosses become tauntable. |
| Interruption resistance around bunny | Requires a client-recognized poise/endurance modifier or equivalent synchronized behavior. Lowering incoming HP damage is not interruption resistance. |
| A real shield | Needs client-compatible shield state, absorption/durability handling and presentation. A server-only HP-damage gate can approximate protection, but lacks native shield behavior. |
| Bunny emits repeated Pyro pulses | Server periodic HP damage is possible; actual Pyro aura, reaction ownership and application timing require client-compatible attack/ability behavior. There is no generic `TriggerAttackEvent` Java action handler. |
| Longer Fiery Rain / more ticks / different application interval | Literal timer and attack loops live in the client's ability definition. Larger damage per received tick is easy; creating more real reaction-capable ticks is not the same change. |
| Change elemental application, reaction internal cooldown or aura strength | Much of the reaction calculation happens before the client reports final damage. Rewriting `elementType` on that result does not rerun reactions or apply a correct new aura. |
| Faster bow charging, more arrows, homing projectiles, throw distance | Native input/state machine, projectile movement and collision. Existing numeric specials can sometimes be overridden; the server copy of avatar/gadget animation config is not a client update. |
| New animation, model, audio, icon or rewritten talent tooltip | Needs client assets/UI changes for a reliable native presentation. Existing notifications can explain custom rules, not rewrite the talent screen. |

Native timing/collision evidence: Amber ability file `:1830`, `:3845`, `:4051`, `:4137`, `:5143`; `/home/aim/.local/share/astaps/resources/BinOutput/Gadget/ConfigGadget_SkillObj_Ambor.json`; `/home/aim/.local/share/astaps/resources/BinOutput/Avatar/ConfigAvatar_Ambor.json`.

Client-owned gadget limitation: `src/main/java/emu/grasscutter/game/entity/EntityClientGadget.java:140`; analogous client-skill-object handling: `src/main/java/emu/grasscutter/game/ability/VentiSkillObjHelper.java:13`.

## Deployment and validation

- Character JSON is loaded into memory; plan a server restart and usually scene re-entry/relogin. The artifact drop-table reload behavior does not imply ability/character hot reload.
- A Java hook requires compilation and deployed-class/JAR replacement. Its values can still live in a small dedicated config table; adding such a table would require its loader first.
- Preserve client/server local action and modifier IDs. Adding/reordering native JSON actions or modifier names can redirect client invocations to the wrong action. Prefer scoped hooks over structural edits to the native ability graph. Source: `src/main/java/emu/grasscutter/data/binout/AbilityData.java:41`.
- Native C6 and added custom buffs must not double-count. Test refresh, expiry, character switching, gear/stat recalculation, reconnect, death, team changes, multiple Ambers and co-op range.
- Validate bunny ownership while Amber is off-field; never attribute a summon to whichever teammate happens to be active.
- Check real damage/energy/HP and client feedback separately. Official tooltips and simulator entries will not describe the custom kit.

## Most practical support directions

First establish native C6 amount/duration overrides. Then choose one distinct bunny benefit: more reliable/more numerous particles, a capped party energy refund, a short EM buff, healing or incoming-damage reduction. A conditional charged-shot reward is another code-first option if its hit classification is reliable.

Treat the previously suggested bunny interruption resistance as exploratory, not a confirmed easy server feature. Avoid promising longer bunny/rain duration, altered taunts or easier collision before a client-compatible path is tested.
