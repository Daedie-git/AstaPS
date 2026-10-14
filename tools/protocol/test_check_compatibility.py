#!/usr/bin/env python3
"""Offline regression tests; no application, client, database, or native execution."""
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

from google.protobuf import descriptor_pb2 as pb
import check_compatibility as c


def native(number=5, kind="uint32", complete=True, **properties):
    values = {"type": kind, "wire_type": c.WIRES[kind], "label": "optional",
              "accepted_wire_types": [c.WIRES[kind]], "serialized_tags": [(number << 3) | c.WIRES[kind]], **properties}
    return {"type_identity": "native_A", "command_id": 100, "schema_complete": complete,
            "wire_schema_complete": complete, "blockers": [], "semantic_aliases": [],
            "fields": [{"number": number, "properties": values,
                        "property_statuses": {k: "verified_client_static" for k in ["number", *values]},
                        "evidence": [{"source": "proof", "addresses": ["0x123"]}],
                        "value_transform_chains": [[]]}]}


def server(number=5, kind="uint32", repeated=False, packed=False):
    return {"name": ".DemoRewardRsp", "fields": {number: {"number": number, "name": "value",
        "type": kind, "type_name": "", "label": "repeated" if repeated else "optional",
        "packed": packed, "wire_type": c.WIRES[kind],
        "serialization_wire_type": 2 if packed else c.WIRES[kind],
        "accepted_wire_types": [0, 2] if repeated and c.WIRES[kind] == 0 else [c.WIRES[kind]]}}}


def reference(row):
    r = {"format": "astaps.protocol-compatibility-reference.v1", "artifact": {"sha256": "a" * 64},
         "registry": {"complete": True, "expected_count": 1, "command_ids": [100]},
         "messages": [row], "enums": []}
    r["content_sha256"] = c.digest(c.canonical_bytes(r))
    return r


def compare(s, n, strong=True, direction="server_to_client", **kwargs):
    return c.compare_message("DemoRewardRsp", s, n, {s["name"]: s}, {}, {n["type_identity"]: n}, {},
                             strong, direction, **kwargs)


