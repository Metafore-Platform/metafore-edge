import tempfile
import textwrap
import unittest
from pathlib import Path

import check_no_deploy_on_development as guard

OWN_OK = """
name: development
on:
  push:
    branches: [development]
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: mvn package -B
"""
DEPLOY_STEP = "      - run: kubectl set image deployment/x x=y\n"


def wf(on_block: str, steps: str = "      - run: echo hi\n") -> str:
    return "name: w\n" + textwrap.dedent(on_block) + "jobs:\n  j:\n    runs-on: ubuntu-latest\n    steps:\n" + steps


def run(**files: str) -> list[str]:
    with tempfile.TemporaryDirectory() as tmp:
        for name, text in files.items():
            (Path(tmp) / name.replace("_yml", ".yml")).write_text(text)
        return guard.check(Path(tmp))


class GuardTest(unittest.TestCase):
    def test_the_real_workflows_pass(self):
        self.assertEqual([], guard.check(Path(__file__).resolve().parent.parent / ".github" / "workflows"))

    def test_main_and_staging_only_deploys_do_not_fire_for_development(self):
        text = wf("on:\n  push:\n    branches: [main, staging]\n  pull_request:\n    branches: [main]\n", DEPLOY_STEP)
        self.assertEqual([], run(development_yml=OWN_OK, ci_yml=text))

    def test_eng_glob_does_not_match_development(self):
        text = wf("on:\n  push:\n    branches:\n      - 'eng/**'\n", DEPLOY_STEP)
        self.assertEqual([], run(development_yml=OWN_OK, eng_yml=text))

    def test_a_deploy_on_every_branch_fails(self):
        for on in ("on:\n  push:\n    branches: ['**']\n", "on:\n  push:\n    branches: ['*']\n",
                   "on: push\n", "on: [push, pull_request]\n", "on:\n  push:\n",
                   "on:\n  push:\n    branches: [development]\n",
                   "on:\n  push:\n    branches-ignore: [main]\n",
                   "on:\n  pull_request:\n    branches: ['dev*']\n",
                   "on:\n  pull_request_target:\n"):
            with self.subTest(on=on):
                problems = run(development_yml=OWN_OK, other_yml=wf(on, DEPLOY_STEP))
                self.assertTrue(problems and "could deploy" in problems[0], problems)

    def test_hand_started_or_scheduled_deploys_fail(self):
        for on in ("on:\n  workflow_dispatch:\n", "on:\n  schedule:\n    - cron: '0 3 * * *'\n",
                   "on:\n  workflow_run:\n    workflows: [x]\n"):
            with self.subTest(on=on):
                self.assertTrue(run(development_yml=OWN_OK, other_yml=wf(on, DEPLOY_STEP)))

    def test_every_deploy_marker_is_caught(self):
        for step in ("      - uses: aws-actions/configure-aws-credentials@v4\n", "      - run: helm upgrade --install a b\n",
                     "      - uses: docker/login-action@v3\n", "      - run: docker push x\n",
                     "      - run: argocd app sync a\n", "      - run: aws ecr get-login-password # amazon-ecr\n"):
            with self.subTest(step=step):
                self.assertTrue(run(development_yml=OWN_OK, other_yml=wf("on: push\n", step)))

    def test_a_workflow_that_fires_but_deploys_nothing_is_fine(self):
        self.assertEqual([], run(development_yml=OWN_OK, compass_yml=wf("on:\n  pull_request:\n    types: [opened]\n")))

    def test_development_yml_must_be_present_and_read_only(self):
        self.assertTrue(any("missing" in p for p in run(other_yml=wf("on:\n  push:\n    branches: [main]\n"))))
        no_perms = OWN_OK.replace("permissions:\n  contents: read\n", "")
        self.assertTrue(any("permissions" in p for p in run(development_yml=no_perms)))
        write = OWN_OK.replace("contents: read", "contents: write")
        self.assertTrue(any("only `contents: read`" in p for p in run(development_yml=write)))
        job_write = OWN_OK.replace("    runs-on: ubuntu-latest\n", "    runs-on: ubuntu-latest\n    permissions:\n      id-token: write\n")
        self.assertTrue(run(development_yml=job_write))

    def test_globs_follow_github_rules(self):
        self.assertTrue(guard._matches(["dev*"], "development"))
        self.assertFalse(guard._matches(["eng/**"], "development"))
        self.assertTrue(guard._matches(["**"], "feature/a/b"))
        self.assertFalse(guard._matches(["*"], "feature/a"))
        self.assertFalse(guard._matches(["**", "!development"], "development"))


if __name__ == "__main__":
    unittest.main()
