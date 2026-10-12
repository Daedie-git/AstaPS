#!/usr/bin/env python3
"""Generate five limited tabs and a matching weapon-only Chronicled Wish each day."""
import argparse
from collections import Counter
import copy
import json
from pathlib import Path
from catalog_roster import character_cards, validate_coverage, event_counts

ROOT = Path(__file__).resolve().parent
ARCHON_CARDS = {1022, 1030, 1052, 1073, 1089, 4106}


def validate_daily_features(characters, archon):
    if len(characters) != 5 or len(set(characters)) != 5:
        raise ValueError('Five daily character tabs must be distinct')
    if characters[0] != archon or set(characters) & ARCHON_CARDS != {archon}:
        raise ValueError('Every daily set must feature exactly the scheduled Archon first')


def build(server_root, baseline):
    history = json.loads((ROOT/'historical-banners.json').read_text())
    order = json.loads((ROOT/'team-banner-order.json').read_text())
    standard = next(b for b in json.loads(baseline.read_text()) if b['bannerType']=='STANDARD')
    resources = server_root/'resources/ExcelBinOutput'
    cards = character_cards(resources)
    items = history['items']
    missing_metadata = cards.keys()-{int(k) for k,v in items.items() if v['kind']=='characters'}
    if missing_metadata:
        raise ValueError(f'Historical dataset missing playable cards: {sorted(missing_metadata)}')
    weapons = {r['id']:r for r in json.loads((resources/'WeaponExcelConfigData.json').read_text())}
    shared = {k:copy.deepcopy(standard[k]) for k in (
        'costItemAmount','costItemAmount10','eventChance4','eventChance5','weights4','weights5',
        'poolBalanceWeights4','poolBalanceWeights5')}
    sources = {}
    signatures = {}
    for source in history['phases']:
        for index, character in enumerate(source['characters']):
            sources.setdefault(character['card'], []).append((source, character))
            signatures[character['card']] = source['weapons'][index]
    signatures.update({int(k):v for k,v in order['weaponOverrides'].items()})
    sequence = order['archonOrder']
    if len(sequence) != 6 or set(sequence) != ARCHON_CARDS or len(order['days']) % 6:
        raise ValueError('Archon order must cover all six and continue across cycle wrap')
    if order['days'][1]['characters'][0] != 4106:
        raise ValueError('Tomorrow must feature Mavuika')
    limited = {card for day in order['days'] for card in day['characters']}
    expected_limited = {k for k,v in cards.items() if v['rarity']==5}-set(standard['fallbackItems5Pool1'])
    if limited != expected_limited:
        raise ValueError(f'Limited coverage mismatch: missing={sorted(expected_limited-limited)}, unexpected={sorted(limited-expected_limited)}')
    phases = []
    for day, entry in enumerate(order['days']):
        featured = entry['characters']
        validate_daily_features(featured, sequence[day % 6])
        banners = [copy.deepcopy(standard)]
        characters = []
        for index, card in enumerate(featured):
            # Preserve a real portrait and the four-stars depicted on that portrait.
            source, character = sources[card][-1]
            characters.append(dict(copy.deepcopy(character), weapon=signatures[card], sourcePhase=source['id']))
            banners.append(dict(shared, comment=character['name'], bannerType='CHARACTER2',gachaType=400,
                scheduleId=7101+index,sortId=9997-index,costItemId=223,
                rateUpItems4=source['fourStars'],rateUpItems5=[card],
                fallbackItems4Pool1=standard['fallbackItems4Pool1'],
                fallbackItems5Pool1=standard['fallbackItems5Pool1'],fallbackItems5Pool2=[],
                prefabPath=character['prefabPath'],titlePath=character['titlePath']))
        pool = [signatures[card] for card in featured]
        if len(set(pool)) != 5:
            raise ValueError('Each daily weapon banner must offer five distinct associated weapons')
        art = history['chronicles'][0]
        four_weapons = sorted({w for c in featured for w in sources[c][-1][0]['fourStarWeapons']})
        banners.append(dict(shared, comment='Weapons: '+' / '.join(items[str(w)]['name'] for w in pool),
            bannerType='CHRONICLE',gachaType=500,scheduleId=7300,sortId=9992,costItemId=223,
            epitomizedPath=True,epitomizedTarget='WEAPON',pityGroup='CHRONICLE_WEAPON',
            epitomizedItems5=pool,autoStripRateUpFromFallback=False,
            rateUpItems5=pool,rateUpItems4=four_weapons,
            fallbackItems5Pool1=[],fallbackItems5Pool2=pool,
            fallbackItems4Pool1=[],fallbackItems4Pool2=four_weapons,
            **{k:art[k] for k in ('prefabPath','previewPrefabPath','titlePath')}))
        for banner in banners[1:]:
            for rarity in (4,5):
                for item in banner[f'rateUpItems{rarity}']:
                    resource = cards[item] if item < 10000 else weapons[item]
                    if resource['rankLevel'] != rarity: raise ValueError(f'Wrong rarity: {item}')
        phases.append(dict(name=' / '.join(c['name'] for c in characters),
            theme=entry['theme'],characters=characters,banners=banners))
    validate_coverage(phases, cards)
    five, four = event_counts(phases)
    if not all(n == len(phases)//6 if card in ARCHON_CARDS else 1 <= n <= 2 for card,n in five.items()):
        raise ValueError('Event five-star exposure exceeds the fairness bounds')
    catalog = dict(schemaVersion=3,timezone='Europe/Amsterdam',anchorDate=order['anchorDate'],phases=phases)
    roster = [dict(name=items[str(card)]['name'],card=card,**value) for card,value in sorted(cards.items())]
    report = dict(days=len(phases),excludedCards=[1005,1007,1062],
        fiveStarCharacters=sum(v['rarity']==5 for v in cards.values()),
        fourStarCharacters=sum(v['rarity']==4 for v in cards.values()),
        eventFiveStarBounds=[min(five.values()),max(five.values())],
        nonArchonEventFiveStarBounds=[min(n for c,n in five.items() if c not in ARCHON_CARDS),
                                     max(n for c,n in five.items() if c not in ARCHON_CARDS)],
        archonBounds=[min(five[c] for c in ARCHON_CARDS),max(five[c] for c in ARCHON_CARDS)],
        archonEveryDay=True,archonCards=sorted(ARCHON_CARDS),
        eventFourStarBounds=[min(four.values()),max(four.values())],
        eventAppearanceUnit='one character tab, including repeated four-star lineups',
        characters=[dict(name=items[str(card)]['name'],card=card,rarity=value['rarity'],
            eventAppearances=(five if value['rarity']==5 else four)[card],
            eventDays=[i+1 for i,p in enumerate(phases) if any(card in b[f'rateUpItems{value["rarity"]}']
                for b in p['banners'] if b['bannerType']=='CHARACTER2')],
            standardAvailable=card in standard[f'fallbackItems{value["rarity"]}Pool1'],chronicledDays=[])
            for card,value in sorted(cards.items())])
    return catalog, roster, report


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-root',type=Path,default=ROOT.parent)
    parser.add_argument('--baseline',type=Path,default=ROOT.parent/'src/main/resources/defaults/data/Banners.json')
    parser.add_argument('--output',type=Path,default=ROOT/'rotation-catalog.json')
    args=parser.parse_args()
    catalog,roster,report=build(args.server_root,args.baseline)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    for path,value in ((args.output,catalog),(args.output.with_name('character-roster.json'),roster),
                       (args.output.with_name('banner-fairness.json'),report)):
        path.write_text(json.dumps(value,indent=2)+'\n')
    args.output.with_name('five-star-roster.json').write_text(json.dumps([r for r in roster if r['rarity']==5],indent=2)+'\n')
    print(f"Validated {report['days']} days, five limiteds and five matching Chronicled weapons; Standard unchanged.")