class FieldComparisonTest(unittest.TestCase):
    def test_correct_field_is_clean(self):
        self.assertEqual([], compare(server(), native()))

    def test_historical_team_wrong_number_is_red(self):
        errors = compare(server(number=7), native(number=5))
        self.assertIn("missing_client_field", [f["code"] for f in errors if f["severity"] == "error"])

    def test_unverified_opcode_name_cannot_make_hard_schema_errors(self):
        errors = compare(server(number=7), native(number=5), strong=False)
        self.assertTrue(errors)
        self.assertFalse(any(f["severity"] == "error" for f in errors))

    def test_unverified_property_is_not_promoted(self):
        n = native(kind="string")
        n["fields"][0]["property_statuses"] = {"number": "verified_client_static", "type": "candidate"}
        result = compare(server(), n)
        self.assertEqual(["directional_wire_contract_unproved"], [f["code"] for f in result])
        self.assertFalse(any(f["severity"] == "error" for f in result))

    def test_incomplete_schema_does_not_invent_absent_fields(self):
        n = native(number=1, complete=False)
        s = server(number=1)
        s["fields"][2] = server(number=2)["fields"][2]
        result = compare(s, n)
        self.assertEqual(["incomplete_native_schema"], [r["code"] for r in result])

    def test_reader_accepts_packed_scalar(self):
        n = native(label="repeated", accepted_wire_types=[0, 2])
        self.assertEqual([], compare(server(repeated=True, packed=True), n))

    def test_reader_rejects_proved_unsupported_packed_wire(self):
        n = native(label="repeated", accepted_wire_types=[0])
        self.assertIn("wire_type_mismatch", [f["code"] for f in compare(server(repeated=True, packed=True), n)])

    def test_writer_packed_tag_is_accepted_by_unpacked_server_descriptor(self):
        n = native(label="repeated", serialized_tags=[42], accepted_wire_types=[])
        self.assertEqual([], compare(server(repeated=True, packed=False), n, direction="client_to_server"))

    def test_scalar_signedness_and_cardinality_are_checked(self):
        result = compare(server(kind="int32", repeated=True), native())
        codes = {f["code"] for f in result}
        self.assertEqual({"scalar_or_container_type_mismatch", "cardinality_mismatch"}, codes)
        self.assertTrue(all(f["severity"] == "review" for f in result))

    def test_nontrivial_transform_requires_review_not_invented_absence(self):
        n = native()
        n["fields"][0]["value_transform_chains"] = [[{"operation": "xor", "value": 17}]]
        result = compare(server(), n)
        self.assertEqual("review", result[0]["severity"])
        self.assertEqual("value_transform_requires_code_review", result[0]["code"])

    def test_nested_proof_inherits_parent_binding_confidence(self):
        child = native(number=2)
        child.update(type_identity="native_B", command_id=None)
        n = native(kind="message", type_name="native_B")
        s = server(kind="message")
        s["fields"][5]["type_name"] = ".Child"
        other = server(number=1)
        other["name"] = ".Child"
        result = c.compare_message("DemoRewardRsp", s, n, {".Child": other}, {},
                                   {"native_A": n, "native_B": child}, {}, False, "server_to_client")
        self.assertIn("missing_client_field", [f["code"] for f in result])
        self.assertFalse(any(f["severity"] == "error" for f in result))

    def test_recursive_message_stops_without_dropping_parent_fields(self):
        n = native(kind="message", type_name="native_A")
        s = server(kind="message")
        s["fields"][5]["type_name"] = s["name"]
        self.assertEqual([], compare(s, n))

    def test_enum_numeric_difference_is_review_not_delivery_claim(self):
        n = native(kind="enum", type_name="enum_A")
        s = server(kind="enum")
        s["fields"][5]["type_name"] = ".Enum"
        result = c.compare_message("DemoRewardRsp", s, n, {}, {".Enum": [0]}, {},
                                   {"enum_A": {"complete": True, "values": [0, 1]}}, True, "server_to_client")
        self.assertEqual("enum_values_missing", result[0]["code"])
        self.assertEqual("review", result[0]["severity"])


    def test_writer_only_fact_cannot_prove_reader_acceptance(self):
        n = native(label="repeated", accepted_wire_types=[], serialized_tags=[42])
        result = compare(server(repeated=True, packed=True), n)
        self.assertEqual(["directional_wire_contract_unproved"], [f["code"] for f in result])

    def test_nominal_wire_type_cannot_create_a_directional_error(self):
        n = native(kind="string", accepted_wire_types=[], serialized_tags=[])
        result = compare(server(), n)
        self.assertFalse(any(f["severity"] == "error" for f in result))
        self.assertNotIn("wire_type_mismatch", [f["code"] for f in result])

    def test_map_component_gap_is_visible(self):
        result = compare(server(kind="map", repeated=True), native(kind="map", label="repeated"))
        self.assertEqual(["map_component_comparison_unavailable"], [f["code"] for f in result])

    def test_server_only_enum_value_is_not_silently_accepted(self):
        n = native(kind="enum", type_name="enum_A")
        s = server(kind="enum")
        s["fields"][5]["type_name"] = ".Enum"
        result = c.compare_message("DemoRewardRsp", s, n, {}, {".Enum": [0, 9]}, {},
                                   {"enum_A": {"complete": True, "values": [0]}}, True, "server_to_client")
        self.assertEqual("server_enum_values_not_in_client_domain", result[0]["code"])
        self.assertEqual("review", result[0]["severity"])


