# Daily Team Banner Catalog

Every day offers **five limited character tabs, one weapon-only Chronicled Wish with their five associated weapons, and unchanged Standard**. There are seven visible schedules: Standard `200`, characters `7101`–`7105`, and Chronicled `7300`. The old paired weapon tabs `7200`/`7201` disappear on reload. Character tabs retain shared event-character pity; Chronicled retains its separate pity record. Existing event-weapon pity is not transferred or erased.

## Schedule

The 18-day cycle starts on 2026-10-10, at midnight Europe/Amsterdam. The first character is always the day's sole Archon. The sequence is Venti → Mavuika → Nahida → Furina → Raiden → Zhongli, repeated three times; wrap continues the same sequence. Tomorrow, 2026-10-11, is Mavuika.

| Day | Archon | Other Limited Characters |
| --- | --- | --- |
| 1 | Venti | Mualani, Kazuha, Xilonen, Chasca |
| 2 | Mavuika | Citlali, Xilonen, Mualani, Kinich |
| 3 | Nahida | Alhaitham, Nilou, Kokomi, Cyno |
| 4 | Furina | Neuvillette, Wriothesley, Xianyun, Sigewinne |
| 5 | Raiden | Yae Miko, Kazuha, Yelan, Eula |
| 6 | Zhongli | Hu Tao, Yelan, Albedo, Xiao |
| 7 | Venti | Ayaka, Shenhe, Kokomi, Ganyu |
| 8 | Mavuika | Emilie, Kinich, Chasca, Citlali |
| 9 | Nahida | Nefer, Lauma, Baizhu, Clorinde |
| 10 | Furina | Skirk, Escoffier, Ayato, Yoimiya |
| 11 | Raiden | Varesa, Xianyun, Eula, Wanderer |
| 12 | Zhongli | Itto, Chiori, Navia, Linnea |
| 13 | Venti | Klee, Lyney, Arlecchino, Tartaglia |
| 14 | Mavuika | Lohen, Nicole, Durin, Varka |
| 15 | Nahida | Columbina, Ineffa, Flins, Sandrone |
| 16 | Furina | Vesna, Vodyanitsa, Odette, Zibai |
| 17 | Raiden | Yae Miko, Alhaitham, Baizhu, Ayato |
| 18 | Zhongli | Neuvillette, Navia, Yoimiya, Arlecchino |

These are collections of team options, not five-character teams. Day 2 supports Mavuika/Citlali/Xilonen melt, or off-field Mavuika for Mualani or Kinich. Nahida days offer bloom/quicken options; Furina days include healing and compatible damage dealers; Raiden offers energy, Electro application and superconduct; Zhongli offers shielding; Venti offers grouping and resistance reduction. Venti's burst does not suit every melee enemy matchup. Synergy for the newer resource-only kits, especially days 14–16, remains unverified; resource presence is not evidence of complete gameplay.

`customization/team-banner-order.json` is the authoritative curated order. The previous historical pair order is retained only as legacy reference, not used by the generator. All 60 non-Standard five-stars appear; non-Archons appear one or two times, each Archon three times. Standard's eight five-star characters and all 51 four-stars remain obtainable there. Aloy and the Travelers remain excluded. Four-star event appearances count each individual tab, no longer each historical pair. `banner-fairness.json` records event days and Standard availability separately.

## Weapons And UI

Chronicled offers exactly the five active characters' weapons **in character-tab order**, with no character drops. Select one before wishing. A lost five-star gives another of the remaining four weapons and one Fate Point; the next five-star guarantees the chosen weapon. Four-star drops are also weapons only. Costs and rarity curves stay unchanged. A target absent from a later day must be selected again.

Day 2 weapons: A Thousand Blazing Suns, Starcaller's Watch, Peak Patrol Song, Surf's Up, Fang of the Mountain King. The historical Wriothesley/Furina source lists weapons in opposite order; explicit overrides prevent a wrong association. Albedo has no dedicated five-star signature, so his historical Primordial Jade Cutter pairing is used. Klee similarly uses her historical Lost Prayer pairing. These exceptions are recorded in the order file rather than called dedicated signatures.

Character portraits and depicted four-star pools remain historical and match each other. Chronicled uses the installed selector prefab `GachaShowPanel_N?5` and tab `UI_Tab_GachaShowPanel_N5`; its selectable item list carries the actual five weapons. No new artwork or client resources are installed. The stock Chronicle title/backdrop is reused, not a custom five-weapon illustration. Packet tests verify the five weapon choices, item categories and retained selection across reopens; actual visual readability still needs an in-client check.

## Validate

```bash
python3 customization/build-rotation.py --server-root /home/aim/.local/share/astaps
python3 tools/test_catalog_roster.py
python3 tools/test_real_banner_catalog.py
JAVA_HOME=/home/aim/.local/share/astaps/runtime/jdk PATH=/home/aim/.local/share/astaps/runtime/jdk/bin:$PATH bash gradlew test --tests 'emu.grasscutter.game.gacha.*' --no-daemon
mkdir -p captures/team-catalog-check
cp customization/rotation-catalog.json src/main/resources/defaults/data/Banners.json captures/team-catalog-check/
/home/aim/.local/share/astaps/runtime/jdk/bin/javac -cp build/classes/java/main:/home/aim/.local/share/astaps/grasscutter.jar -d captures/team-catalog-check tools/CheckRotation.java
cd /home/aim/.local/share/astaps
/home/aim/.local/share/astaps/runtime/jdk/bin/java -cp /home/aim/git/astaps/build/classes/java/main:/home/aim/git/astaps/captures/team-catalog-check:grasscutter.jar CheckRotation grasscutter.jar /home/aim/git/astaps/captures/team-catalog-check
```

Checks use fake accounts, sessions and isolated catalog state; they do not change real inventories or wishes. They cover layout, portrait pools, all limiteds, matching weapon pools, rates/costs/targets, the one-point guarantee, midnight/DST, anchor initialization, persistence, wrap, concurrent advances, malformed-state retention, actual client packets and stale-slot removal.

## Deploy And Roll Back

This change is prepared and tested in the checkout, **not deployed**. It needs the updated Java classes as well as the catalog: `CatalogStore` accepts the new layout, and `GachaBanner`/`GachaSystem` stop old linked-tab migration from clearing standalone Chronicled weapon selections.

Before deployment, stop the game service when empty and back up its archive, changed customization files and `rotation-state.json`. Install the built archive and generated catalog together. Explicitly set the new state to the number of local calendar days since 2026-10-10 modulo 18, with today's local date; do not preserve an index from the old 25-day cycle. When no state exists, the new catalog's anchor date supplies this alignment automatically. Restart and check `/catalog`, then reopen Wishes and inspect all seven tabs and the weapon selector. Do not transfer player pity or modify Mongo records. To roll back, stop the service, restore the backed-up archive, catalog and old state together, then restart.

`/catalog next` advances manually; `/catalog` reports without advancing. `/catalog <number>` selects a set directly using the displayed one-based numbering (for example, `/catalog 5`). Out-of-range numbers leave the active set unchanged. Selection persists across restarts and daily rotation resumes from the selected set at the next local midnight. Automatic refresh runs every 30 seconds and when Wishes opens, at local midnight Europe/Amsterdam.
