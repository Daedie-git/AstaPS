# Offline protocol compatibility checks

This checker compares declared server packet contracts with a compact, hash-bound client evidence snapshot. It never launches or attaches to the game, starts a server/database, grants rewards, or changes protocol mappings. It is not a proof of client acceptance or gameplay correctness.

## Run

Use JDK 21 and Python with `tools/protocol/compatibility-requirements.txt` installed:

```sh
bash gradlew generateProtocolJava
python -m unittest discover -s tools/protocol -p 'test_*compatibility*.py' -v
python tools/protocol/check_compatibility.py --output build/reports/protocol-compatibility.json
```

For a one-off Python environment, the last command can also run through `uv run --with protobuf==6.33.6 python ...`. Windows can use the normal Gradle wrapper/Python commands. The descriptor is generated from the actual base descriptor, supplemental overrides and existing TPS patches, not from the legacy checked-in Java dump. Its sidecar hashes the descriptor, base and both supplemental source directories; stale inputs fail with instructions to regenerate.

The default scope covers quests, teleports/trans-points, gadgets/platforms, rewards/achievements/handbook, dungeons and teams. `--scope REGEX` narrows or expands the source-name selection. All 4,896 registered command IDs are available for ID checks, but only the documented selected message/nested closure is in the compact schema reference. A broader scope therefore has explicitly unknown schema cases.

## Evidence and verdicts

- **Error:** a declared positive command ID is absent from the complete native inventory, an independently identified command has the wrong ID/direction, or its direction-proved field contract disagrees with the generated server descriptor. These are conditional contract discrepancies, not assertions that an unused packet class is actually sent.
- **Review:** structural differences joined only by a server opcode/name candidate, wire-compatible type/cardinality differences, unavailable opcodes, enum-domain differences, extra server fields and nontrivial value transformations. A matching ID does not verify a semantic name. A `.proto` descriptor cannot establish whether handwritten server code already implements a value transform.
- **Unknown:** incomplete/protected native schemas, unsupported source expressions, missing descriptor bindings, unavailable directional wire proof, and map-component contracts not compared by this version.

The checker uses reader acceptance for server-to-client packets and writer tags for client-to-server packets. It distinguishes packed serialization from the accepted packed/unpacked scalar forms. Synthetic native field names are not compared with server field names. Nested comparisons inherit their parent binding confidence; enum differences do not prove rejection of unknown proto3 enum values. Loaded hotfix behavior is unobserved.

The tool reads simple literal/alias opcode constants and direct packet-constructor/handler annotations, ignoring comments and string literals. It does not execute or fully analyze Java control flow, custom serialization, registration fallbacks or sender guards. Map key/value contracts, oneof behavior and runtime message semantics need further support; the report explicitly lists these limitations. Source bindings previously covered by the baseline cannot silently become unsupported while their source files still exist. Empty packet or descriptor-comparison runs fail rather than pass.

## CI and existing debt

`.github/workflows/protocol-compatibility.yml` runs the tests and descriptor generation, performs the audit, and uploads the complete JSON report. No game files or private capture directory are needed in CI.

Exit codes are 0 for no **new** hard errors, 1 for new hard errors, and 2 for missing/stale/inconsistent inputs or an empty audit. Exit 0 does not mean every schema is compatible: existing debt, review items and unknowns remain in the report.

`protocol/7.1/compatibility-debt.json` records exact finding fingerprints and human tracking reasons, bound to this client artifact and reference hash. It does not whitelist whole message names or error categories. Fixes are reported as resolved debt; a new field, changed mismatch or lost source binding still fails CI. Reference updates require deliberate debt review.

The initial committed-branch audit includes known achievement/team corrections already submitted in PR 141, the unsubmitted category-response contract, a legacy outgoing request wrapper, and five declared IDs absent from this native inventory. It does not imply that the privately patched running server has those same defects. The baseline keeps these outstanding contracts visible without making the first CI adoption permanently red.

To propose a baseline update explicitly:

```sh
python tools/protocol/check_compatibility.py --write-baseline /tmp/proposed-protocol-debt.json
```

This writes a proposal but does not suppress errors in that run. Review every fingerprint and replace placeholder reasons with specific tracking context before adopting it. Do not use rebaselining to make a new regression disappear.

## Rebuild the compact reference

The frozen `protocol/7.1/client-compatibility-reference.json` contains all registered IDs, selected canonical message fields, nested dependencies, numeric enum domains and original evidence addresses/statuses. Its provenance retains the exact registry/schema/manual-evidence/source hashes. It does not contain a client executable, memory dump or network credentials.

```sh
python tools/protocol/build_compatibility_reference.py \
  --registry /path/to/client-registry.json \
  --schema /path/to/client-schema-canonical.json \
  --evidence /path/to/known-client-evidence.json \
  --opcode-source src/main/java/emu/grasscutter/net/packet/PacketOpcodes.java \
  --output protocol/7.1/client-compatibility-reference.json
```

This optional producer needs the saved large evidence inputs and validates their registry identity/hash joins, completeness claims and aliases. Normal audits and CI need only the small frozen reference. Schema completeness remains normal-path-only; no server-derived candidate is promoted to verified client identity. Rebuilding is deterministic and does not need a running client.
