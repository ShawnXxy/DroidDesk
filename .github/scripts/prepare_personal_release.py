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


def format_version(version, build):
    return f"{'.'.join(map(str, version))}+{build}"


def choose_auto_version(source, latest, is_available):
    source_version, source_build = parse_version(source)
    latest_version, latest_build = parse_version(latest) if latest is not None else ((0, 0, 0), 0)
    major, minor, patch = max(source_version, latest_version)
    build = max(source_build, latest_build)

    while True:
        patch += 1
        build += 1
        candidate = format_version((major, minor, patch), build)
        parse_version(candidate)
        if is_available(f"v{candidate.split('+', 1)[0]}"):
            return candidate


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


def release_collision(repository, tag):
    prefix = f"repos/{repository}"
    if github_json(f"{prefix}/git/ref/tags/{tag}", allow_missing=True) is not None:
        return "tag"
    if github_json(f"{prefix}/releases/tags/{tag}", allow_missing=True) is not None:
        return "release"
    return None


def release_is_available(repository, tag):
    return release_collision(repository, tag) is None


def require_release_available(repository, tag):
    collision = release_collision(repository, tag)
    if collision == "tag":
        raise ValueError(f"Tag {tag} already exists. Choose a new version; tags are never overwritten.")
    if collision == "release":
        raise ValueError(f"Release {tag} already exists. Choose a new version.")


def validated_release(repository, release, label):
    manifests = [asset for asset in release["assets"] if asset["name"] == "release-manifest.json"]
    if len(manifests) != 1:
        raise ValueError(f"{label} needs exactly one release-manifest.json to verify upgrade order.")
    manifest = github_json(
        f"repos/{repository}/releases/assets/{manifests[0]['id']}",
        binary_asset=True,
    )
    version = f"{manifest['versionName']}+{manifest['versionCode']}"
    if release["tag_name"] != f"v{manifest['versionName']}":
        raise ValueError(f"{label} tag and manifest version do not match.")
    parse_version(version)
    return version, manifest


def latest_release_version(repository):
    latest = github_json(f"repos/{repository}/releases/latest", allow_missing=True)
    if latest is None:
        return None
    version, _ = validated_release(repository, latest, "Latest release")
    return version


def branch_head(repository, branch):
    ref = github_json(f"repos/{repository}/git/ref/heads/{branch}")
    return ref["object"]["sha"]


def published_release_for_commit(repository, commit):
    refs = github_json(f"repos/{repository}/git/matching-refs/tags/v")
    matches = []
    for ref in refs:
        if ref["object"]["type"] != "commit" or ref["object"]["sha"] != commit:
            continue
        tag = ref["ref"].removeprefix("refs/tags/")
        release = github_json(f"repos/{repository}/releases/tags/{tag}", allow_missing=True)
        if release is None or release["draft"] or release["prerelease"]:
            continue
        version, manifest = validated_release(repository, release, f"Published release {tag}")
        if manifest.get("sourceCommit") != commit:
            raise ValueError(f"Published release {tag} manifest source commit does not match its tag.")
        matches.append(
            (
                parse_version(version),
                {
                    "tag": tag,
                    "url": release["html_url"],
                    "version": version,
                },
            )
        )
    return max(matches, default=(None, None), key=lambda match: match[0])[1]


def write_environment(output, values):
    with output.open("a", encoding="utf-8") as environment:
        for name, value in values.items():
            environment.write(f"{name}={value}\n")


def prepare(version, pubspec, output):
    repository = os.environ["GITHUB_REPOSITORY"]
    if repository != REPOSITORY or os.environ["GITHUB_REF"] != "refs/heads/xixia":
        raise ValueError("Personal releases must run on ShawnXxy/DroidDesk's xixia branch.")

    match = re.search(r"^version:\s*(\S+)\s*$", pubspec.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        raise ValueError("app/pubspec.yaml does not declare a version.")
    source = match.group(1)
    parse_version(source)

    if version is None:
        commit = os.environ["GITHUB_SHA"]
        published = published_release_for_commit(repository, commit)
        if published is not None:
            name, build = published["version"].split("+")
            write_environment(
                output,
                {
                    "RELEASE_NAME": name,
                    "RELEASE_CODE": build,
                    "RELEASE_TAG": published["tag"],
                    "RELEASE_BUILD_REQUIRED": "false",
                    "RELEASE_STATUS_MESSAGE": (
                        f"{published['tag']} already publishes commit {commit}; nothing to rebuild."
                    ),
                    "RELEASE_URL": published["url"],
                },
            )
            print(f"{published['tag']} already publishes {commit}; nothing to rebuild.")
            return

        current_head = branch_head(repository, "xixia")
        if current_head != commit:
            message = f"Skipping stale commit {commit}; xixia now points to {current_head}."
            write_environment(
                output,
                {
                    "RELEASE_BUILD_REQUIRED": "false",
                    "RELEASE_STATUS_MESSAGE": message,
                },
            )
            print(message)
            return

    latest = latest_release_version(repository)

    if version is None:
        version = choose_auto_version(
            source,
            latest,
            lambda tag: release_is_available(repository, tag),
        )
    else:
        parse_version(version)
        require_newer(version, source, "the source version")
        if latest is not None:
            require_newer(version, latest, "the latest published release")

    name, build = version.split("+")
    tag = f"v{name}"
    require_release_available(repository, tag)

    write_environment(
        output,
        {
            "RELEASE_NAME": name,
            "RELEASE_CODE": build,
            "RELEASE_TAG": tag,
            "RELEASE_BUILD_REQUIRED": "true",
        },
    )
    print(f"Prepared {tag} (Android build {build}) from {os.environ['GITHUB_SHA']}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Select and validate a personal release version.")
    version_group = parser.add_mutually_exclusive_group(required=True)
    version_group.add_argument("--version")
    version_group.add_argument("--auto", action="store_true")
    arguments = parser.parse_args()
    try:
        prepare(None if arguments.auto else arguments.version, Path("app/pubspec.yaml"), Path(os.environ["GITHUB_ENV"]))
    except (ValueError, RuntimeError) as error:
        parser.exit(1, f"Release preparation failed: {error}\n")
