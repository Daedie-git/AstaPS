"""Derive banner coverage from canonical five-star avatar cards, not a curated count."""
from collections import Counter
import json
from pathlib import Path

EXCLUDED_TRAVELERS = {1005, 1007}
REPEAT_COUNTS = {1093: 2, 4106: 2, 1089: 2}


def five_star_cards(resources):
    resources = Path(resources)
    avatars = {row['id']: row for row in json.loads((resources / 'AvatarExcelConfigData.json').read_text())}
    materials = json.loads((resources / 'MaterialExcelConfigData.json').read_text())
    result = {}
    for card in materials:
        if card.get('materialType') != 'MATERIAL_AVATAR':
            continue
        ids = [int(use['useParam'][0]) for use in card.get('itemUse', [])
               if use.get('useOp') == 'ITEM_USE_GAIN_AVATAR']
        if len(ids) != 1:
            continue
        avatar = avatars.get(ids[0])
        if not avatar or avatar.get('useType') != 'AVATAR_FORMAL' or avatar.get('qualityType') not in (
                'QUALITY_ORANGE', 'QUALITY_ORANGE_SP'):
            continue
        avatar_id = avatar['id']
        canonical = avatar_id - 10000000 + (1000 if avatar_id < 10000100 else 4000)
        if card['id'] != canonical:
            continue  # Exclude alternate acquisition tokens, not characters.
        if card['id'] in EXCLUDED_TRAVELERS:
            continue
        result[card['id']] = {'avatarId': avatar_id, 'rankLevel': card['rankLevel']}
    if not result:
        raise ValueError('No canonical five-star avatar cards found')
    return result


def validate_coverage(phases, expected):
    counts = Counter(character['card'] for phase in phases for character in phase['characters'])
    missing = set(expected) - counts.keys()
    extra = counts.keys() - set(expected)
    if missing or extra:
        raise ValueError(f'Five-star coverage mismatch: missing={sorted(missing)}, unexpected={sorted(extra)}')
    if any(count != REPEAT_COUNTS.get(card, 1) for card, count in counts.items()):
        raise ValueError('Only Xianyun, Mavuika and Furina repeat, twice each')
    return counts
