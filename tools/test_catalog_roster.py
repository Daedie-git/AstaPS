"""Negative controls for complete five-star coverage and the explicit Traveler exclusion."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('catalog_roster', ROOT / 'customization/catalog_roster.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CatalogRosterTest(unittest.TestCase):
    def test_resources_include_aloy_but_not_travelers_aliases_or_four_stars(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'captures') as directory:
            resources = Path(directory)
            avatars = [dict(id=avatar, useType='AVATAR_FORMAL', qualityType=quality) for avatar, quality in (
                (10000005, 'QUALITY_ORANGE'), (10000007, 'QUALITY_ORANGE'),
                (10000062, 'QUALITY_ORANGE_SP'), (10000106, 'QUALITY_ORANGE'),
                (10000021, 'QUALITY_PURPLE'))]
            cards = [dict(id=card, materialType='MATERIAL_AVATAR', rankLevel=rank,
                          itemUse=[dict(useOp='ITEM_USE_GAIN_AVATAR', useParam=[str(avatar)])])
                     for card, avatar, rank in ((1005, 10000005, 5), (1007, 10000007, 5),
                        (1062, 10000062, 105), (4106, 10000106, 5),
                        (1021, 10000021, 4), (3004, 10000106, 5))]
            (resources / 'AvatarExcelConfigData.json').write_text(json.dumps(avatars))
            (resources / 'MaterialExcelConfigData.json').write_text(json.dumps(cards))
            self.assertEqual({1062, 4106}, set(module.five_star_cards(resources)))

    def test_old_catalog_fails_for_missing_mavuika(self):
        phases = [dict(characters=[dict(card=1093), dict(card=1093)])]
        with self.assertRaisesRegex(ValueError, 'missing=\\[4106\\]'):
            module.validate_coverage(phases, {1093, 4106})

    def test_future_resource_character_cannot_be_silently_omitted(self):
        phases = [dict(characters=[dict(card=1093), dict(card=1093), dict(card=4106), dict(card=4106)])]
        with self.assertRaisesRegex(ValueError, 'missing=\\[4999\\]'):
            module.validate_coverage(phases, {1093, 4106, 4999})

    def test_complete_coverage_and_repeat_policy(self):
        phases = [dict(characters=[dict(card=1093), dict(card=1093), dict(card=4106), dict(card=4106)])]
        self.assertEqual(2, module.validate_coverage(phases, {1093, 4106})[1093])
        phases[0]['characters'].append(dict(card=4106))
        with self.assertRaisesRegex(ValueError, 'repeat, twice each'):
            module.validate_coverage(phases, {1093, 4106})

    def test_diluc_then_mavuika_order(self):
        phases = json.loads((ROOT / 'customization/rotation-catalog.json').read_text())['phases']
        self.assertEqual([1016, 1093], [c['card'] for c in phases[1]['characters']][:2])
        self.assertEqual([4106, 4102], [c['card'] for c in phases[2]['characters']][:2])
        self.assertEqual(4101, phases[6]['characters'][2]['card'])

    def test_tracked_catalog_covers_the_roster_snapshot(self):
        phases = json.loads((ROOT / 'customization/rotation-catalog.json').read_text())['phases']
        roster = json.loads((ROOT / 'customization/five-star-roster.json').read_text())
        counts = module.validate_coverage(phases, {character['card'] for character in roster})
        self.assertTrue({4106, 1062}.issubset(counts))
        self.assertFalse({1005, 1007} & counts.keys())
        traveler = json.loads(json.dumps(phases))
        traveler[0]['characters'][0]['card'] = 1005
        with self.assertRaisesRegex(ValueError, 'unexpected=\\[1005\\]'):
            module.validate_coverage(traveler, counts.keys())
        broken = json.loads(json.dumps(phases))
        for phase in broken:
            phase['characters'] = [character for character in phase['characters'] if character['card'] != 4106]
        with self.assertRaisesRegex(ValueError, 'missing=\\[4106\\]'):
            module.validate_coverage(broken, counts.keys())


if __name__ == '__main__':
    unittest.main()
