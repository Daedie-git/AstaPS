#!/usr/bin/env python3
"""Freeze hash-bound native evidence, not a server-derived protocol specification.

Only independently documented command names become semantic aliases. Server names
are candidates. Completeness refers to canonical normal disk-code paths; loaded
hotfix execution remains unobserved. No baseline field names are copied.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re

FORMAT = 'astaps.protocol-compatibility-reference.v1'
EXPECTED_COUNT = 4896
SCOPE = re.compile(r'Quest|Teleport|TransPoint|Gadget|Platform|Achievement|Reward|Investigation|PlayerLevel|Dungeon|Team')


def canonical_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def digest(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def file_hash(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def parse_opcodes(source):
    """Accept literal Java int initializers only; never evaluate Java expressions."""
    source = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)
    values, unknown = {}, []
    for name, expression in re.findall(r'\b(?:public\s+)?(?:static\s+)?(?:final\s+)?int\s+(\w+)\s*=\s*([^;]+);', source):
        expression = expression.strip()
        require(name not in values and name not in unknown, 'duplicate opcode constant: ' + name)
        if re.fullmatch(r'(?:0[xX][0-9a-fA-F]+|0|[1-9][0-9]*)', expression):
            values[name] = int(expression, 16 if expression.lower().startswith('0x') else 10)
        else:
            unknown.append(name)
    return values, sorted(unknown)


def stable_records(records):
    # Preserve every actual address and source, without arbitrary evidence caps.
    return [json.loads(item) for item in sorted({canonical_bytes(r).decode() for r in records})]


def unique_index(records, key, label):
    result = {}
    for record in records:
        value = record[key]
        require(value not in result, 'duplicate ' + label + ': ' + str(value))
        result[value] = record
    return result


def validate_schema(types, enums, registered):
    roots = {}
    for identity, t in types.items():
        command = t['command_id']
        if command is not None:
            require(command not in roots, 'duplicate canonical command id')
            roots[command] = identity
        fields = unique_index(t['fields'], 'number', 'field number')
        for number, field in fields.items():
            require(type(number) is int and number > 0, 'invalid field number')
            if field['local_field_complete']:
                require(not field['blockers'], 'false field completeness')
        if t['schema_complete']:
            require(t['wire_schema_complete'] and t['local_schema_complete'] and t['nested_closure_complete']
                    and not t['blockers'] and not t.get('unresolved_dependencies'), 'false schema completeness')
        if t['local_schema_complete']:
            require(t['wire_schema_complete'] and all(f['local_field_complete'] for f in fields.values()),
                    'false local schema completeness')
        for dep in t.get('dependencies', []):
            if t['schema_complete']:
                require(dep in types or dep in enums, 'missing complete canonical dependency: ' + dep)
                require((types.get(dep) or enums[dep])['schema_complete'], 'false recursive completeness')
    require(roots == registered, 'canonical registry identities do not match')


def build_reference(registry, schema, evidence, opcode_source, hashes, expected_count=EXPECTED_COUNT):
    """Pure producer API; hashes must be computed from the exact input files."""
    require(schema['registry_sha256'] == hashes['registry_sha256'], 'mixed registry hash')
    snapshot = schema.get('input_snapshots', {}).get('registry', '')
    require(Path(snapshot).name == hashes['registry_sha256'] + '.registry.json', 'mixed registry snapshot')
    require(registry['artifact'] == schema['artifact'], 'mixed artifact identity')
    artifact_hash = registry['artifact']['sha256']
    coverage = registry['coverage']
    for key in ('actual_constructor_entries', 'static_type_slots', 'verified_command_class_pairs'):
        require(coverage.get(key) == expected_count, 'incomplete registry coverage: ' + key)
    require(coverage.get('unresolved_command_ids') == 0, 'unresolved registry ids')
    entries = registry['entries']
    require(len(entries) == expected_count, 'incomplete registry entries')
    by_command = unique_index(entries, 'command_id', 'registry command id')
    unique_index(entries, 'type_identity', 'registry type identity')
    for command, entry in by_command.items():
        require(type(command) is int and command > 0, 'invalid registry command id')
        require(entry.get('network_cached_command_id') == command, 'registry cached id mismatch')
        require(entry.get('status') == 'verified_dictionary_class_slot_leaf_getter_reader', 'unverified registry identity')
    types = unique_index(schema['types'], 'type_identity', 'canonical type identity')
    enums = unique_index(schema['enums'], 'type_identity', 'canonical enum identity')
    require(not (types.keys() & enums.keys()), 'ambiguous canonical identity')
    validate_schema(types, enums, {c: e['type_identity'] for c, e in by_command.items()})
    opcodes, unknown = parse_opcodes(opcode_source)
    aliases, joins, unjoined = {}, {}, []
    for record in evidence['records']:
        require(record['binary_sha256'] == artifact_hash, 'mixed manual artifact hash')
        name, command = record['message'], record.get('opcode')
        direction = record['direction']
        require(direction in ('client_to_server', 'server_to_client', 'unknown'), 'invalid alias direction')
        if name in joins:
            require(joins[name] == (command, direction), 'ambiguous semantic alias: ' + name)
        joins[name] = (command, direction)
        if command is None:
            unjoined.append(name)
            continue  # Metadata offsets alone cannot attach a nested semantic name.
        require(record.get('confidence') == 'verified' and record.get('evidence'), 'unproved semantic alias: ' + name)
        # The exact join is to the captured registry, not the server constants
        # being audited (those may intentionally be zero or incorrect).
        require(command in by_command, 'manual alias outside registry: ' + name)
        status = record.get('client_status')
        require(status in ('verified_client_static', 'observed_client'), 'unknown alias evidence status')
        identity = by_command[command]['type_identity']
        group = aliases.setdefault(identity, {})
        alias = group.setdefault(name, {'name': name, 'status': 'observed' if status == 'observed_client' else 'verified',
                                       'direction': direction, 'evidence': [], 'wire_observations': []})
        if status == 'verified_client_static':
            alias['status'] = 'verified'
        alias['evidence'].extend(record['evidence'])
        if 'wire_schema' in record:
            alias['wire_observations'].append({'client_status': status, 'wire_schema': copy.deepcopy(record['wire_schema']),
                                              'evidence': copy.deepcopy(record['evidence'])})
    candidates = {}
    for name, command in opcodes.items():
        if SCOPE.search(name) and command in by_command:
            candidates.setdefault(by_command[command]['type_identity'], []).append(name)
    selected = set(candidates) | set(aliases)
    pending = list(selected)
    while pending:
        t = types[pending.pop()]
        for dep in t.get('dependencies', []):
            if dep in types and dep not in selected:
                selected.add(dep)
                pending.append(dep)
    messages = []
    for identity in sorted(selected):
        t = types[identity]
        message = {k: copy.deepcopy(t[k]) for k in ('type_identity', 'command_id', 'wire_schema_complete',
                   'schema_complete', 'normal_path_only', 'blockers', 'oneofs')}
        message['dependencies'] = sorted(t.get('dependencies', []))
        message['unresolved_dependencies'] = copy.deepcopy(t.get('unresolved_dependencies', []))
        message['semantic_aliases'] = []
        for name, alias in sorted(aliases.get(identity, {}).items()):
            alias['evidence'] = stable_records(alias['evidence'])
            alias['wire_observations'] = stable_records(alias['wire_observations'])
            message['semantic_aliases'].append(alias)
        message['candidate_aliases'] = sorted(set(candidates.get(identity, [])))
        message['fields'] = []
        for field in sorted(t['fields'], key=lambda f: f['number']):
            compact = {k: copy.deepcopy(field[k]) for k in ('number', 'properties', 'property_statuses',
                       'local_field_complete', 'blockers', 'value_transform_chains')}
            compact['evidence'] = stable_records(field['evidence'])
            message['fields'].append(compact)
        message['oneofs'] = stable_records(message['oneofs'])
        messages.append(message)
    compact_enums = []
    for identity, enum in sorted(enums.items()):
        domain = enum['numeric_domain_evidence']
        require(not enum['schema_complete'] or (not enum['blockers'] and not domain.get('unresolved_field_indices')
                and domain.get('status') == 'verified_all_declared_literal_values'
                and sorted(set(enum['values'])) == sorted(set(domain.get('values', [])))),
                'false enum completeness')
        compact_enums.append({'type_identity': identity, 'values': sorted(set(enum['values'])),
                              'complete': enum['schema_complete'], 'numeric_domain_status': domain['status'],
                              'evidence': copy.deepcopy(domain)})
    result = {'format': FORMAT, 'artifact': copy.deepcopy(registry['artifact']), 'provenance': dict(hashes),
              'registry': {'complete': True, 'expected_count': expected_count, 'command_ids': sorted(by_command)},
              'normal_path_only': schema['normal_path_only'], 'hotfix_execution_verified': False,
              'limitations': ['Canonical completeness covers normal native disk-code paths only; loaded hotfix behavior is unobserved.',
                              'Server opcode names are unverified candidates, never independent semantic evidence.',
                              'Observed wire fixtures prove only their documented fields; they do not replace canonical completeness.'],
              'scope': {'name_pattern': SCOPE.pattern, 'unparseable_opcode_constants': unknown,
                        'unjoined_manual_aliases': sorted(set(unjoined))},
              'messages': messages, 'enums': compact_enums}
    result['content_sha256'] = digest(result)
    return result


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    for flag, default in [('registry', root / 'captures/full-protocol/client-registry.json'),
                          ('schema', root / 'captures/full-protocol/client-schema-canonical.json'),
                          ('evidence', root / 'captures/full-protocol/known-client-evidence.json'),
                          ('opcode-source', root / 'src/main/java/emu/grasscutter/net/packet/PacketOpcodes.java'),
                          ('output', root / 'protocol/7.1/client-compatibility-reference.json')]:
        parser.add_argument('--' + flag, type=Path, default=default)
    args = parser.parse_args()
    hashes = {key + '_sha256': file_hash(getattr(args, key)) for key in ('registry', 'schema', 'evidence', 'opcode_source')}
    inputs = []
    for key in ('registry', 'schema', 'evidence'):
        with getattr(args, key).open() as stream:
            inputs.append(json.load(stream))
    result = build_reference(*inputs, args.opcode_source.read_text(), hashes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(canonical_bytes(result) + b'\n')
    print(json.dumps({'output': str(args.output), 'messages': len(result['messages']), 'enums': len(result['enums']),
                      'content_sha256': result['content_sha256'], 'bytes': args.output.stat().st_size}, sort_keys=True))


if __name__ == '__main__':
    main()
