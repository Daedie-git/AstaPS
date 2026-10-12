"""Canonical playable cards and coverage checks for the real-banner catalog."""
from collections import Counter
import json
from pathlib import Path

EXCLUDED_CARDS = {1005, 1007, 1062}  # Travelers and Aloy have no place in this wish catalog.
STARTER_CARDS = {1006, 1015, 1021}


def character_cards(resources, rarity=None):
    resources = Path(resources)
    avatars = {r['id']: r for r in json.loads((resources/'AvatarExcelConfigData.json').read_text())}
    result = {}
    for card in json.loads((resources/'MaterialExcelConfigData.json').read_text()):
        if card.get('materialType') != 'MATERIAL_AVATAR': continue
        ids = [int(use['useParam'][0]) for use in card.get('itemUse', [])
               if use.get('useOp') == 'ITEM_USE_GAIN_AVATAR']
        if len(ids) != 1: continue
        avatar = avatars.get(ids[0])
        if not avatar or avatar.get('useType') != 'AVATAR_FORMAL': continue
        quality = avatar.get('qualityType')
        rank = 4 if quality == 'QUALITY_PURPLE' else 5 if quality in ('QUALITY_ORANGE', 'QUALITY_ORANGE_SP') else 0
        canonical = ids[0]-10000000+(1000 if ids[0] < 10000100 else 4000)
        if not rank or card['id'] != canonical or canonical in EXCLUDED_CARDS: continue
        if rarity is None or rarity == rank:
            result[canonical] = {'avatarId': ids[0], 'rankLevel': card['rankLevel'], 'rarity': rank}
    if not result: raise ValueError('No canonical playable character cards found')
    return result


def five_star_cards(resources):
    return character_cards(resources, 5)


def validate_coverage(phases, expected):
    counts = Counter(item for phase in phases for banner in phase['banners']
                     for rarity in (4, 5)
                     for item in (banner.get(f'fallbackItems{rarity}Pool1', [])
                                  if banner['bannerType'] == 'STANDARD' else banner[f'rateUpItems{rarity}'])
                     if item < 10000)
    missing, extra = set(expected)-counts.keys(), counts.keys()-set(expected)
    if missing or extra:
        raise ValueError(f'Character coverage mismatch: missing={sorted(missing)}, unexpected={sorted(extra)}')
    return counts


def event_counts(phases):
    five, four = Counter(), Counter()
    for phase in phases:
        banners = [b for b in phase['banners'] if b['bannerType'] == 'CHARACTER2']
        for banner in banners:
            five.update(banner['rateUpItems5'])
            four.update(banner['rateUpItems4'])
    return five, four
