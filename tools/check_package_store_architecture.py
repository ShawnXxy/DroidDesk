#!/usr/bin/env python3
import re
import sys
from pathlib import Path


LEGACY_METHODS = (
    "searchNativePackages",
    "getInstalledNativePackages",
    "installNativePackage",
    "removeNativePackage",
    "cancelNativePackageOperation",
)

DIRECT_RUNTIME_CALL = re.compile(
    r"linuxRuntime\.(?:"
    r"searchNativePackages|getInstalledNativePackages|installStorePackage|"
    r"removeStorePackage|getUpgradablePackages|upgradeAllPackages|"
    r"getStorageInfo|cleanPackageCache|cancelPackageOperation"
    r")\b"
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    violations: list[str] = []

    flutter_root = root / "app/lib"
    production_dart = list(flutter_root.rglob("*.dart")) if flutter_root.is_dir() else []
    main_activity = (
        root
        / "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt"
    )
    linux_runtime = (
        root
        / "app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/LinuxRuntime.kt"
    )

    for path in [*production_dart, main_activity]:
        text = read(path)
        for legacy_name in LEGACY_METHODS:
            for match in re.finditer(rf"\b{re.escape(legacy_name)}\b", text):
                violations.append(
                    f"{path.relative_to(root)}:{line_number(text, match.start())}: "
                    f"legacy package-store method {legacy_name}"
                )

    main_text = read(main_activity)
    for match in DIRECT_RUNTIME_CALL.finditer(main_text):
        violations.append(
            f"{main_activity.relative_to(root)}:{line_number(main_text, match.start())}: "
            f"direct LinuxRuntime package-store call {match.group(0)}"
        )

    runtime_text = read(linux_runtime)
    preference_pattern = re.compile(
        r'getSharedPreferences\(\s*"package_store"\s*,'
    )
    for match in preference_pattern.finditer(runtime_text):
        violations.append(
            f"{linux_runtime.relative_to(root)}:{line_number(runtime_text, match.start())}: "
            "unscoped package_store preferences"
        )

    if violations:
        print("Package store architecture violations:")
        for violation in violations:
            print(f"- {violation}")
        return 1

    print("Package store architecture: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
