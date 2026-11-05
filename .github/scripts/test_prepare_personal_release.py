from pathlib import Path
import tempfile
import unittest
from unittest import mock

import prepare_personal_release as release


class ChooseAutoVersionTests(unittest.TestCase):
    def test_increments_source_when_no_release_exists(self):
        self.assertEqual(
            release.choose_auto_version("1.3.1+6", None, lambda tag: True),
            "1.3.2+7",
        )

    def test_uses_highest_version_name_and_build_number(self):
        self.assertEqual(
            release.choose_auto_version("2.0.0+6", "1.9.9+10", lambda tag: True),
            "2.0.1+11",
        )

    def test_skips_existing_tags_and_releases(self):
        checked = []

        def is_available(tag):
            checked.append(tag)
            return tag == "v1.3.4"

        self.assertEqual(
            release.choose_auto_version("1.3.1+6", "1.3.2+7", is_available),
            "1.3.4+9",
        )
        self.assertEqual(checked, ["v1.3.3", "v1.3.4"])


class PublishedReleaseTests(unittest.TestCase):
    @mock.patch.object(release, "github_json")
    def test_finds_published_release_for_commit(self, github_json):
        responses = {
            "repos/ShawnXxy/DroidDesk/git/matching-refs/tags/v": [
                {
                    "ref": "refs/tags/v1.3.2",
                    "object": {"type": "commit", "sha": "abc123"},
                }
            ],
            "repos/ShawnXxy/DroidDesk/releases/tags/v1.3.2": {
                "assets": [{"id": 42, "name": "release-manifest.json"}],
                "draft": False,
                "html_url": "https://github.com/ShawnXxy/DroidDesk/releases/tag/v1.3.2",
                "prerelease": False,
                "tag_name": "v1.3.2",
            },
            "repos/ShawnXxy/DroidDesk/releases/assets/42": {
                "sourceCommit": "abc123",
                "versionCode": 7,
                "versionName": "1.3.2",
            },
        }
        github_json.side_effect = lambda endpoint, **kwargs: responses[endpoint]

        self.assertEqual(
            release.published_release_for_commit("ShawnXxy/DroidDesk", "abc123"),
            {
                "tag": "v1.3.2",
                "url": "https://github.com/ShawnXxy/DroidDesk/releases/tag/v1.3.2",
                "version": "1.3.2+7",
            },
        )

    @mock.patch.object(release, "github_json")
    def test_ignores_draft_release_for_commit(self, github_json):
        responses = {
            "repos/ShawnXxy/DroidDesk/git/matching-refs/tags/v": [
                {
                    "ref": "refs/tags/v1.3.2",
                    "object": {"type": "commit", "sha": "abc123"},
                }
            ],
            "repos/ShawnXxy/DroidDesk/releases/tags/v1.3.2": {
                "draft": True,
                "prerelease": False,
            },
        }
        github_json.side_effect = lambda endpoint, **kwargs: responses[endpoint]

        self.assertIsNone(release.published_release_for_commit("ShawnXxy/DroidDesk", "abc123"))


class PrepareTests(unittest.TestCase):
    @mock.patch.object(release, "published_release_for_commit", return_value=None)
    @mock.patch.object(release, "branch_head", return_value="newer-commit")
    def test_skips_stale_automatic_commit(self, branch_head, published_release):
        with tempfile.TemporaryDirectory() as directory:
            pubspec = Path(directory) / "pubspec.yaml"
            output = Path(directory) / "github-env"
            pubspec.write_text("version: 1.3.1+6\n", encoding="utf-8")

            with mock.patch.dict(
                release.os.environ,
                {
                    "GITHUB_REPOSITORY": "ShawnXxy/DroidDesk",
                    "GITHUB_REF": "refs/heads/xixia",
                    "GITHUB_SHA": "stale-commit",
                },
                clear=False,
            ):
                release.prepare(None, pubspec, output)

            self.assertEqual(
                output.read_text(encoding="utf-8").splitlines(),
                [
                    "RELEASE_BUILD_REQUIRED=false",
                    "RELEASE_STATUS_MESSAGE=Skipping stale commit stale-commit; "
                    "xixia now points to newer-commit.",
                ],
            )
            branch_head.assert_called_once_with("ShawnXxy/DroidDesk", "xixia")
            published_release.assert_called_once_with("ShawnXxy/DroidDesk", "stale-commit")


if __name__ == "__main__":
    unittest.main()
