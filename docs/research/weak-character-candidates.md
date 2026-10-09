# Further character-tuning candidates

Retrieved 2026-10-09 from the [gcsim community database](https://db.kqm.gg/). Research only; no additional balance changes.

## Scope

Screened 18 characters: Eula, Yoimiya, Cyno, Ayato, Dehya, Razor, Freminet, Xinyan, Amber, Gaming, Noelle, Itto, Wriothesley, Heizou, Ningguang, Lisa, Klee, and Albedo. Each listing query requested the top 100 single-target entries. Five-stars were filtered to C0 and character levels to at most 90; four-star constellations were allowed. Detailed results below use three-/four-star weapons, including battle-pass and event weapons. Four-stars are C6 unless noted.

These are strong examples found, not proven global maxima. The query cap, varied optimization, different simulator builds and team roles prevent a universal tier ranking. Listed team DPS and personal shares come from the same detailed result, not the occasionally different listing summary. No performance was measured on AstaPS, and newer support mechanics need verification in the installed client/server.

## Main candidates

DPS is damage per second against one target. Personal contribution includes the character's attributed reactions and any support additions inside their hits; it does not isolate their unbuffed talent strength.

| Character | Example team | Team DPS | Own DPS / share | Initial tuning focus |
|---|---|---:|---:|---|
| Itto | Xilonen / Furina / Yelan | 66,369 | 31,150 / 46.9% | Geo charged slashes and Ushi; keep the DEF-based playstyle. |
| Dehya | Furina / Bennett / Xilonen | 50,082 | 19,017 / 38.0% | Burst punches/finisher and skill, rather than fixing her through plunge attacks. |
| Dehya | Fischl / Chevreuse / Yae Miko | 54,708 | 13,094 / 23.9% | Distinguish talent damage from Overload before setting a multiplier. |
| Cyno | Chevreuse / Fischl / Durin | 80,282 | 36,143 / 45.0% | Burst-state normals, skill and Duststalker Bolts. |
| Klee | Furina / Yelan / Xilonen | 80,218 | 25,514 / 31.8% | Charged attacks first, then skill/burst if needed. |
| Ningguang | Xilonen / Mavuika / Bennett | 75,568 | 36,924 / 48.9% | Burst, charged attacks/Star Jades and skill. |

Sources: [Itto](https://db.kqm.gg/db/RbwfzgQmJp6q), [Dehya vape](https://db.kqm.gg/db/HwmzLNdLtWpP), [Dehya Overload](https://db.kqm.gg/db/GR8wTh6TqCMb), [Cyno](https://db.kqm.gg/db/cNGqFd8pBcmF), [Klee](https://db.kqm.gg/db/RnwbDdh6bM8n), [Ningguang](https://db.kqm.gg/db/p69Qzh9Lj9L9).

- Itto's two charged-slash categories contribute 18,040 DPS, and Ushi 6,466 DPS in the Geo example. His Bennett/Furina/Xianyun Pyro-plunge alternative reaches 67,412 DPS, with 81.2% attributed to Itto and 50% plunge collisions assumed. A Geo-specific change would avoid prioritizing this alternative. [Plunge source](https://db.kqm.gg/db/TmnJjrcwbRNb).
- Dehya's Overload example includes 6,720 DPS of Overload: only about 6,374 DPS is her other damage. Doubling just burst punches/finisher would add about 4,712 team DPS, not double the team. Her Xianyun vape-plunge alternative reaches 65,832 DPS, with 77.0% attributed to Dehya and 50% collisions assumed. [Plunge source](https://db.kqm.gg/db/KghbcTmcrN78).
- Cyno's Overload example attributes effectively no Overload damage to Cyno. His Xingqiu/Fischl/Nahida Hyperbloom example reaches 68,837 DPS with 50.3% attributed to him, but that attribution includes reactions. Keep global Hyperbloom coefficients and Fischl unchanged. [Hyperbloom source](https://db.kqm.gg/db/GKP8RnDdjzq9).
- Klee's charged attacks contribute 10,450 DPS, about 41% of her personal damage. A +50% personal-damage increase would add about 12,757 team DPS in this example; +50% charged damage alone would add about 5,225. Those are fixed-rotation projections, not fresh simulations or server measurements.
- Ningguang's burst categories contribute 14,914 DPS and Jade Screen 6,636 DPS. Mavuika supplies 44.5% of the team total, so a strong team result should not be mistaken for Ningguang carrying all its damage.

I would examine Itto, Cyno and Dehya first. Dehya requires the most care: increasing only her actual talent damage may still leave her team far behind, while increasing all her hits also strengthens infused-plunge alternatives.

## Other clear damage-focused candidates

| Character | Example team | Team DPS | Own DPS / share | Limitation |
|---|---|---:|---:|---|
| Amber C6 | Diona / Bennett / Rosaria | 40,306 | 26,043 / 64.6% | Assumes 70% weakspot accuracy and manually detonated bunnies. |
| Xinyan C6 | Furina / Rosaria / Xianyun | 55,813 | 33,989 / 60.9% | Physical plunge, 50% collisions; shield reliability remains a separate issue. |
| Xinyan C6 | Rosaria / Fischl / Bennett C5 | 40,187 | 15,515 / 38.6% | Conventional physical normals; charged/spin behavior is not fully represented across database entries. |
| Heizou C6 | Faruzan / Furina / Xianyun | 62,668 | 45,157 / 72.1% | This hypercarry-plunge example assumes 100% collisions; not a general judgment of his support/driver role. |

Sources: [Amber](https://db.kqm.gg/db/7DGgwFBWfcQK), [Xinyan plunge](https://db.kqm.gg/db/FCkGpHQKCwBw), [Xinyan normals](https://db.kqm.gg/db/tw8RttgdrRqf), [Heizou](https://db.kqm.gg/db/JCBRgkCqnzMN).

Amber C1 with Furina/Bennett/Rosaria reaches 41,451 DPS and 50.5% personal share, but assumes 100% weakspot accuracy. It is not a fair lower-investment superiority claim over the C6 example with different supports and aiming assumptions. [Source](https://db.kqm.gg/db/PmjnHMfHQPMM).

## Cases that need role-specific decisions

- **Eula:** physical Furina/Shenhe/Escoffier reaches 73,090 DPS, with Eula contributing 28,916 (39.6%). Her Citlali/Bennett/Xianyun Pyro-infused melt-plunge example reaches 93,032 DPS with 92.7% attributed to her, assuming 50% collisions. Prefer investigating physical normals/Lightfall damage rather than a blanket multiplier. [Physical](https://db.kqm.gg/db/dHNbrBTPbRww), [plunge](https://db.kqm.gg/db/8R7bRtPpbhzJ).
- **Ayato:** Furina/Citlali/Escoffier reaches 74,767 DPS with 37.3% personal contribution. Columbina/Lauma/Ineffa reaches 108,904, but Ayato supplies only 12.2%, while Ineffa supplies 52.1%. He remains a personal-damage candidate, not evidence that all his teams are weak. [Freeze](https://db.kqm.gg/db/DKwGzQCDtjRp), [lunar team](https://db.kqm.gg/db/nnPTctLN8JHz).
- **Wriothesley:** Furina/Yelan/Escoffier reaches 82,373 DPS with 30.4% personal contribution. Other screened compositions approach 98k while Durin supplies most of the damage; do not treat those as a Wriothesley-only ceiling. [Detailed example](https://db.kqm.gg/db/DPG7nH8rPtHQ); composition descriptions are retained in the listing evidence.
- **Yoimiya:** Yelan/Fischl/Durin reaches 97,587 DPS with 42.7% personal contribution. Lower priority relative to the approximately 100k team target. This does not establish equal performance against multiple targets. [Source](https://db.kqm.gg/db/Jq7cpQw77rDQ).
- **Razor, Gaming and Noelle:** the screened listings include eligible examples over 100k. Gaming's 132k example is one-rotation speedrunning and should not be compared as sustained output; other listed Gaming teams exceed 109k with newer supports. Do not automatically buff these three based on older reputations. Full results were not retrieved for these listing-only comparisons.
- **Lisa and Albedo:** low personal damage does not by itself establish that their support/off-field roles need buffs. Their stronger listed teams are driven by another damage dealer.

## Evidence

- `captures/weak-character-listings.json`: retrieval timestamp, complete listing URLs, fetched counts and selected eligible compositions.
- `captures/weak-character-extra-listings.json`: additional untrimmed eligible Dehya/Xinyan/Amber listings.
- `captures/weak-character-details.json`: complete detailed API responses for 22 inspected simulations, including configuration, weapons, constellations, damage sources, simulator build and target settings.
- Detailed endpoint: `https://db.kqm.gg/api/share/db/<entry-id>`.

The level-100 detailed targets inspected have 10% base resistance to each damage type. Character/weapon investment, timing, enemy behavior, survivability and multi-target coverage still vary. A damage multiplier cannot repair interruption, targeting, uptime or unsupported client/server mechanics.
