"""Regression checks for curated daily teams, portrait bindings and matching weapons."""
from collections import Counter
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT/'customization'))
spec = importlib.util.spec_from_file_location('build_rotation', ROOT/'customization/build-rotation.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class TeamBannerCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads((ROOT/'customization/rotation-catalog.json').read_text())
        cls.history = json.loads((ROOT/'customization/historical-banners.json').read_text())
        cls.order = json.loads((ROOT/'customization/team-banner-order.json').read_text())
        cls.standard = json.loads((ROOT/'src/main/resources/defaults/data/Banners.json').read_text())[0]

    def test_exact_layout_and_unchanged_standard(self):
        for p in self.catalog['phases']:
            self.assertEqual(self.standard, p['banners'][0])
            self.assertEqual(['STANDARD']+['CHARACTER2']*5+['CHRONICLE'],
                             [b['bannerType'] for b in p['banners']])
            self.assertEqual([200,7101,7102,7103,7104,7105,7300],
                             [b['scheduleId'] for b in p['banners']])
            self.assertEqual(7, len({b['sortId'] for b in p['banners']}))

    def test_character_pools_match_their_artwork(self):
        art = {c['prefabPath']: (c['card'], p['fourStars'])
               for p in self.history['phases'] for c in p['characters']}
        for p in self.catalog['phases']:
            for b in p['banners'][1:6]:
                self.assertEqual(([b['rateUpItems5'][0]], b['rateUpItems4']),
                                 ([art[b['prefabPath']][0]], art[b['prefabPath']][1]))

    def test_weapons_match_all_five_active_characters_in_order(self):
        signatures = {c['card']:p['weapons'][i] for p in self.history['phases']
                      for i,c in enumerate(p['characters'])}
        # Wriothesley/Furina's source weapons are in the opposite order.
        signatures.update({1086:14513,1089:11513})
        for p in self.catalog['phases']:
            chronicle = p['banners'][-1]
            expected = [signatures[b['rateUpItems5'][0]] for b in p['banners'][1:6]]
            self.assertEqual(expected, chronicle['rateUpItems5'])
            self.assertEqual(expected, chronicle['epitomizedItems5'])
            self.assertEqual(expected, chronicle['fallbackItems5Pool2'])
            self.assertEqual(5, len(set(expected)))
            self.assertTrue(all(x >= 10000 for x in expected+chronicle['rateUpItems4']))
            self.assertEqual([], chronicle['fallbackItems5Pool1'])
            self.assertEqual([], chronicle['fallbackItems4Pool1'])
            self.assertEqual('WEAPON', chronicle['epitomizedTarget'])
            self.assertFalse(chronicle['autoStripRateUpFromFallback'])

    def test_one_archon_per_day_in_sequence_including_wrap(self):
        phases = self.catalog['phases']
        expected = [1022,4106,1073,1089,1052,1030]
        self.assertEqual(18, len(phases))
        for i,p in enumerate(phases+phases[:6]):
            featured = [b['rateUpItems5'][0] for b in p['banners'][1:6]]
            self.assertEqual(expected[i%6], featured[0])
            self.assertEqual({expected[i%6]}, set(featured)&set(expected))
            self.assertEqual(5, len(set(featured)))

    def test_tomorrows_team_and_signature_weapons(self):
        self.assertEqual('2026-10-10', self.catalog['anchorDate'])
        p = self.catalog['phases'][1]
        self.assertEqual([4106,4107,4103,4102,4101], [c['card'] for c in p['characters']])
        self.assertEqual([12514,14517,11516,14516,12513],p['banners'][-1]['rateUpItems5'])

    def test_all_limiteds_have_bounded_exposure_and_standard_characters_stay_standard(self):
        counts = Counter(c['card'] for p in self.catalog['phases'] for c in p['characters'])
        roster = json.loads((ROOT/'customization/five-star-roster.json').read_text())
        expected = {r['card'] for r in roster}-set(self.standard['fallbackItems5Pool1'])
        self.assertEqual(expected, set(counts))
        self.assertTrue(all(n == 3 if c in generator.ARCHON_CARDS else 1 <= n <= 2
                            for c,n in counts.items()))

    def test_invalid_daily_teams_are_rejected(self):
        for cards in ([1049,1058,1026,1078,1047], [4106,4107,4107,4102,4101],
                      [4106,4107,1073,4102,4101]):
            with self.assertRaises(ValueError): generator.validate_daily_features(cards,4106)


if __name__ == '__main__': unittest.main()
