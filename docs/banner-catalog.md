# Daily banner catalog

`customization/rotation-catalog.json` mirrors the deployed nine-set catalog. It retains seven tabs: Standard, four character slots and two weapon pairs. Rates, pity routing and Standard content remain unchanged.

| Set | Character slots 1–4 |
| --- | --- |
| 1 | Kazuha, Xilonen, Yelan, Arlecchino |
| 2 | Diluc, Xianyun, Keqing, Jean |
| 3 | Nahida, Alhaitham, Nilou, Kokomi |
| 4 | Raiden, Furina, Clorinde, Baizhu |
| 5 | Ayaka, Shenhe, Wriothesley, Sigewinne |
| 6 | Neuvillette, Emilie, Mualani, Venti |
| 7 | Navia, Zhongli, Itto, Chiori |
| 8 | Xiao, Xianyun, Hu Tao, Yae Miko |
| 9 | Yoimiya, Citlali, Ayato, Escoffier |

Xianyun appears twice; the other 34 characters appear once. Weapon pairs follow slots 1/2 and 3/4. Set 2 pairs Wolf's Gravestone/Crane's Echoing Call and Primordial Jade Cutter/Aquila Favonia. Keqing/Jean's weapons are selected five-star options, not official signatures. Diluc, Keqing and Jean use Standard artwork; configured pools determine rewards, not the artwork.

The catalog advances at midnight Europe/Amsterdam, checked every 30 seconds or when Wishes is requested. `/catalog` shows the position; `/catalog next` advances manually. Both require `server.catalog`. Close and reopen Wishes afterward. Shared character pity and shared weapon pity/target state persist; weapon banners do not have independent Epitomized Path records.

## Generate and validate

From the repo, with Java 21 and a disk-backed `TMPDIR`:

```bash
mkdir -p captures/catalog-check
python3 customization/build-rotation.py --server-root /home/aim/.local/share/astaps --output captures/catalog-check/rotation-catalog.json
cmp customization/rotation-catalog.json captures/catalog-check/rotation-catalog.json
cp src/main/resources/defaults/data/Banners.json captures/catalog-check/Banners.json
javac -cp /home/aim/.local/share/astaps/grasscutter.jar -d captures/catalog-check tools/CheckRotation.java
```

Run the compiled checks from the installed server directory so its startup resource checks find the installed resources:

```bash
cd /home/aim/.local/share/astaps
java -Djava.io.tmpdir="$TMPDIR" -cp grasscutter.jar:/home/aim/git/astaps/captures/catalog-check CheckRotation "$PWD/grasscutter.jar" /home/aim/git/astaps/captures/catalog-check
```

Checks cover rates, seven stable schedule IDs, featured pools and weapon targets, Diluc/Xianyun as set 2, Xiao/Xianyun as set 8, midnight/DST, nine-day wrap, restart/catch-up, concurrent advancement and failure retention. They execute installed load/refresh/command hooks against isolated state; no wishes or account records change. Client artwork and actual wishes remain unverified.

## Deploy

Stop the game service, back up the installed catalog and state, copy the tracked catalog to the installation's `customization/rotation-catalog.json`, then restart. Preserve `rotation-state.json`; it is runtime state and is deliberately not tracked. When changing phase order on an existing installation, map its active set to the new index rather than blindly retaining an index that now names another set. This deployment retained set 1 at index 0; set 2 is next.

`customization/game-options.json` records only the four deployed character tuning values. Merge these keys into `server.game.gameOptions` in the installation's config; this file is not loaded automatically and is not a replacement for the full config. See [character damage tuning](character-damage-tuning.md) and [Amber support](amber-support.md).

To undo only the catalog change, stop the service, restore its catalog/state backup, then restart. Leave the archive and character tuning intact. The installed backup/manifest paths are recorded in `/home/aim/SYSTEM_CHANGES.md`.
