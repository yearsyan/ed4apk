package dev.aqe.smoketest;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;

/** Exercises ordinary edits, then static renaming of the activity, a callee and an XML View. */
public class MainActivity extends Activity {
    private final JSONObject checks = new JSONObject();
    private final JSONObject values = new JSONObject();
    private boolean passed = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String stage = getIntent().getStringExtra("stage");
        boolean renamed = "renamed".equals(stage);
        boolean edited = "patched".equals(stage) || renamed;
        JSONObject report = new JSONObject();
        try {
            report.put("stage", stage);
            check("dex_call", PatchTarget.message(), edited ? "helper-called" : "original");
            check("secondary_dex", OtherDex.value(), 17);
            int layout = getResources().getIdentifier("probe_layout", "layout", getPackageName());
            android.view.View inflated = getLayoutInflater().inflate(layout, null);
            check("layout_class", inflated.getClass().getName(), getPackageName() + (renamed ? ".RenamedView" : ".ProbeView"));
            check("activity_class", getClass().getName(), getPackageName() + (renamed ? ".RenamedActivity" : ".MainActivity"));
            check("added_class", hasClass("Added"), edited);
            check("deleted_class", hasClass("Legacy"), !edited);
            check("asset_replace", asset("config.txt"), edited ? "new-config" : "old-config");
            check("asset_add", hasAsset("added.txt"), edited);
            if (edited) check("added_asset_content", asset("added.txt"), "new-asset");
            check("asset_delete", hasAsset("obsolete.txt"), !edited);
            check("unchanged_asset", asset("keep.txt"), "keep");
            int title = getResources().getIdentifier("title", "string", getPackageName());
            check("string_resource", getString(title), edited ? "New resource" : "Original resource");
            int raw = getResources().getIdentifier("probe", "raw", getPackageName());
            check("resource_file", read(getResources().openRawResource(raw)), edited ? "new-raw" : "old-raw");
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            check("manifest_label", getApplicationInfo().loadLabel(getPackageManager()).toString(),
                    edited ? "AQE Edited" : "AQE Original");
            check("manifest_version", info.versionCode, edited ? 2 : 1);
            System.loadLibrary("aqeprobe");
            check("native_library", NativeProbe.value(), edited ? 42 : 7);
        } catch (Throwable error) {
            passed = false;
            try { report.put("error", Log.getStackTraceString(error)); } catch (Exception ignored) {}
        }
        try {
            report.put("passed", passed).put("checks", checks).put("values", values);
            try (OutputStream out = openFileOutput("result.json", MODE_PRIVATE)) {
                out.write(report.toString(2).getBytes("UTF-8"));
            }
            Log.i("AQE_DEVICE_TEST", report.toString());
            TextView text = new TextView(this);
            text.setTextSize(18);
            text.setPadding(24, 24, 24, 24);
            StringBuilder display = new StringBuilder("AQE " + stage + ": " + (passed ? "PASS" : "FAIL") + "\n\n");
            java.util.Iterator<String> names = checks.keys();
            while (names.hasNext()) {
                String name = names.next();
                display.append(checks.getBoolean(name) ? "PASS  " : "FAIL  ").append(name)
                        .append(": ").append(values.get(name)).append('\n');
            }
            if (report.has("error")) display.append(report.getString("error"));
            text.setText(display);
            setContentView(text);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void check(String name, Object actual, Object expected) throws Exception {
        boolean ok = expected.equals(actual);
        checks.put(name, ok);
        values.put(name, actual);
        passed &= ok;
    }

    private boolean hasClass(String name) throws Exception {
        try { Class.forName(getPackageName() + "." + name); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    private boolean hasAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) { return true; }
        catch (FileNotFoundException absent) { return false; }
    }

    private String asset(String name) throws IOException { return read(getAssets().open(name)); }

    private String read(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int size;
            while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
            return out.toString("UTF-8");
        }
    }
}

class PatchTarget { static String message() { return "original"; } }
class Legacy {}
class OtherDex { static int value() { return 17; } }
class NativeProbe { static native int value(); }
