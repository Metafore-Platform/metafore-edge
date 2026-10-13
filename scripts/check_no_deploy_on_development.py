#!/usr/bin/env python3
"""Fail when a workflow that can run for the `development` branch could also deploy.

metafore-edge's `main` deploys to production, `staging` to the staging cluster and `eng/**` to an engineer's namespace
(ci.yml, eng-branch-deploy.yml). The v2 work happens on `development`, which must never deploy. For every workflow in
.github/workflows this answers two questions:

* can it run for a push to `development`, or for a pull request into it (`on.push.branches`, `on.pull_request.branches`,
  their `-ignore` forms, GitHub's glob rules; a trigger with no branch filter fires for every branch)? and could it be
  started by hand or from another event on that ref (`workflow_dispatch`, `schedule`, `workflow_run`, ...)?
* does it carry anything that deploys (AWS credentials or ECR, kubectl, helm, a docker push, Argo CD)?

A workflow that does both fails the check. `development.yml` must in addition ask for nothing but `contents: read`.

  python3 scripts/check_no_deploy_on_development.py [workflows-dir]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

BRANCH = "development"
OWN = "development.yml"
#: anything that deploys or reaches a cluster or registry
DEPLOY_MARKERS = re.compile(
    r"aws-actions/|configure-aws-credentials|amazon-ecr|\bkubectl\b|\bhelm\b|azure/setup-helm|docker/login-action|"
    r"docker push|\bargocd\b|id-token", re.IGNORECASE)
BRANCH_EVENTS = ("push", "pull_request", "pull_request_target")


def _glob(pattern: str) -> re.Pattern[str]:
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            out, i = out + ".*", i + 2
        elif pattern[i] == "*":
            out, i = out + "[^/]*", i + 1
        elif pattern[i] == "?":
            out, i = out + "[^/]", i + 1
        else:
            out, i = out + re.escape(pattern[i]), i + 1
    return re.compile(out + r"\Z")


def _matches(patterns: object, branch: str) -> bool:
    if isinstance(patterns, str):
        patterns = [patterns]
    positive = [p for p in patterns or [] if not str(p).startswith("!")]
    negative = [str(p)[1:] for p in patterns or [] if str(p).startswith("!")]
    hit = any(_glob(str(p)).match(branch) for p in positive)
    return hit and not any(_glob(p).match(branch) for p in negative)


def events_of(workflow: dict) -> dict:
    """The workflow's `on:` as {event: config}. (YAML reads a bare `on` key as True.)"""
    on = workflow.get("on", workflow.get(True))
    if isinstance(on, str):
        return {on: {}}
    if isinstance(on, list):
        return {str(e): {} for e in on}
    return {str(k): (v or {}) for k, v in (on or {}).items()}


def runs_for_development(workflow: dict, branch: str = BRANCH) -> list[str]:
    """Why this workflow can run for `branch` ([] when it cannot)."""
    why = []
    for event, config in events_of(workflow).items():
        if event in BRANCH_EVENTS:
            cfg = config if isinstance(config, dict) else {}
            if "branches" in cfg:
                if _matches(cfg["branches"], branch):
                    why.append(f"{event} to {branch} (branches filter)")
            elif "branches-ignore" in cfg:
                if not _matches(cfg["branches-ignore"], branch):
                    why.append(f"{event} to {branch} (not in branches-ignore)")
            else:
                why.append(f"{event} with no branch filter (every branch)")
        else:
            why.append(f"{event} (not tied to a branch: it can be run on any ref)")
    return why


def own_permission_problems(workflow: dict) -> list[str]:
    """development.yml may ask for `contents: read` and nothing more, at the top and in every job."""
    bad = []
    blocks = [("workflow", workflow.get("permissions"))]
    blocks += [(f"job {name}", (job or {}).get("permissions")) for name, job in (workflow.get("jobs") or {}).items()]
    for where, perms in blocks:
        if where == "workflow" and perms is None:
            bad.append("workflow: no top-level `permissions: contents: read`")
        elif perms is not None and perms != {"contents": "read"}:
            bad.append(f"{where}: permissions are {perms!r}, only `contents: read` is allowed")
    return bad


def check(directory: Path) -> list[str]:
    problems = []
    files = sorted(directory.glob("*.yml")) + sorted(directory.glob("*.yaml"))
    if not any(f.name == OWN for f in files):
        problems.append(f"{OWN} is missing")
    for path in files:
        text = path.read_text()
        workflow = yaml.safe_load(text) or {}
        why = runs_for_development(workflow)
        if not why:
            continue
        found = DEPLOY_MARKERS.findall(text)
        if found:
            problems.append(f"{path.name} can run for {BRANCH} ({'; '.join(why)}) and could deploy: "
                            f"{sorted({m.lower() for m in found})}")
        if path.name == OWN:
            problems += [f"{path.name}: {p}" for p in own_permission_problems(workflow)]
    return problems


def main(argv: list[str]) -> int:
    directory = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent / ".github" / "workflows"
    problems = check(directory)
    for p in problems:
        print(f"::error::{p}")
    if not problems:
        print(f"no workflow that can run for `{BRANCH}` deploys anything ({directory})")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
