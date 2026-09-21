package dev.ed4apk;

import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ValueType;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

final class ResourceEditor {
    static AndroidManifestBlock manifest(Path apk) throws IOException {
        return manifest(ApkArchive.read(apk, "AndroidManifest.xml"));
    }

    static AndroidManifestBlock manifest(byte[] bytes) throws IOException {
        AndroidManifestBlock manifest = new AndroidManifestBlock();
        manifest.readBytes(new ByteArrayInputStream(bytes));
        return manifest;
    }

    static int minSdk(Path apk) throws IOException {
        Integer value = manifest(apk).getMinSdkVersion();
        return value == null ? 1 : value;
    }

    static void setManifest(Path apk, Path output, String label, String versionName,
                            Integer versionCode, boolean force) throws Exception {
        AndroidManifestBlock manifest = manifest(apk);
        setManifest(manifest, label, versionName, versionCode);
        manifest.refreshFull();
        ApkArchive.rewrite(apk, output, Map.of("AndroidManifest.xml",
                ApkArchive.Replacement.bytes(manifest.getBytes())), force);
    }

    static void setManifest(AndroidManifestBlock manifest, String label, String versionName,
                            Integer versionCode) {
        if (label == null && versionName == null && versionCode == null)
            throw new IllegalArgumentException("Specify --label, --version-name or --version-code");
        if (label != null) manifest.setApplicationLabel(label);
        if (versionName != null) manifest.setVersionName(versionName);
        if (versionCode != null) {
            if (versionCode < 1) throw new IllegalArgumentException("Version code must be positive");
            manifest.setVersionCode(versionCode);
        }
    }

    static void setString(Path apk, Path output, int resourceId, String config, String value,
                          boolean force) throws Exception {
        TableBlock table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(apk, "resources.arsc")));
        setString(table, resourceId, config, value);
        table.refresh();
        ApkArchive.rewrite(apk, output, Map.of("resources.arsc",
                ApkArchive.Replacement.bytes(table.getBytes())), force);
    }

    static void setString(TableBlock table, int resourceId, String config, String value) throws IOException {
        String qualifiers = config.isEmpty() || config.startsWith("-") ? config : "-" + config;
        List<Entry> matches = new ArrayList<>();
        var entries = table.getEntries(resourceId);
        while (entries.hasNext()) {
            Entry entry = entries.next();
            if (entry.getResConfig().getQualifiers().equals(qualifiers)) matches.add(entry);
        }
        if (matches.size() != 1) throw new IOException("Expected one resource for "
                + String.format("0x%08x", resourceId) + " config='" + config + "'; found " + matches.size());
        Entry entry = matches.get(0);
        if (entry.isComplex() || entry.getValueType() != ValueType.STRING)
            throw new IOException("Resource is not a simple string value");
        entry.setValueAsString(value);
    }
}
