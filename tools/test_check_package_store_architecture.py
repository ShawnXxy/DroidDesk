import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_package_store_architecture.py")


class PackageStoreArchitectureGuardTest(unittest.TestCase):
    def run_guard(self, files: dict[str, str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT), str(root)],
                text=True,
                capture_output=True,
                check=False,
            )

    def test_compliant_environment_store_passes(self) -> None:
        result = self.run_guard(
            {
                "app/lib/services/platform_bridge.dart": (
                    "queryPackageStore(); runPackageStoreOperation(); "
                    "cancelPackageStoreOperation();"
                ),
                "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/"
                "MainActivity.kt": 'when (method) { "queryPackageStore" -> Unit }',
                "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/"
                "LinuxRuntime.kt": "class LinuxRuntime",
            }
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_legacy_flutter_or_method_channel_names_fail(self) -> None:
        for legacy_name in (
            "searchNativePackages",
            "getInstalledNativePackages",
            "installNativePackage",
            "removeNativePackage",
            "cancelNativePackageOperation",
        ):
            with self.subTest(legacy_name=legacy_name):
                result = self.run_guard(
                    {
                        "app/lib/services/platform_bridge.dart": legacy_name,
                        "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/"
                        "MainActivity.kt": "",
                        "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/"
                        "LinuxRuntime.kt": "",
                    }
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn(legacy_name, result.stdout + result.stderr)

    def test_main_activity_direct_linux_runtime_store_call_fails(self) -> None:
        for direct_call in (
            "linuxRuntime.getUpgradablePackages(true)",
            "linuxRuntime.installStorePackages(packages, onProgress)",
            "linuxRuntime.removeStorePackages(packages, onProgress)",
            "linuxRuntime.upgradeStorePackages(packages, onProgress)",
            "linuxRuntime.cleanStorePackageCache(onOutput)",
            "linuxRuntime\n    .cleanStorePackageCache(onOutput)",
        ):
            with self.subTest(direct_call=direct_call):
                result = self.run_guard(
                    {
                        "app/lib/services/platform_bridge.dart": "",
                        "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/"
                        "MainActivity.kt": direct_call,
                        "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/"
                        "LinuxRuntime.kt": "",
                    }
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn(
                    "direct LinuxRuntime package-store call",
                    result.stdout + result.stderr,
                )

    def test_unscoped_legacy_package_store_preferences_fail(self) -> None:
        result = self.run_guard(
            {
                "app/lib/services/platform_bridge.dart": "",
                "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/"
                "MainActivity.kt": "",
                "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/"
                "LinuxRuntime.kt": 'getSharedPreferences("package_store", MODE_PRIVATE)',
            }
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unscoped package_store preferences", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
