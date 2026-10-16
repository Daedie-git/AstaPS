# Point-array notification regression

Run from the checkout:

```sh
# The server is now patched; use its preserved pre-fix archive for the red baseline.
ASTAPS_JAR=/home/aim/.local/share/astaps/customization/offline-protocol-fixes-backup-20261013T122618/grasscutter.jar \
  bash tools/check-platform-point-array.sh
```

The runner resolves sources relative to its own path, so it also works from another working directory. It compiles `tools/CheckPlatformPointArrayProtocol.java`; no ignored capture source or precompiled probe is required.

## Dependencies and overrides

The installed jar must retain the unfixed `SetPlatformPointArray` notification method so it can serve as a genuine failing baseline. The checkout must contain the notification fallback. A fixed installed baseline deliberately fails the strict red/green contract rather than being accepted as a false negative control.

- `ASTAPS_INSTALL`: defaults to `$HOME/.local/share/astaps`.
- `ASTAPS_JAR`: defaults to `$ASTAPS_INSTALL/grasscutter.jar`; selects the read-only baseline/dependency archive.
- `ASTAPS_JDK`: defaults to `JAVA_HOME`, then `$ASTAPS_INSTALL/runtime/jdk`. JDK 21 works with the default cached Lombok.
- `LOMBOK_JAR`: overrides cached Lombok 1.18.30; its default cache respects `GRADLE_USER_HOME`.
- `PYTHON`: defaults to `python3`, used only to stage the synthetic constant copy.
- `PLATFORM_POINT_ARRAY_LOG_DIR`: optional log destination; otherwise a fresh `/tmp` results directory is printed and retained. Compiler classes and temporary bootstrap files are removed.

Dependencies are existing local files only. The runner performs no downloads, client/server launch, deployment, database access or network calls.

## Verified result

```text
baseline_exit=1 candidate_exit=0 positive_fixture_exit=0
baseline: attempted=[0] deliverable=[]
candidate: attempted=[6253, 27685] deliverable=[6253, 27685]
positive fixture: attempted=[60000] deliverable=[60000]
```

The baseline must fail on the exact discarded-notification assertion. The compiled actual checkout method must pass **17 checks** covering notification order/payload, same-ID silence, missing/non-gadget entities, route conversion, preserved route/point/timing/rotation state, unchanged input parameters and unchanged return values.

The positive fixture recompiles actual ScriptLib and the actual change-packet source against a temporary constant **60000**. This is solely a compile-time branch test because javac inlines opcode constants; it is not a recovered client command or a production candidate. Logs retain source/tool/dependency hashes and the fixture's provenance. The installed archive is hashed before/after and remains unchanged.

The collector reproduces the independently bytecode-verified nonpositive-opcode discard predicate; it does not execute positive transport sends. Constructor-free scene/manager/gadget fixtures avoid player state, scripts, schedulers and DB bootstrap. Gadget appearance serialization is limited to its ID and actual platform DTO. The checks establish the server-side notification and preservation contracts, not client acceptance or complete point-array movement. Existing point-array parameter interpretation/serialization is unchanged.

Verification checkpoint: installed jar SHA-256 `2f610292a2d118b72148776136262b03668d29e14eca53d1c4f5bf05ada95333`; default runner passes with the exact result above. Existing capture reports and production source are unchanged by promoting this tool.
