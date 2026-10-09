#!/usr/bin/env python3
"""Build the nine-day catalog while preserving Standard and wish rates."""
import argparse
import copy
import json
from pathlib import Path

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=root/'rotation-catalog.json')
parser.add_argument('--server-root', type=Path, default=root.parent,
                    help='Server directory containing resources/ and data/')
args = parser.parse_args()
resources = args.server_root / 'resources/ExcelBinOutput'
old = json.loads((root.parent/'src/main/resources/defaults/data/Banners.json').read_text())
standard = next(b for b in old if b['bannerType'] == 'STANDARD')
art = {r['itemId']: r for r in json.loads((args.server_root/'data/GachaEpitomizedPrefabMap.json').read_text())}
# Name, avatar card, signature weapon, featured four-star cards.
phases = [
    ('Versatile supports and Vaporize', [
        ('Kazuha',1047,11503,[1043,1031,1032]), ('Xilonen',4103,11516,[1027,1064,1032]),
        ('Yelan',1060,15508,[1025,1031,1043]), ('Arlecchino',1096,13512,[1032,1025,1090])]),
    ('Diluc plunge and classic Mondstadt', [
        ('Diluc',1016,12501,[1032,1025,1043]), ('Xianyun',1093,14515,[1076,1032,1043]),
        ('Keqing',1042,11505,[1031,1077,1043]), ('Jean',1003,11501,[1031,1039,1045])]),
    ('Bloom and Dendro cores', [
        ('Nahida',1073,14511,[1065,1025,1072]), ('Alhaitham',1078,11512,[1065,1031,1077]),
        ('Nilou',1070,11511,[1072,1014,1067]), ('Kokomi',1054,14506,[1025,1072,1067])]),
    ('Electro carries and healing', [
        ('Raiden',1052,13509,[1056,1090,1032]), ('Furina',1089,11513,[1034,1088,1039]),
        ('Clorinde',1098,11515,[1031,1090,1032]), ('Baizhu',1082,14505,[1065,1077,1031])]),
    ('Cryo and Freeze', [
        ('Ayaka',1002,11509,[1039,1045,1025]), ('Shenhe',1063,13507,[1045,1039,1043]),
        ('Wriothesley',1086,14513,[1032,1039,1025]), ('Sigewinne',1095,15513,[1031,1025,1088])]),
    ('Hydro exploration and Burning', [
        ('Neuvillette',1087,14514,[1031,1043,1092]), ('Emilie',1099,13513,[1023,1032,1067]),
        ('Mualani',4102,14516,[1023,1032,1097]), ('Venti',1022,15503,[1031,1043,1039])]),
    ('Geo foundations', [
        ('Navia',1091,12512,[1032,1023,1031]), ('Zhongli',1030,13504,[1027,1064,1032]),
        ('Itto',1057,12510,[1055,1064,1034]), ('Chiori',1094,11514,[1055,1027,1064])]),
    ('Xiao plunge and Liyue carries', [
        ('Xiao',1026,13505,[1076,1032,1039]), ('Xianyun',1093,14515,[1076,1032,1043]),
        ('Hu Tao',1046,13501,[1025,1045,1039]), ('Yae Miko',1058,14509,[1031,1077,1065])]),
    ('Inazuma carries, shields and Freeze', [
        ('Yoimiya',1049,15509,[1032,1064,1025]), ('Citlali',4107,14517,[1032,1025,1043]),
        ('Ayato',1066,11510,[1025,1031,1064]), ('Escoffier',4112,13514,[1039,1045,1025])]),
]
materials = {r['id']: r for r in json.loads((resources/'MaterialExcelConfigData.json').read_text())}
weapons = {r['id']: r for r in json.loads((resources/'WeaponExcelConfigData.json').read_text())}
avatars = {r['id']: r for r in json.loads((resources/'AvatarExcelConfigData.json').read_text())}
shared = {k: copy.deepcopy(standard[k]) for k in ('costItemAmount','costItemAmount10','eventChance4','eventChance5','weights4','weights5','poolBalanceWeights4','poolBalanceWeights5')}
output = []
for theme, entries in phases:
    banners = [copy.deepcopy(standard)]
    for i,(name,card,signature,four) in enumerate(entries):
        avatar_id = 10000000 + (card-1000 if card < 4000 else card-4000)
        assert materials[card]['rankLevel'] == 5 and materials[card]['materialType'] == 'MATERIAL_AVATAR'
        assert weapons[signature]['rankLevel'] == 5
        assert avatars[avatar_id]['weaponType'] == weapons[signature]['weaponType'], name
        b = dict(shared, comment=name, bannerType='CHARACTER2',gachaType=400,
            scheduleId=7101+i,sortId=9997-i,costItemId=223,rateUpItems4=four,rateUpItems5=[card],
            fallbackItems4Pool1=standard['fallbackItems4Pool1'],fallbackItems5Pool1=standard['fallbackItems5Pool1'],fallbackItems5Pool2=[])
        b.update({k:art[card][k] for k in ('prefabPath','titlePath')})
        if art[card]['previewPrefabPath'] != 'UI_Tab_' + art[card]['prefabPath']:
            b['previewPrefabPath'] = art[card]['previewPrefabPath']
        banners.append(b)
    for i in range(2):
        pair=entries[i*2:i*2+2]; signatures=[r[2] for r in pair]
        skin=next((a for a in art.values() if set(a.get('displayItemIds',[]))==set(signatures)),art[signatures[1]])
        b=dict(shared,comment='Signatures: '+', '.join(r[0] for r in pair),bannerType='WEAPON',gachaType=302,
            scheduleId=7200+i,sortId=9990-i,costItemId=223,rateUpItems4=[11401,12401,13407,14401,15401],
            rateUpItems5=signatures,fallbackItems5Pool1=[],fallbackItems5Pool2=standard['fallbackItems5Pool2'])
        b.update({k:skin[k] for k in ('prefabPath','titlePath')})
        if skin['previewPrefabPath'] != 'UI_Tab_' + skin['prefabPath']:
            b['previewPrefabPath'] = skin['previewPrefabPath']
        banners.append(b)
    for b in banners:
        for rarity in (4,5):
            for item in b[f'rateUpItems{rarity}']:
                resource=materials[item] if item<10000 else weapons[item]
                assert resource['rankLevel']==rarity,(theme,item)
                if item<10000: assert resource['materialType']=='MATERIAL_AVATAR'
    output.append(dict(name=theme,characters=[dict(name=r[0],card=r[1],signature=r[2]) for r in entries],banners=banners))
cards = [r[1] for _,entries in phases for r in entries]
assert len(phases) == 9 and len(set(cards)) == 35
assert cards.count(1093) == 2 and all(cards.count(card) == 1 for card in set(cards) - {1093})
args.output.write_text(json.dumps(dict(timezone='Europe/Amsterdam',phases=output),indent=2)+'\n')
print('Validated nine phases, 35 distinct characters (Xianyun twice), weapon types/rarities and seven tabs per phase.')
