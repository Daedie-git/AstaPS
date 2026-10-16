"""Negative controls for canonical character coverage and the wish roster exclusions."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('catalog_roster', ROOT/'customization/catalog_roster.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CatalogRosterTest(unittest.TestCase):
    def test_canonical_roster_excludes_aloy_travelers_and_aliases(self):
        with tempfile.TemporaryDirectory(prefix='astaps-catalog-') as directory:
            resources = Path(directory)
            avatars = [dict(id=avatar,useType='AVATAR_FORMAL',qualityType=quality) for avatar,quality in (
                (10000005,'QUALITY_ORANGE'),(10000007,'QUALITY_ORANGE'),
                (10000062,'QUALITY_ORANGE_SP'),(10000106,'QUALITY_ORANGE'),(10000021,'QUALITY_PURPLE'))]
            cards = [dict(id=card,materialType='MATERIAL_AVATAR',rankLevel=rank,
                itemUse=[dict(useOp='ITEM_USE_GAIN_AVATAR',useParam=[str(avatar)])])
                for card,avatar,rank in ((1005,10000005,5),(1007,10000007,5),(1062,10000062,105),
                    (4106,10000106,5),(1021,10000021,4),(3004,10000106,5))]
            (resources/'AvatarExcelConfigData.json').write_text(json.dumps(avatars))
            (resources/'MaterialExcelConfigData.json').write_text(json.dumps(cards))
            self.assertEqual({4106},set(module.five_star_cards(resources)))
            self.assertEqual({4106,1021},set(module.character_cards(resources)))

    def test_missing_new_character_is_rejected(self):
        phases = [dict(banners=[dict(bannerType='CHARACTER2',rateUpItems4=[1021],rateUpItems5=[4106])])]
        with self.assertRaisesRegex(ValueError,'missing=\\[4999\\]'):
            module.validate_coverage(phases,{4106,1021,4999})

    def test_missing_four_star_is_rejected(self):
        phases = [dict(banners=[dict(bannerType='CHARACTER2',rateUpItems4=[],rateUpItems5=[4106])])]
        with self.assertRaisesRegex(ValueError,'missing=\\[1021\\]'):
            module.validate_coverage(phases,{4106,1021})

    def test_tracked_catalog_covers_both_rarity_snapshots(self):
        phases=json.loads((ROOT/'customization/rotation-catalog.json').read_text())['phases']
        roster=json.loads((ROOT/'customization/character-roster.json').read_text())
        counts=module.validate_coverage(phases,{r['card'] for r in roster})
        self.assertEqual(119,len(counts))
        self.assertTrue({1006,1015,1021,4106}.issubset(counts))
        self.assertFalse({1005,1007,1062}&counts.keys())

    def test_aloy_cannot_be_added_back(self):
        phases=[dict(banners=[dict(bannerType='CHARACTER2',rateUpItems4=[1021],rateUpItems5=[4106,1062])])]
        with self.assertRaisesRegex(ValueError,'unexpected=\\[1062\\]'):
            module.validate_coverage(phases,{4106,1021})

    def test_report_matches_actual_event_frequencies(self):
        phases=json.loads((ROOT/'customization/rotation-catalog.json').read_text())['phases']
        report=json.loads((ROOT/'customization/banner-fairness.json').read_text())
        five,four=module.event_counts(phases)
        for row in report['characters']:
            self.assertEqual((five if row['rarity']==5 else four)[row['card']],row['eventAppearances'])


if __name__ == '__main__': unittest.main()