class RegistryAndDebtTest(unittest.TestCase):
    def usage(self, opcode=100):
        return [{"symbol": "DemoRewardRsp", "opcode": opcode, "direction": "server_to_client", "path": "send.java", "line": 1}]

    def test_unregistered_positive_command_is_error_without_name_guess(self):
        result, _ = c.audit(reference(native()), {}, self.usage(200), {}, {})
        self.assertIn("opcode_not_registered", [f["code"] for f in result if f["severity"] == "error"])

    def test_unknown_zero_is_review_not_unconditional_send_failure(self):
        result, _ = c.audit(reference(native()), {}, self.usage(0), {}, {})
        self.assertEqual(["opcode_unavailable"], [f["code"] for f in result])

    def test_verified_zero_mapping_is_error_and_still_checks_schema(self):
        n = native()
        n["semantic_aliases"] = [{"name": "DemoRewardRsp", "status": "verified", "direction": "server_to_client", "evidence": ["proof"]}]
        result, _ = c.audit(reference(n), {}, self.usage(0), {".DemoRewardRsp": server(number=7)}, {})
        self.assertTrue({"semantic_opcode_mismatch", "missing_client_field"} <= {f["code"] for f in result if f["severity"] == "error"})

    def test_exact_debt_does_not_whitelist_other_fields(self):
        old = c.finding("missing_client_field", "error", "DemoRewardRsp", expected=5, field=5)
        new = c.finding("missing_client_field", "error", "DemoRewardRsp", expected=6, field=6)
        baseline = {"format": "astaps.protocol-compatibility-debt.v1", "entries": [{"fingerprint": old["fingerprint"], "reason": "tracked issue"}]}
        self.assertEqual([], c.classify_debt([old, new], baseline))
        self.assertTrue(old["known_debt"])
        self.assertFalse(new["known_debt"])
        self.assertEqual([old["fingerprint"]], c.classify_debt([], baseline))

    def test_fingerprint_ignores_line_changes(self):
        a = c.finding("opcode_not_registered", "error", "DemoRewardRsp", actual=20, line=1)
        b = c.finding("opcode_not_registered", "error", "DemoRewardRsp", actual=20, line=99)
        self.assertEqual(a["fingerprint"], b["fingerprint"])

    def test_reference_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "reference.json"
            r = reference(native())
            p.write_text(json.dumps(r))
            self.assertEqual(1, len(c.load_reference(p)["messages"]))
            r["registry"]["command_ids"] = [100, 100]
            p.write_text(json.dumps(r))
            with self.assertRaisesRegex(ValueError, "content hash"):
                c.load_reference(p)
            r["content_sha256"] = c.digest(c.canonical_bytes({k: v for k, v in r.items() if k != "content_sha256"}))
            p.write_text(json.dumps(r))
            with self.assertRaisesRegex(ValueError, "unique command inventory"):
                c.load_reference(p)


    def test_contradictory_alias_directions_are_rejected(self):
        n = native()
        n["semantic_aliases"] = [{"name": "DemoRewardRsp", "status": "verified", "direction": direction, "evidence": ["proof"]}
                                 for direction in ["server_to_client", "client_to_server"]]
        with self.assertRaisesRegex(ValueError, "Conflicting verified alias"):
            c.audit(reference(n), {}, self.usage(), {}, {})

    def test_writer_tag_number_contradiction_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            n = native()
            n["fields"][0]["properties"]["serialized_tags"] = [48]
            path = Path(tmp) / "reference.json"
            path.write_text(json.dumps(reference(n)))
            with self.assertRaisesRegex(ValueError, "Writer tag contradicts"):
                c.load_reference(path)

    def test_existing_but_unresolved_binding_is_a_ci_coverage_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "send.java").write_text("unsupported expression")
            baseline = {"monitored_bindings": [{"path": "send.java", "symbol": "DemoRewardRsp", "direction": "server_to_client"}]}
            self.assertEqual("source_binding_no_longer_resolved", c.lost_bindings(root, re.compile(c.SCOPE), [], baseline)[0]["code"])
            self.assertEqual([], c.lost_bindings(root, re.compile(c.SCOPE), self.usage(), baseline))
            (root / "send.java").unlink()
            self.assertEqual([], c.lost_bindings(root, re.compile(c.SCOPE), [], baseline))


