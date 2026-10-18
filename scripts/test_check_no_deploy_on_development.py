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

#: development.yml with the one job that may publish (Compass a3990b6e): after every other job, on a push to development only,
#: in the development environment, with the OIDC token and nothing else, to the one ECR repository `metafore-dev/edge`.
OWN_PUBLISH = """
name: development
on:
  push:
    branches: [development]
  pull_request:
    branches: [development]
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: mvn package -B
  publish:
    needs: [test]
    if: github.event_name == 'push' && github.ref == 'refs/heads/development'
    runs-on: ubuntu-latest
    environment: development
    permissions:
      contents: read
      id-token: write
    env:
      REGISTRY: 359469026479.dkr.ecr.us-east-1.amazonaws.com/metafore-dev
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
      - uses: aws-actions/amazon-ecr-login@v2
      - run: |
          aws ecr describe-images --repository-name metafore-dev/edge --image-ids imageTag="$SHA"
          docker push "$REGISTRY/edge:$SHA"
"""


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

    # ── the one job that may publish (Compass a3990b6e) ─────────────────────────────────────────────────────────────────────

    def test_the_named_publish_job_may_hold_credentials_and_push(self):
        self.assertEqual([], run(development_yml=OWN_PUBLISH))

    def test_a_publish_job_is_still_not_allowed_to_reach_a_cluster_or_another_registry(self):
        for bad in ("      - run: kubectl rollout restart deployment/x\n", "      - run: helm upgrade a b\n",
                    "      - run: argocd app sync a\n", "      - uses: docker/login-action@v3\n",
                    "      - run: docker push ghcr.io/x/y:z\n", "      - run: docker push \"$REGISTRY/api:$SHA\"\n",
                    "      - run: aws ecr describe-images --repository-name metafore-dev/api\n",
                    "      - run: docker push 359469026479.dkr.ecr.us-east-1.amazonaws.com/metafore-ilan/metafore-edge:latest\n",
                    "      - run: aws eks update-kubeconfig --name metafore-staging\n"):
            with self.subTest(step=bad):
                self.assertTrue(run(development_yml=OWN_PUBLISH + bad), bad)

    def test_the_publish_job_runs_on_a_push_to_development_and_nothing_else(self):
        for cond in ("github.event_name == 'push'", "github.ref == 'refs/heads/development'", "true"):
            with self.subTest(cond=cond):
                text = OWN_PUBLISH.replace("github.event_name == 'push' && github.ref == 'refs/heads/development'", cond)
                self.assertTrue(any("publish" in p and "push to development" in p for p in run(development_yml=text)))
        no_if = OWN_PUBLISH.replace("    if: github.event_name == 'push' && github.ref == 'refs/heads/development'\n", "")
        self.assertTrue(any("push to development" in p for p in run(development_yml=no_if)))

    def test_the_publish_job_waits_for_every_other_job(self):
        two = OWN_PUBLISH.replace("  publish:", "  lint:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo\n  publish:")
        self.assertTrue(any("needs" in p for p in run(development_yml=two)))
        self.assertEqual([], run(development_yml=two.replace("needs: [test]", "needs: [test, lint]")))
        self.assertTrue(any("needs" in p for p in run(development_yml=OWN_PUBLISH.replace("    needs: [test]\n", ""))))

    def test_the_publish_job_runs_in_the_development_environment(self):
        for env in ("      environment: production\n", ""):
            text = OWN_PUBLISH.replace("    environment: development\n", env and "    environment: production\n")
            self.assertTrue(any("environment" in p for p in run(development_yml=text)), env)

    def test_the_publish_job_asks_for_the_token_and_read_access_only(self):
        for extra in ("      packages: write\n", "      contents: write\n", "      pull-requests: write\n"):
            text = OWN_PUBLISH.replace("      id-token: write\n", "      id-token: write\n" + extra)
            self.assertTrue(any("permissions" in p for p in run(development_yml=text)), extra)
        self.assertTrue(run(development_yml=OWN_PUBLISH.replace("      contents: read\n      id-token: write\n", "")))

    def test_no_other_job_of_development_yml_may_hold_credentials(self):
        other = OWN_PUBLISH.replace("  publish:", "  sneaky:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: aws-actions/configure-aws-credentials@v4\n  publish:")
        self.assertTrue(any("could deploy" in p for p in run(development_yml=other)))
        token = OWN_PUBLISH.replace("  test:\n    runs-on: ubuntu-latest\n", "  test:\n    runs-on: ubuntu-latest\n    permissions:\n      id-token: write\n")
        self.assertTrue(run(development_yml=token))

    def test_development_yml_can_only_be_started_by_a_push_or_a_pull_request(self):
        for extra in ("  workflow_dispatch:\n", "  schedule:\n    - cron: '0 3 * * *'\n", "  workflow_run:\n    workflows: [x]\n"):
            text = OWN_PUBLISH.replace("permissions:\n  contents: read\njobs:", extra + "permissions:\n  contents: read\njobs:", 1)
            with self.subTest(extra=extra):
                self.assertTrue(any("can only be started by a push or a pull request" in p for p in run(development_yml=text)), extra)

    def test_a_publish_job_in_another_workflow_is_a_deploy(self):
        self.assertTrue(run(development_yml=OWN_OK, publish_yml=OWN_PUBLISH.replace("name: development", "name: p")))

    def test_globs_follow_github_rules(self):
        self.assertTrue(guard._matches(["dev*"], "development"))
        self.assertFalse(guard._matches(["eng/**"], "development"))
        self.assertTrue(guard._matches(["**"], "feature/a/b"))
        self.assertFalse(guard._matches(["*"], "feature/a"))
        self.assertFalse(guard._matches(["**", "!development"], "development"))


if __name__ == "__main__":
    unittest.main()
