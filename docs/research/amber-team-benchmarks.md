# Amber team benchmarks

Retrieved 2026-10-09 from the [gcsim community database](https://db.kqm.gg/), extending `weak-character-candidates.md`. No balance changes.

The Amber listing returned 32 entries. Filtered to C0 five-stars and character levels at most 90; the detailed examples here use three-/four-star weapons. These are the strongest Amber-focused examples found under those limits, not proven best teams or equal-investment comparisons. Different aiming assumptions and support investment matter more than small differences in ranking. All figures below use detailed team and individual results from the same simulation.

## Amber-focused teams

| Team | Amber constellation | Team DPS | Amber DPS / share | Amber equipment | Important assumption |
|---|---|---:|---:|---|---|
| Amber / Furina / Bennett / Rosaria | C1 | 41,451 | 20,934 / 50.5% | Sharpshooter's Oath R5; Wanderer's Troupe | 100% weakspot accuracy; optimized low Crit Rate build. |
| Amber / Diona / Bennett / Rosaria | C6 | 40,306 | 26,043 / 64.6% | Song of Stillness R5; Crimson Witch | 70% weakspot accuracy; manually detonated bunnies. |
| Amber / Furina / Bennett / Xilonen | C6 | 39,729 | 16,949 / 42.7% | Scion of the Blazing Sun R1; Marechaussee Hunter | 20% weakspot accuracy; manually detonated bunnies; no waiting for Hydro aura. |
| Amber / Chongyun / Bennett / Rosaria | C6 | 39,304 | 17,468 / 44.4% | Stringless R3; Crimson Witch | 70% weakspot accuracy; manual bunny detonations and Chongyun burst damage. |
| Amber / Zhongli / Bennett / Rosaria | C2 | 38,644 | 25,374 / 65.7% | Hamayumi R5; Wanderer's Troupe | 100% weakspot accuracy; shielded charged-shot play, one manual bunny detonation per rotation. |
| Amber / Furina / Bennett / Mona | C6 | 37,881 | 17,582 / 46.4% | Song of Stillness R5; Marechaussee Hunter | No manual bunny shots; attempts 50% weakspots on initial shot, then 20%. |

Sources: [Furina Melt](https://db.kqm.gg/db/PmjnHMfHQPMM), [Diona Melt](https://db.kqm.gg/db/7DGgwFBWfcQK), [Xilonen Vape](https://db.kqm.gg/db/N8qhWGPkchDJ), [Chongyun Melt](https://db.kqm.gg/db/C8MzwcT6gLdq), [Zhongli Melt](https://db.kqm.gg/db/fprDpqJrPpd7), [Mona Vape](https://db.kqm.gg/db/TpzKcKNznWdn).

Diona, Bennett, Rosaria and Chongyun are C6 in the relevant examples; Furina, Xilonen, Mona and Zhongli are C0. Constellation notation in a table describes the specific simulation, not a requirement that every constellation is needed to play the team. Manual bunny detonation requires Amber C2; C4 supplies another charge and reduces cooldown.

### Useful damage-tuning baseline

The Diona/Bennett/Rosaria example attributes about 11,904 DPS to Melted manual bunny explosions and 10,912 DPS to Melted main charged shots. Together they supply about 88% of Amber's own damage. Her C1 additional arrow supplies another 849 DPS and Fiery Rain 2,234 DPS.

This is more informative for tuning her damage than the higher-total support teams below. The C1/Furina result's slightly higher team DPS does not establish that C1 Amber is stronger than C6: the supports, weapons, artifact builds and aiming assumptions differ. Perfect weakspot assumptions also fail against enemies without usable weakspots.

## Stronger teams featuring support Amber

| Team | Amber constellation | Team DPS | Amber DPS / share | What drives the damage |
|---|---|---:|---:|---|
| Gaming / Furina / Xianyun / Amber | C6 | 78,221 | 841 / 1.1% | Gaming contributes 67,705 DPS; Amber uses Favonius/Exile and her burst. |
| Nahida / Xingqiu / Raiden / Amber | C0 | 64,335 | 505 / 0.8% | Raiden contributes 43,292 DPS, including Hyperbloom; Amber is mostly a spare slot. |
| Lyney / Amber / Lynette / Bennett | C6 | 53,604 | 1,965 / 3.7% | Lyney contributes 45,311 DPS; Amber uses Favonius/Tenacity. |

Sources: [Gaming](https://db.kqm.gg/db/JLPQHzdNJpDB), [Hyperbloom](https://db.kqm.gg/db/TCfM9rQrL6hp), [Lyney](https://db.kqm.gg/db/D77GHppkfQPD).

Damage share does not measure the full value of buffs or energy supplied to teammates. These results show why increasing Amber's personal damage alone would barely move these team totals at ordinary buff sizes; they do not prove she supplies no support value.

## Evidence and limitations

Full results, configurations and attack-source breakdowns: `captures/amber-team-details.json`. The two earlier cached Melt results were reused; seven additional detailed results were fetched for this comparison. Listing evidence: `captures/weak-character-extra-listings.json`.

All nine simulations run four rotation cycles against one level-100 target with 10% base resistance. They differ in energy-drop assumptions, artifact builds and simulator revisions. These are simulator results, not measurements on the installed AstaPS server; bunny targeting/detonation and support mechanics still need in-game validation.