class SourceAndDescriptorTest(unittest.TestCase):
    def test_source_scanner_ignores_comments_strings_and_arithmetic(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / "src/main/java/emu/grasscutter/server/packet/send/PacketDemoRewardRsp.java"
            path.parent.mkdir(parents=True)
            path.write_text('class PacketDemoRewardRsp { /* super(PacketOpcodes.Fake); */ String s="super(PacketOpcodes.Fake)"; PacketDemoRewardRsp(){super(PacketOpcodes.DemoRewardRsp + 1);} }')
            usage, unknown = c.scan_packets(root, {"DemoRewardRsp": 100}, re.compile(c.SCOPE))
            self.assertEqual([], usage)
            self.assertEqual(1, len(unknown))
            path.write_text('class PacketDemoRewardRsp { PacketDemoRewardRsp(){super(PacketOpcodes.DemoRewardRsp);} }')
            usage, unknown = c.scan_packets(root, {"DemoRewardRsp": 100}, re.compile(c.SCOPE))
            self.assertEqual(100, usage[0]["opcode"])
            self.assertEqual([], unknown)

    def test_opcode_parser_resolves_only_literals_and_aliases(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "opcodes.java"
            path.write_text('public static final int A = 0x64; public static final int B = A; public static final int C = -1; public static final int D = A + 1;')
            self.assertEqual(({"A": 100, "B": 100, "C": -1}, ["D"]), c.read_opcodes(path))

    def test_java_hex_int_signedness_is_not_python_unsigned_int(self):
        self.assertEqual(-1, c.java_int_literal("0xffffffff"))
        self.assertEqual(-2147483648, c.java_int_literal("0x80000000"))
        self.assertEqual(100, c.java_int_literal("0x64"))
        self.assertEqual(-2147483648, c.java_int_literal("-2147483648"))

    def test_unsupported_octal_or_overflow_is_not_guessed(self):
        for text in ["0123", "2147483648", "0x100000000", "1_000", "100L"]:
            self.assertIsNone(c.java_int_literal(text))

    def test_descriptor_hashes_bind_compat_sources_and_import_stubs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proto = root / "protocol/7.1"
            (proto / "compat").mkdir(parents=True)
            (proto / "compat-imports").mkdir()
            (proto / "protocol.desc").write_bytes(b"base")
            desc = pb.FileDescriptorSet()
            file = desc.file.add(name="Demo.proto", syntax="proto3")
            message = file.message_type.add(name="DemoRewardRsp")
            message.field.add(name="ids", number=1, type=pb.FieldDescriptorProto.TYPE_UINT32, label=pb.FieldDescriptorProto.LABEL_REPEATED)
            path = root / "server.desc"
            path.write_bytes(desc.SerializeToString())
            metadata = {"format": "astaps.server-protocol-descriptor.v1", "descriptor_sha256": c.digest(path.read_bytes()), "base_sha256": c.digest(b"base"), "proto_sources_sha256": c.proto_source_fingerprint(root)}
            Path(str(path) + ".json").write_text(json.dumps(metadata))
            messages, _, _ = c.load_server_descriptor(root, path)
            self.assertEqual(2, messages[".DemoRewardRsp"]["fields"][1]["serialization_wire_type"])
            (proto / "compat-imports/Stub.proto").write_text('syntax="proto3";')
            with self.assertRaisesRegex(ValueError, "stale"):
                c.load_server_descriptor(root, path)


    def test_cli_rejects_usages_with_zero_descriptor_comparisons(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proto = root / "protocol/7.1"
            (proto / "compat").mkdir(parents=True)
            (proto / "compat-imports").mkdir()
            (proto / "protocol.desc").write_bytes(b"base")
            (proto / "client-compatibility-reference.json").write_text(json.dumps(reference(native())))
            desc = pb.FileDescriptorSet()
            desc.file.add(name="Unrelated.proto", syntax="proto3").message_type.add(name="Unrelated")
            path = root / "build/generated/protocol/7.1/server.desc"
            path.parent.mkdir(parents=True)
            path.write_bytes(desc.SerializeToString())
            metadata = {"format": "astaps.server-protocol-descriptor.v1", "descriptor_sha256": c.digest(path.read_bytes()), "base_sha256": c.digest(b"base"), "proto_sources_sha256": c.proto_source_fingerprint(root)}
            Path(str(path) + ".json").write_text(json.dumps(metadata))
            code = root / "src/main/java/emu/grasscutter"
            (code / "net/packet").mkdir(parents=True)
            (code / "net/packet/PacketOpcodes.java").write_text("public static final int DemoRewardRsp = 100;")
            (code / "server/packet/send").mkdir(parents=True)
            (code / "server/packet/send/PacketDemoRewardRsp.java").write_text("super(PacketOpcodes.DemoRewardRsp);")
            result = subprocess.run([sys.executable, str(Path(c.__file__)), "--repo", str(root)], text=True, capture_output=True)
            self.assertEqual(2, result.returncode)
            self.assertIn("No descriptor comparisons", result.stderr)


if __name__ == "__main__":
    unittest.main()
