# Amber support tuning

`server.game.gameOptions` accepts:

| Setting | Source default | Installed value |
| --- | --- | --- |
| `amberBurstHealHpRatio` | `0` (disabled) | `0.03` |
| `amberC6AttackMultiplier` | `1` (original) | `2` |

Amber's confirmed burst starts six party-healing ticks at 2, 4, 6, 8, 10 and 12 seconds. Each heals living members of her loaded party for 3% of her snapshotted maximum HP, multiplied by her snapshotted healing bonus and each recipient's current incoming-healing bonus. This works at C0 and after switching; no field requirement. Recasting refreshes rather than stacks. Missed intervals during a server stall are skipped, not paid out together.

Logout, scene changes, changing party composition, caster removal or caster death cancel the effect. Normal healing restrictions, Bond of Life, HP clamping and native heal notifications/hooks remain in force. Co-op guests outside the caster's loaded party are not added as recipients.

The C6 override changes only `Ambor_Talent_216_P2_AttackUp`: the native 15% ATK buff becomes 30%. It still requires C6; movement speed remains 15% and duration remains 10 seconds. Native skill damage, Pyro application, energy, cooldowns and Baron Bunny stay unchanged. Client tooltips and visual effects remain original; validate the received ATK and HP in game rather than trusting the tooltip.

## Validation and deployment

`AmberSupportTest` exercises real heal handlers, scene-entry serialization, all four ability-ingress handlers, burst confirmation, real-time scheduling, restrictions and cleanup. Set `combatJar` to verify classes load from a staged/deployed archive rather than source output.

`tools/install_amber_support.py prepare --stage PATH --expected-sha256 HASH` copies the installed archive, replacing only the listed changed classes and adding `AmberSupport*.class`. It verifies all other archive payloads and config values remain unchanged. Test that staged archive before stopping `astaps.service` and running the utility's `install` mode. Installation checks baseline/staged digests, makes backups, and refuses to run while the service is active.

To disable the tuning, set the heal ratio to `0` and C6 multiplier to `1`, then restart. To undo the binary patch, stop the service and restore both backups recorded in `/home/aim/.local/share/astaps/customization/amber-support-manifest.json`; restarting then returns to the prior Keqing/Diluc-tuned archive.
