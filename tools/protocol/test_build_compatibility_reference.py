"""Synthetic failure tests require neither captured client files nor a runtime."""
import copy
import json
from pathlib import Path
import unittest

from build_compatibility_reference import build_reference, canonical_bytes, digest, parse_opcodes


class ReferenceTests(unittest.TestCase):
    def setUp(self):
        self.hashes = {k + '_sha256': k * 3 for k in ('registry', 'schema', 'evidence', 'opcode_source')}
        self.registry = {'artifact': {'sha256': 'binary', 'size': 123, 'preferred_base': '0x140000000'},
                         'coverage': {'actual_constructor_entries': 2, 'static_type_slots': 2,
                                      'verified_command_class_pairs': 2, 'unresolved_command_ids': 0},
                         'entries': [{'command_id': c, 'network_cached_command_id': c, 'type_identity': t,
                                      'status': 'verified_dictionary_class_slot_leaf_getter_reader'}
                                     for c, t in [(1, 'root'), (2, 'other')]]}
        field = {'number': 4, 'properties': {'name': 'field_4', 'type': 'message', 'type_identity': 'nested',
                                           'map_key_type': 'uint32', 'future_property': {'unknown': True}},
                 'property_statuses': {'name': 'unknown', 'type': 'verified_client_static', 'future_property': 'unknown'},
                 'evidence': [{'source': 'canonical.json', 'addresses': ['0x1', '0x2']}],
                 'local_field_complete': True, 'blockers': [], 'value_transform_chains': [[]],
                 'proof_records': [{'large_metadata': 'must not leak'}]}
        def message(identity, command, dependencies, fields):
            return {'type_identity': identity, 'command_id': command, 'fields': fields,
                    'dependencies': dependencies, 'unresolved_dependencies': [], 'schema_complete': True,
                    'wire_schema_complete': True, 'local_schema_complete': True, 'nested_closure_complete': True,
                    'normal_path_only': True, 'blockers': [], 'oneofs': []}
        self.schema = {'artifact': copy.deepcopy(self.registry['artifact']),
                       'registry_sha256': self.hashes['registry_sha256'],
                       'input_snapshots': {'registry': 'captures/' + self.hashes['registry_sha256'] + '.registry.json'},
                       'normal_path_only': True,
                       'types': [message('root', 1, ['nested'], [field]), message('other', 2, [], []),
                                 message('nested', None, ['enum'], [])],
                       'enums': [{'type_identity': 'enum', 'schema_complete': True, 'blockers': [], 'values': [0, 2],
                                  'numeric_domain_evidence': {'status': 'verified_all_declared_literal_values',
                                                              'values': [0, 2], 'unresolved_field_indices': []}}]}
        self.evidence = {'records': [{'message': 'IndependentNameReq', 'opcode': 1,
                                     'binary_sha256': 'binary', 'confidence': 'verified',
                                     'direction': 'client_to_server', 'client_status': 'observed_client',
                                     'evidence': [{'source': 'fixture.md', 'fixture_hex': '2001'}],
                                     'wire_schema': {'complete': False, 'fields': [{'number': 4, 'name': 'observed_name'}]}}]}
        self.source = 'public static final int QuestCandidateReq = 1; public static final int OtherRsp = 2;'

    def build(self):
        return build_reference(self.registry, self.schema, self.evidence, self.source, self.hashes, expected_count=2)

    def test_deterministic_replay_and_hash(self):
        first = self.build()
        self.assertEqual(canonical_bytes(first), canonical_bytes(self.build()))
        expected = first.pop('content_sha256')
        self.assertEqual(expected, digest(first))
        self.registry['entries'].reverse()
        self.schema['types'].reverse()
        self.assertEqual(expected, self.build()['content_sha256'])

    def test_nested_closure_and_compaction(self):
        result = self.build()
        self.assertEqual(['nested', 'root'], [m['type_identity'] for m in result['messages']])
        self.assertIsNone(result['messages'][0]['command_id'])
        root = result['messages'][1]
        self.assertEqual(['QuestCandidateReq'], root['candidate_aliases'])
        self.assertEqual('IndependentNameReq', root['semantic_aliases'][0]['name'])
        self.assertNotIn('proof_records', root['fields'][0])
        self.assertEqual(self.schema['types'][0]['fields'][0]['evidence'], root['fields'][0]['evidence'])
        self.assertEqual([0, 2], result['enums'][0]['values'])

    def test_property_unknowns_and_observations_retained(self):
        root = self.build()['messages'][1]
        self.assertTrue(root['schema_complete'])
        self.assertEqual('unknown', root['fields'][0]['property_statuses']['name'])
        self.assertEqual({'unknown': True}, root['fields'][0]['properties']['future_property'])
        self.assertEqual('field_4', root['fields'][0]['properties']['name'])
        alias = root['semantic_aliases'][0]
        self.assertEqual('observed', alias['status'])
        self.assertFalse(alias['wire_observations'][0]['wire_schema']['complete'])

    def test_candidates_never_verified(self):
        self.evidence['records'] = []
        self.source += ' public static final int TeamCandidateRsp = 1;'
        root = self.build()['messages'][1]
        self.assertEqual([], root['semantic_aliases'])
        self.assertEqual(['QuestCandidateReq', 'TeamCandidateRsp'], root['candidate_aliases'])

    def test_zero_server_opcode_does_not_override_independent_join(self):
        self.source += ' public static final int IndependentNameReq = 0;'
        self.assertEqual('observed', self.build()['messages'][1]['semantic_aliases'][0]['status'])

    def test_duplicate_manual_records_collapse(self):
        self.evidence['records'] *= 2
        alias = self.build()['messages'][1]['semantic_aliases'][0]
        self.assertEqual(1, len(alias['evidence']))
        self.assertEqual(1, len(alias['wire_observations']))

    def test_ambiguous_alias_id(self):
        duplicate = copy.deepcopy(self.evidence['records'][0]); duplicate['opcode'] = 2
        self.evidence['records'].append(duplicate)
        with self.assertRaisesRegex(ValueError, 'ambiguous semantic alias'):
            self.build()

    def test_ambiguous_alias_direction(self):
        duplicate = copy.deepcopy(self.evidence['records'][0]); duplicate['direction'] = 'server_to_client'
        self.evidence['records'].append(duplicate)
        with self.assertRaisesRegex(ValueError, 'ambiguous semantic alias'):
            self.build()

    def test_wrong_registry_hash(self):
        self.schema['registry_sha256'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'mixed registry hash'): self.build()

    def test_wrong_snapshot(self):
        self.schema['input_snapshots']['registry'] = 'wrong.registry.json'
        with self.assertRaisesRegex(ValueError, 'mixed registry snapshot'): self.build()

    def test_wrong_artifact(self):
        self.schema['artifact']['size'] += 1
        with self.assertRaisesRegex(ValueError, 'mixed artifact identity'): self.build()

    def test_wrong_manual_artifact(self):
        self.evidence['records'][0]['binary_sha256'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'mixed manual artifact'): self.build()

    def test_duplicate_registry_id(self):
        self.registry['entries'][1]['command_id'] = 1
        with self.assertRaisesRegex(ValueError, 'duplicate registry command id'): self.build()

    def test_false_registry_coverage(self):
        self.registry['coverage']['verified_command_class_pairs'] = 1
        with self.assertRaisesRegex(ValueError, 'incomplete registry coverage'): self.build()

    def test_wrong_registry_identity(self):
        self.registry['entries'][0]['type_identity'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'registry identities'): self.build()

    def test_false_completeness(self):
        self.schema['types'][0]['blockers'] = ['unproved']
        with self.assertRaisesRegex(ValueError, 'false schema completeness'): self.build()

    def test_false_field_completeness(self):
        self.schema['types'][0]['fields'][0]['blockers'] = ['unproved']
        with self.assertRaisesRegex(ValueError, 'false field completeness'): self.build()

    def test_false_recursive_completeness(self):
        nested = self.schema['types'][2]
        nested['schema_complete'] = False
        with self.assertRaisesRegex(ValueError, 'false recursive completeness'): self.build()

    def test_false_enum_completeness(self):
        self.schema['enums'][0]['numeric_domain_evidence']['status'] = 'unknown'
        with self.assertRaisesRegex(ValueError, 'false enum completeness'): self.build()

    def test_false_enum_numeric_domain(self):
        self.schema['enums'][0]['numeric_domain_evidence']['values'] = [0]
        with self.assertRaisesRegex(ValueError, 'false enum completeness'): self.build()

    def test_unproved_alias_is_rejected(self):
        self.evidence['records'][0]['confidence'] = 'candidate'
        with self.assertRaisesRegex(ValueError, 'unproved semantic alias'): self.build()

    def test_duplicate_canonical_field_is_rejected(self):
        self.schema['types'][0]['fields'] *= 2
        with self.assertRaisesRegex(ValueError, 'duplicate field number'): self.build()

    def test_incomplete_missing_dependency_is_retained(self):
        root = self.schema['types'][0]
        root['schema_complete'] = False
        root['dependencies'].append('unresolved')
        root['blockers'].append('referenced nested schema is incomplete')
        result = self.build()['messages'][1]
        self.assertIn('unresolved', result['dependencies'])
        self.assertFalse(result['schema_complete'])

    def test_unparseable_constants_are_scope_unknown(self):
        self.source += ' public static final int QuestUnknown = SOME_VALUE + 1;'
        self.assertEqual(['QuestUnknown'], self.build()['scope']['unparseable_opcode_constants'])

    def test_no_join_from_metadata_offsets(self):
        record = copy.deepcopy(self.evidence['records'][0])
        record.pop('opcode'); record['message'] = 'ItemParam'; record['direction'] = 'unknown'
        self.evidence['records'].append(record)
        result = self.build()
        self.assertEqual(['ItemParam'], result['scope']['unjoined_manual_aliases'])
        self.assertEqual([], result['messages'][0]['semantic_aliases'])

    def test_literal_parser(self):
        source = '''// public static final int Ignored = 9;
        public static final int QuestHex = 0x1a; /* ignored */
        public static final int QuestDecimal = 26; // trailing
        public static final int QuestExpression = 1 << 4;
        public static final int QuestOctal = 012;
        public static final int QuestLong = 1L;'''
        values, unknown = parse_opcodes(source)
        self.assertEqual({'QuestHex': 26, 'QuestDecimal': 26}, values)
        self.assertEqual(['QuestExpression', 'QuestLong', 'QuestOctal'], unknown)

    def test_frozen_reference_is_self_contained_and_hash_bound(self):
        path = Path(__file__).resolve().parents[2] / 'protocol/7.1/client-compatibility-reference.json'
        reference = json.loads(path.read_text())
        expected = reference.pop('content_sha256')
        self.assertEqual(expected, digest(reference))
        self.assertEqual(4896, len(reference['registry']['command_ids']))
        self.assertEqual(sorted(set(reference['registry']['command_ids'])), reference['registry']['command_ids'])
        self.assertFalse(reference['hotfix_execution_verified'])
        self.assertEqual({'registry_sha256', 'schema_sha256', 'evidence_sha256', 'opcode_source_sha256'},
                         set(reference['provenance']))


if __name__ == '__main__':
    unittest.main()
