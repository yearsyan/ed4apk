#!/usr/bin/env python3
"""Opt-in device test. SDK/NDK are used only to build the fixture, never by AQE itself."""
import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.aqe.smoketest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device serial, e.g. IP:PORT")
    parser.add_argument("--android-jar", required=True, type=Path, help="Android API 34+ android.jar for fixture compilation")
    parser.add_argument("--clang", required=True, type=Path, help="NDK aarch64-linux-android21-clang executable")
    parser.add_argument("--jar", type=Path, default=ROOT / "build/libs/aqe.jar")
    parser.add_argument("--output", type=Path, help="New directory for APKs, report, screenshot and logs")
    parser.add_argument("--keep-installed", action="store_true", help="Leave this run's test app installed")
    args = parser.parse_args()
    output = (args.output or ROOT / "build/device-test" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    log = output / "commands.log"
    jar = args.jar.resolve()
    java, javac, keytool = (shutil.which(name) for name in ("java", "javac", "keytool"))
    if not all((java, javac, keytool, shutil.which("adb"))):
        raise RuntimeError("Requires JDK 21 and adb on PATH")
    env = dict(os.environ, AQE_TEST_KS_PASS="aqe-device-test-only")
    # Demonstrate that the shipped JAR does not discover SDK or classpath dependencies.
    for name in ("CLASSPATH", "ANDROID_HOME", "ANDROID_SDK_ROOT"):
        env.pop(name, None)

    def run(command, *, check=True, binary=False, timeout=60):
        command = [str(arg) for arg in command]
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=timeout)
        with log.open("a") as stream:
            stream.write("$ " + " ".join(command) + "\n")
            if not binary:
                stream.write(result.stdout.decode(errors="replace"))
            stream.write(result.stderr.decode(errors="replace") + "\n")
        if check and result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stderr.decode(errors='replace')}\n"
                               + ("" if binary else result.stdout.decode(errors="replace")))
        return result.stdout if binary else result.stdout.decode(errors="replace").strip()

    def adb(*command, **kwargs):
        return run(["adb", "-s", args.serial, *command], **kwargs)

    def aqe(*command):
        result = run([java, "-jar", jar, *command])
        print(result, flush=True)
        return result

    report = {"serial": args.serial, "started": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "package": PACKAGE, "passed": False}
    installed = False
    try:
        if ":" in args.serial:
            run(["adb", "connect", args.serial], timeout=15)
        if adb("get-state") != "device":
            raise RuntimeError("ADB device is not ready")
        if adb("shell", "pm", "path", PACKAGE, check=False).startswith("package:"):
            raise RuntimeError(f"{PACKAGE} is already installed; refusing to replace an existing app")
        report["device"] = {key: adb("shell", "getprop", key) for key in (
            "ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abilist")}
        report["device"]["page_size"] = adb("shell", "getconf", "PAGE_SIZE")
        if "arm64-v8a" not in report["device"]["ro.product.cpu.abilist"]:
            raise RuntimeError("This JNI fixture currently targets arm64-v8a")
        print("Device: " + json.dumps(report["device"]), flush=True)
        print("Building disposable fixture (SDK/NDK used only for this step)...", flush=True)
        for value, name in ((7, "original.so"), (42, "replacement.so")):
            run([args.clang.resolve(), "-shared", "-fPIC", "-O2", "-Wl,-z,max-page-size=16384",
                 f"-DPROBE_VALUE={value}", ROOT / "device-test/probe.c", "-o", output / name])
        generator = output / "generator"
        generator.mkdir()
        run([javac, "-cp", jar, "-d", generator, ROOT / "device-test/FixtureBuilder.java"])
        run([java, "-cp", str(generator) + os.pathsep + str(jar), "dev.aqe.FixtureBuilder",
             ROOT / "device-test", args.android_jar.resolve(), output])
        key = output / "test.p12"
        run([keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
             "-storepass:env", "AQE_TEST_KS_PASS", "-keypass:env", "AQE_TEST_KS_PASS",
             "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
             "-dname", "CN=AQE Disposable Device Test", "-noprompt"])
        signing = ["--ks", key, "--alias", "test", "--ks-pass-env", "AQE_TEST_KS_PASS"]
        base_apk, patched_apk = output / "base.apk", output / "patched.apk"
        aqe("sign", output / "base-unsigned.apk", *signing, "-o", base_apk)
        aqe("apply", base_apk, "--patch", output / "patch.json", *signing, "-o", patched_apk)
        aqe("verify", patched_apk)

        def exercise(stage, apk):
            nonlocal installed
            print(f"Installing and running {stage} APK...", flush=True)
            response = adb("install", "-r", "--no-streaming", apk)
            if "Success" not in response:
                raise RuntimeError("Install did not succeed: " + response)
            installed = True
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "run-as", PACKAGE, "rm", "-f", "files/result.json")
            started = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", "--es", "stage", stage)
            (output / (stage + "-launch.txt")).write_text(started)
            deadline = time.monotonic() + 15
            result = None
            while time.monotonic() < deadline:
                text = adb("shell", "run-as", PACKAGE, "cat", "files/result.json", check=False, timeout=10)
                try:
                    result = json.loads(text)
                    if result.get("stage") == stage:
                        break
                except json.JSONDecodeError:
                    pass
                time.sleep(0.5)
            if result is None or result.get("stage") != stage:
                raise RuntimeError("App did not produce a result for " + stage)
            report[stage] = result
            (output / (stage + "-result.json")).write_text(json.dumps(result, indent=2) + "\n")
            pid = adb("shell", "pidof", PACKAGE)
            if pid:
                (output / (stage + "-logcat.txt")).write_text(adb("logcat", "-d", "--pid=" + pid, "-t", "200"))
            if not result.get("passed") or not result.get("checks") or not all(result["checks"].values()):
                raise RuntimeError("Runtime checks failed for " + stage + ": " + json.dumps(result))
            print(f"{stage}: PASS ({len(result['checks'])} runtime checks)", flush=True)

        exercise("baseline", base_apk)
        exercise("patched", patched_apk)
        # am start waits for launch; give the small result TextView one frame to draw.
        time.sleep(0.5)
        (output / "patched.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        report["passed"] = True
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        try:
            if installed and not args.keep_installed:
                response = adb("uninstall", PACKAGE)
                report["uninstalled"] = "Success" in response
                if not report["uninstalled"]:
                    raise RuntimeError("Test package cleanup failed: " + response)
            elif installed:
                report["uninstalled"] = False
        except Exception as error:
            report["passed"] = False
            report["cleanup_error"] = str(error)
            raise
        finally:
            report["finished"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
            print("Report: " + str(output / "report.json"), flush=True)


if __name__ == "__main__":
    main()
