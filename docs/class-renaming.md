# Static class references and renaming

`dex refs` discovers a definition and its supported static references. `dex rename`
updates one class descriptor and its references across the APK. Both commands accept
Java names or DEX descriptors. `dex rename --dry-run --json` provides a reviewable
report without writing output. The same operation is available as `dex.rename` in
an ordered version 1 patch.

The offline `--help-all` guide is the authoritative user-facing list of XML slots,
limitations and copyable commands. `examples class-rename` supplies a minimal JSON
patch; `examples batch` combines all nine operation types. The root README remains
the repository's one-line description.

## Architecture

- `ClassReferences` traverses live DEX definitions and typed references, including
  nested encoded values, call sites, exception handlers and debug local types.
  It emits locations rather than deciding whether a reference is permitted.
- `XmlClassReferences` interprets known node/attribute/namespace combinations in
  binary Manifest, layout, navigation and preference XML. Ordinary text is not
  globally replaced. Android activity-alias names remain identifiers.
- `ClassRenamer` uses dexlib2 descriptor rewriters, with complete encoded-value and
  call-site argument rewrites, then materializes immutable definitions. Consecutive
  renames therefore operate on the preceding result without lazy aliasing.
- `BatchEditor.Session` owns the overlay, DEX index and parsed XML/resource table.
  Resource table paths cover all configurations and renamed/obfuscated XML paths.
  Whole-file edits invalidate that file's parsed state. Changes are serialized once
  per dirty entry at the end, preserving compressed bytes of other ZIP entries.
- `ClassDeletionGuard` consumes the shared locations, extending existing deletion
  protection to the same XML slots. Its existing per-class explicit opt-out remains
  independent from rename validation.

## Validation and publication

1. Validate descriptors, an existing source, and an absent, unreferenced destination.
   This version requires the same package, avoiding changes to package access.
   Accepted names use the DEX SimpleName syntax without DEX 040-only spaces, so
   they work in older DEX too. See the [DEX format specification](https://source.android.com/docs/core/runtime/dex-format#simplename).
2. Scan every DEX and supported XML entry before mutation. Rebuild only affected
   DEX entries; keep classes in their original DEX.
3. Resolve indirect class-name string resources recursively across configurations.
   Inline the new class name in only the referring XML attribute when all variants
   agree after renaming. Preserve the shared string resource. Reject ambiguity,
   cycles, missing resources and unsupported values in known class slots.
4. Validate the final overlay for references to renamed classes that are absent.
   This catches old references introduced by later class, raw file or resource
   edits. Explicitly adding a new definition under the old name is permitted.
5. Serialize and enforce DEX limits. Refresh XML structural data without namespace
   normalization or removal of unrelated attributes. Optionally sign, then publish
   via the existing atomic output path. Failure preserves input and existing output.
   Dry-run performs the same final validation and serialization in memory.

No Smali round-trip, Android SDK, subprocess compiler or new runtime dependency is
required. The fat JAR still runs on Java 11+.

## Boundaries

Renaming is exact: it does not implicitly rename nested classes. Reflection/JNI,
dynamic code, generic/Kotlin/InnerClass string metadata, arbitrary resource strings,
custom XML conventions and class values inherited through styles/themes are outside
the supported reference model. This is not a full method/field linker or runtime
correctness proof. XML references to theme attributes in known class slots fail
closed; styles absent from those explicit slots are not traversed.

## Tests

Integration tests round-trip multidex APKs and binary resource XML. They cover typed
DEX reference forms, resource configurations/aliases/obfuscated paths, namespace and
ordinary-string preservation, atomic conflicts, ordered rename/add/replace/delete,
final-state guards and unchanged ZIP payloads. Standalone JAR tests exercise query,
preview, rename, batch, help and existing signing flows with Android SDK/classpath
environment variables removed. `verifyJava11Bytecode` checks bundled class versions;
`-PaqeTestJava=/path/to/java11/bin/java` runs CLI subprocesses on Java 11.

Validated with 111 passing tests and a Java 11 standalone runtime. The opt-in
`device-test/run.py` also installs and exercises the baseline, edited and renamed
APKs on Android: it renames the manifest Activity, a callee in another DEX and a
custom View loaded from binary layout XML. All 16 checks passed for the renamed
APK on Android 14 / arm64; the disposable test package was then uninstalled.
The SDK/NDK build this test fixture only and remain unnecessary for running AQE.
See [device results](validation/class-rename/report.json) and [rename patch](validation/class-rename/rename.json).
