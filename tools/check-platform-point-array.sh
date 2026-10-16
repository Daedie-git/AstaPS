#!/usr/bin/env bash
# Isolated actual-method regression: installed baseline must be red; checkout must be green.
# No client/server launch, deployment, player, DB, network, or installed archive writes.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
install=${ASTAPS_INSTALL:-"$HOME/.local/share/astaps"}
jar=${ASTAPS_JAR:-"$install/grasscutter.jar"}
jdk=${ASTAPS_JDK:-${JAVA_HOME:-"$install/runtime/jdk"}}
python=${PYTHON:-python3}
if [[ -n "${LOMBOK_JAR:-}" ]]; then
    lombok=$LOMBOK_JAR
else
    cache=${GRADLE_USER_HOME:-"$HOME/.gradle"}/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.30
    [[ -d "$cache" ]] || { printf 'Cached Lombok 1.18.30 missing; set LOMBOK_JAR.\n' >&2; exit 2; }
    lombok=$(find "$cache" -name 'lombok-1.18.30.jar' -print -quit)
fi
for file in "$jar" "$lombok" "$jdk/bin/javac" "$jdk/bin/java"; do
    [[ -f "$file" ]] || { printf 'Missing dependency: %s\n' "$file" >&2; exit 2; }
done
# Resolve jar before running JVMs in a temporary working directory; relative overrides work.
jar=$(realpath "$jar"); lombok=$(realpath "$lombok"); jdk=$(realpath "$jdk")
logs=${PLATFORM_POINT_ARRAY_LOG_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/platform-point-array-results.XXXXXX")}
mkdir -p "$logs"; logs=$(realpath "$logs")
work=$(mktemp -d "${TMPDIR:-/tmp}/platform-point-array-work.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/candidate" "$work/positive" "$work/positive-src/emu/grasscutter/net/packet" \
    "$work/resources/"{BinOutput,ExcelBinOutput,Server,ScriptSceneData}
before=$(sha256sum "$jar" | cut -d' ' -f1)
printf 'installed_jar=%s\ninstalled_sha256=%s\njdk=%s\nlogs=%s\n' "$jar" "$before" "$jdk" "$logs" | tee "$logs/provenance.txt"
sha256sum "$repo/tools/CheckPlatformPointArrayProtocol.java" "$repo/tools/check-platform-point-array.sh" \
    "$repo/src/main/java/emu/grasscutter/scripts/ScriptLib.java" \
    "$repo/src/main/java/emu/grasscutter/server/packet/send/PacketPlatformChangeRouteNotify.java" \
    "$repo/src/main/java/emu/grasscutter/net/packet/PacketOpcodes.java" "$lombok" >> "$logs/provenance.txt"
"$jdk/bin/javac" -cp "$jar" -d "$work" "$repo/tools/CheckPlatformPointArrayProtocol.java" 2> "$logs/compile.txt"
"$jdk/bin/javac" -cp "$jar:$lombok" -processorpath "$lombok" -d "$work/candidate" \
    "$repo/src/main/java/emu/grasscutter/scripts/ScriptLib.java" 2>> "$logs/compile.txt"
# 60000 is a compile-time branch fixture ONLY, not a recovered opcode or deployable candidate.
# Recompile the actual packet source too because its installed constructor embeds opcode zero.
"$python" - "$repo" "$work" <<'PY'
from pathlib import Path
import sys
repo, work = map(Path, sys.argv[1:])
s = (repo / 'src/main/java/emu/grasscutter/net/packet/PacketOpcodes.java').read_text()
needle = 'public static final int PlatformChangeRouteNotify = 0;'
assert s.count(needle) == 1, 'Expected checkout unknown opcode zero; refuse changed fixture provenance'
(work / 'positive-src/emu/grasscutter/net/packet/PacketOpcodes.java').write_text(
    s.replace(needle, 'public static final int PlatformChangeRouteNotify = 60000;', 1))
PY
printf 'positive_fixture=60000 (temporary compile-time constant only; no opcode recovery)\n' >> "$logs/provenance.txt"
"$jdk/bin/javac" -cp "$jar:$lombok" -processorpath "$lombok" -d "$work/positive" \
    "$work/positive-src/emu/grasscutter/net/packet/PacketOpcodes.java" \
    "$repo/src/main/java/emu/grasscutter/scripts/ScriptLib.java" \
    "$repo/src/main/java/emu/grasscutter/server/packet/send/PacketPlatformChangeRouteNotify.java" 2>> "$logs/compile.txt"
set +e
(cd "$work" && "$jdk/bin/java" -cp ".:$jar" CheckPlatformPointArrayProtocol) > "$logs/baseline.txt" 2>&1
baseline=$?
(cd "$work" && "$jdk/bin/java" -cp "candidate:.:$jar" CheckPlatformPointArrayProtocol) > "$logs/candidate.txt" 2>&1
candidate=$?
(cd "$work" && "$jdk/bin/java" -cp "positive:.:$jar" CheckPlatformPointArrayProtocol 60000) > "$logs/positive-fixture.txt" 2>&1
positive=$?
set -e
printf 'baseline_exit=%s candidate_exit=%s positive_fixture_exit=%s\n' "$baseline" "$candidate" "$positive" | tee "$logs/results.txt"
grep -E '^OBSERVED|^RESULT|AssertionError' "$logs/"{baseline,candidate,positive-fixture}.txt | tee -a "$logs/results.txt"
[[ "$before" == "$(sha256sum "$jar" | cut -d' ' -f1)" ]] || { printf 'Installed jar changed during run.\n' >&2; exit 1; }
# Require the precise baseline bug, not arbitrary setup failure or state-assertion failure.
if [[ "$baseline" != 1 || "$candidate" != 0 || "$positive" != 0 ]]; then
    printf 'Expected an unfixed installed baseline and fixed checkout; inspect %s\n' "$logs" >&2
    exit 1
fi
grep -q 'AssertionError: changed point-array notification must survive the nonpositive-opcode discard boundary' "$logs/baseline.txt"
grep -q 'OBSERVED attempted=\[0\] deliverable=\[\]' "$logs/baseline.txt"
grep -q 'OBSERVED attempted=\[6253, 27685\] deliverable=\[6253, 27685\]' "$logs/candidate.txt"
grep -q 'OBSERVED attempted=\[60000\] deliverable=\[60000\]' "$logs/positive-fixture.txt"
[[ $(grep -c '^PASS ' "$logs/candidate.txt") == 17 ]]
printf 'PASS strict baseline red / actual source green (17 checks) / synthetic positive green; installed jar unchanged.\n'
