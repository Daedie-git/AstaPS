# Character damage tuning

Both settings live under `server.game.gameOptions`, default to `1.0` (unchanged), and require a game-service restart after editing.

| Setting | Scope | Installed value |
|---|---|---:|
| `keqingElectroDamageMultiplier` | Keqing-owned Electro hits | `1.5` (+50%) |
| `dilucDamageMultiplier` | All Diluc-owned hits, including physical attacks and plunges | `1.25` (+25%) |

Scaling applies only against monsters/DPS dummies, to final client-reported damage. Existing Aggravate, Melt/Vaporize and Xianyun plunge contributions already included in the hit scale too. Separately reported reaction hits qualify only if their owner/element matches the setting; global reaction coefficients remain unchanged. The settings do not stack on one character or alter teammates' own damage, stats, energy or cooldowns.

Ownership follows the attacker avatar or up to eight client-gadget owner links. Known immediate ownership takes precedence over original ownership. Missing/cyclic ownership leaves damage unchanged; the active character or team roster is never used as a fallback.

`HandlerCombatInvocationsNotify` applies the multiplier once after existing damage/target repairs, before energy handling and the server damage queue. The queued attack and forwarded combat packet contain the same adjusted result. Changed hits are forwarded to all clients, including the attacker. Client-local damage-number reconciliation remains unverified; the original client prediction may still display the unscaled number.

## Test

Run with Java 21 and a disk-backed `TMPDIR`:

```bash
bash gradlew test --tests 'emu.grasscutter.game.entity.CharacterDamageTuningTest' -PexcludeTags=integration
```

Tests cover Keqing 100→150, Diluc 100→125 (including plunges/physical damage), unchanged teammates, independent settings, gadget ownership/cycles, monster-only targets, invalid floats, old config defaults, and actual combat-handler queue/forward/scene application. They use offline fixtures, not account records or a running game.

## Undo

Set either live multiplier to `1.0` and restart `astaps.service` to disable only that character's buff. Source builds preserve the hook but default both settings off; remove both fields, the helper and handler call to remove the implementation.
