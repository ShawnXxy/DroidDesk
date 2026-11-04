import argparse
import json
import os
from pathlib import Path
import re
import subprocess


VERSION_PATTERN = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\+([1-9][0-9]*)")
REPOSITORY = "ShawnXxy/DroidDesk"


def parse_version(value):
    match = VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise ValueError("Version must be MAJOR.MINOR.PATCH+BUILD, for example 1.3.2+7.")
    major, minor, patch, build = map(int, match.groups())
    if build > 2_100_000_000:
        raise ValueError("Android build number must not exceed 2100000000.")
    return (major, minor, patch), build


def require_newer(candidate, previous, label):
    version, build = parse_version(candidate)
    previous_version, previous_build = parse_version(previous)
    if version <= previous_version or build <= previous_build:
        raise ValueError(f"Both version name and build number must exceed {label}: {previous}.")


def github_json(endpoint, allow_missing=False, binary_asset=False):
    command = ["gh", "api", endpoint]
    if binary_asset:
        command.extend(["-H", "Accept: application/octet-stream"])
    result = subprocess.run(command, capture_output=True, check=False, timeout=60)
    if result.returncode:
        error = result.stderr.decode("utf-8", errors="replace").strip()
        if allow_missing and "(HTTP 404)" in error:
            return None
        raise RuntimeError(f"GitHub API request failed for {endpoint}: {error}")
    return json.loads(result.stdout)


def prepare(version, pubspec, output):
    parse_version(version)
    repository = os.environ["GITHUB_REPOSITORY"]
    if repository != REPOSITORY or os.environ["GITHUB_REF"] != "refs/heads/xixia":
        raise ValueError("Personal releases must run on ShawnXxy/DroidDesk's xixia branch.")

    match = re.search(r"^version:\s*(\S+)\s*$", pubspec.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        raise ValueError("app/pubspec.yaml does not declare a version.")
    require_newer(version, match.group(1), "the source version")
    name, build = version.split("+")
    tag = f"v{name}"
    prefix = f"repos/{repository}"
    if github_json(f"{prefix}/git/ref/tags/{tag}", allow_missing=True) is not None:
        raise ValueError(f"Tag {tag} already exists. Choose a new version; tags are never overwritten.")
    if github_json(f"{prefix}/releases/tags/{tag}", allow_missing=True) is not None:
        raise ValueError(f"Release {tag} already exists. Choose a new version.")

    latest = github_json(f"{prefix}/releases/latest", allow_missing=True)
    if latest is not None:
        manifests = [asset for asset in latest["assets"] if asset["name"] == "release-manifest.json"]
        if len(manifests) != 1:
            raise ValueError("Latest release needs exactly one release-manifest.json to verify upgrade order.")
        manifest = github_json(f"{prefix}/releases/assets/{manifests[0]['id']}", binary_asset=True)
        previous = f"{manifest['versionName']}+{manifest['versionCode']}"
        if latest["tag_name"] != f"v{manifest['versionName']}":
            raise ValueError("Latest release tag and manifest version do not match.")
        require_newer(version, previous, "the latest published release")

    with output.open("a", encoding="utf-8") as environment:
        environment.write(f"RELEASE_NAME={name}\nRELEASE_CODE={build}\nRELEASE_TAG={tag}\n")
    print(f"Prepared {tag} (Android build {build}) from {os.environ['GITHUB_SHA']}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Validate a new personal release without publishing it.")
    parser.add_argument("--version", required=True)
    arguments = parser.parse_args()
    try:
        prepare(arguments.version, Path("app/pubspec.yaml"), Path(os.environ["GITHUB_ENV"]))
    except (ValueError, RuntimeError) as error:
        parser.exit(1, f"Release preparation failed: {error}\n")
