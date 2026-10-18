import os
import tempfile
import unittest
from pathlib import Path

from check_no_personal_repos import main


def _wf(content: str) -> str:
    """Write content to a temp workflows dir and return its path."""
    d = tempfile.mkdtemp()
    Path(d, "wf.yml").write_text(content)
    return d


class TestCheckNoPersonalRepos(unittest.TestCase):
    def _ok(self, content: str) -> None:
        self.assertEqual(main(_wf(content)), 0)

    def _fail(self, content: str) -> None:
        self.assertEqual(main(_wf(content)), 1)

    def test_clean_workflow_passes(self):
        self._ok("on: push\njobs:\n  build:\n    steps:\n      - uses: actions/checkout@v4\n")

    def test_ilansehayekca_is_rejected(self):
        self._fail("      token: ${{ secrets.GH_PAT }}\n      repository: ilansehayekCA/metafore-platform\n")

    def test_metafore_ilan_ecr_is_rejected(self):
        self._fail("        run: docker push 359469026479.dkr.ecr.us-east-1.amazonaws.com/metafore-ilan/edge:sha\n")

    def test_ghcr_is_rejected(self):
        self._fail("        run: docker push ghcr.io/org/image:sha\n")

    def test_metafore_staging_is_rejected(self):
        self._fail("        run: aws eks update-kubeconfig --name metafore-staging\n")

    def test_metafore_platform_repository_passes(self):
        self._ok("      - uses: actions/checkout@v4\n        with:\n          repository: Metafore-Platform/metafore-contracts\n")

    def test_other_org_repository_is_rejected(self):
        self._fail("      - uses: actions/checkout@v4\n        with:\n          repository: some-other-org/repo\n")

    def test_empty_directory_passes(self):
        d = tempfile.mkdtemp()
        self.assertEqual(main(d), 0)


if __name__ == "__main__":
    unittest.main()
