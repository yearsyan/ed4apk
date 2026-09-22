ed4apk is a fast, standalone APK editor.

## Usage

Requires Java 11+. Download `ed4apk.jar` from [Releases](https://github.com/yearsyan/ed4apk/releases).
No Android SDK or extra JARs are required.

Browse an APK, inspect native libraries, and read its Manifest without unpacking it:

```sh
java -jar ed4apk.jar info app.apk
java -jar ed4apk.jar file list app.apk --prefix lib/
java -jar ed4apk.jar native check app.apk --json
java -jar ed4apk.jar manifest show app.apk --format xml
java -jar ed4apk.jar manifest summary app.apk
java -jar ed4apk.jar resource list app.apk --type string
java -jar ed4apk.jar dex info app.apk
java -jar ed4apk.jar dex list app.apk --prefix com.example.
```

Native checks report ZIP, ELF LOAD and RELRO alignment separately for 16 KiB pages;
ZIP alignment alone does not prove runtime compatibility. Use `native show APK ENTRY`
for segment details and `native list APK` for a lightweight inventory.

Preview or extract one entry:

```sh
java -jar ed4apk.jar file show app.apk assets/config.json
java -jar ed4apk.jar file extract app.apk lib/arm64-v8a/libfoo.so -o libfoo.so
java -jar ed4apk.jar resource show app.apk @string/app_name --json
```

New inspection commands support `--json` (except `file extract`). `manifest show`
keeps its existing typed JSON by default and uses `--format xml` for decoded XML.
Previews are bounded; extraction streams the full entry and verifies its CRC.

Change the app label and version:

```sh
java -jar ed4apk.jar manifest set app.apk --label "My App" --version-name 2.0 --version-code 2 -o metadata.apk
```

Edit a component attribute. Replace `.MainActivity` with an existing component name;
`--component` also accepts `service`, `receiver`, `provider`, and `activity-alias`.

```sh
java -jar ed4apk.jar manifest update app.apk --component activity --name .MainActivity --attribute android:exported=true -o component.apk
```

Replace an APK file with a local file:

```sh
java -jar ed4apk.jar replace app.apk assets/config.json=./config.json -o files.apk
```

Export an existing class, edit its Smali, and replace it:

```sh
java -jar ed4apk.jar dex export app.apk com.example.Main -o Main.smali
# Edit Main.smali, keeping its class name and complete class body.
java -jar ed4apk.jar dex replace app.apk Main.smali -o dex-edited.apk
```

Apply several edits together. Save this as `patch.json`:

```json
{
  "version": 1,
  "operations": [
    {"op": "manifest.set", "label": "My App", "versionCode": 2},
    {"op": "file.replace", "path": "assets/config.json", "source": "config.json"}
  ]
}
```

Place the local `config.json` beside the patch file; the APK must already contain
`assets/config.json` for `file.replace`.

```sh
java -jar ed4apk.jar apply app.apk --patch patch.json -o edited.apk
```

Editing produces an unsigned APK. Sign it with your own keystore before installation:

```sh
java -jar ed4apk.jar sign edited.apk --ks release.p12 --alias release -o signed.apk
java -jar ed4apk.jar verify signed.apk
```

The signing command prompts for the password in an interactive terminal, or reads
`ed4apk_KS_PASS` from the environment. Output paths must differ from the input;
add `--force` to replace an existing output file. The editing examples above are
independent; use a previous output as the next input to keep its changes.

More help and bundled examples:

```sh
java -jar ed4apk.jar --help-all
java -jar ed4apk.jar examples manifest
java -jar ed4apk.jar examples batch
```

## License

ed4apk is licensed under the [MIT License](LICENSE). The distributed JAR includes
the project license at `META-INF/ed4apk/LICENSE`.

Bundled third-party dependencies retain their respective licenses; see
[THIRD_PARTY.md](THIRD_PARTY.md) and the notices included in the JAR.
