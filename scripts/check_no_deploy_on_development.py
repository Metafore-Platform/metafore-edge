#!/usr/bin/env python3
"""Fail when a workflow that can run for the `development` branch could also deploy.

metafore-edge's `main` deploys to production, `staging` to the staging cluster and `eng/**` to an engineer's namespace
(ci.yml, eng-branch-deploy.yml). The v2 work happens on `development`, which must never deploy. For every workflow in
.github/workflows this answers two questions:

* can it run for a push to `development`, or for a pull request into it (`on.push.branches`, `on.pull_request.branches`,
  their `-ignore` forms, GitHub's glob rules; a trigger with no branch filter fires for every branch)? and could it be
  started by hand or from another event on that ref (`workflow_dispatch`, `schedule`, `workflow_run`, ...)?
* does it carry anything that deploys (AWS credentials or ECR, kubectl, helm, a docker push, Argo CD)?

A workflow that does both fails the check. `development.yml` must in addition ask for nothing but `contents: read`, with ONE
exception (Compass a3990b6e): its job named `publish` may take the OIDC token and push the commit's image to the ECR repository
`metafore-dev/edge` and pin it in metafore-deploy. That job is held to: a push to `development` only, after every other job of the
file, in the `development` environment, with `contents: read` and `id-token: write` and nothing more, and no cluster, Helm, Argo CD,
GHCR, staging or production reference; `development.yml` itself can only be started by a push or a pull request.

A second, narrower exception: the job named `image` may take the OIDC token to sign in to ECR as the PULL-ONLY role
`vars.CI_PULL_ROLE_ARN` (the pull-through cache of base images, Compass a3990b6e). It gets `contents: read` and `id-token: write`
and nothing more, no environment, and it may not push, name the edge's repository or the publish role, or reach a cluster.

  python3 scripts/check_no_deploy_on_development.py [workflows-dir]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

BRANCH = "development"
OWN = "development.yml"
#: the one job of development.yml that may publish (see the module docstring)
PUBLISH_JOB = "publish"
PUBLISH_PERMISSIONS = {"contents": "read", "id-token": "write"}
#: the one pull-only job (see the module docstring)
IMAGE_JOB = "image"
IMAGE_PULL_ROLE = "CI_PULL_ROLE_ARN"
IMAGE_FORBIDDEN = re.compile(
    r"docker push|--push\b|put-image|metafore-dev/|EDGE_PUBLISH_ROLE_ARN|DEPLOY_APP|\bkubectl\b|\bhelm\b|azure/setup-helm|"
    r"docker/login-action|\bargocd\b|ghcr\.io|metafore-ilan|metafore-staging|\beks\b", re.IGNORECASE)
PUBLISH_FORBIDDEN = re.compile(
    r"\bkubectl\b|\bhelm\b|azure/setup-helm|docker/login-action|\bargocd\b|ghcr\.io|metafore-ilan|metafore-staging|\beks\b", re.IGNORECASE)
#: any ECR repository of the development account other than the edge's (images and charts), and any image under $REGISTRY but the edge's
PUBLISH_OTHER_REPO = re.compile(r"metafore-dev/(?!edge\b)|\$\{?REGISTRY\}?/(?!edge[:\"'\s]|edge$)")
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


def _strings(node: object):
    """Every string in a parsed workflow fragment (keys and values), so comments never count and a block scalar is one string."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for key, value in node.items():
            yield from _strings(key)
            yield from _strings(value)
    elif isinstance(node, (list, tuple)):
        for item in node:
            yield from _strings(item)


def _jobs(workflow: dict) -> dict:
    jobs = workflow.get("jobs")
    return {str(k): (v or {}) for k, v in jobs.items()} if isinstance(jobs, dict) else {}


def own_permission_problems(workflow: dict) -> list[str]:
    """development.yml may ask for `contents: read` and nothing more, at the top and in every job but `publish` and `image` (each checked on its own)."""
    bad = []
    blocks = [("workflow", workflow.get("permissions"))]
    blocks += [(f"job {name}", job.get("permissions")) for name, job in _jobs(workflow).items() if name not in (PUBLISH_JOB, IMAGE_JOB)]
    for where, perms in blocks:
        if where == "workflow" and perms is None:
            bad.append("workflow: no top-level `permissions: contents: read`")
        elif perms is not None and perms != {"contents": "read"}:
            bad.append(f"{where}: permissions are {perms!r}, only `contents: read` is allowed")
    return bad


def publish_problems(workflow: dict) -> list[str]:
    """What the `publish` job of development.yml must be: nothing but a push of the edge image from the development branch."""
    jobs = _jobs(workflow)
    job = jobs.get(PUBLISH_JOB)
    if job is None:
        return []
    bad = []
    cond = str(job.get("if", ""))
    if "github.event_name == 'push'" not in cond or "refs/heads/development" not in cond or "||" in cond:
        bad.append(f"job {PUBLISH_JOB}: `if` must be a push to development only (github.event_name == 'push' && "
                   f"github.ref == 'refs/heads/development', no `||`), it is {cond!r}")
    environment = job.get("environment")
    if (environment.get("name") if isinstance(environment, dict) else environment) != "development":
        bad.append(f"job {PUBLISH_JOB}: environment must be `development`, it is {environment!r}")
    needs = job.get("needs")
    needed = {needs} if isinstance(needs, str) else set(needs or [])
    missing = sorted(set(jobs) - {PUBLISH_JOB} - needed)
    if missing:
        bad.append(f"job {PUBLISH_JOB}: it must list every other job in `needs` (it publishes only a tree that passed), missing {missing}")
    if job.get("permissions") != PUBLISH_PERMISSIONS:
        bad.append(f"job {PUBLISH_JOB}: permissions are {job.get('permissions')!r}, they must be exactly {PUBLISH_PERMISSIONS!r}")
    text = "\n".join(_strings(job))
    forbidden = sorted({m.lower() for m in PUBLISH_FORBIDDEN.findall(text)})
    if forbidden:
        bad.append(f"job {PUBLISH_JOB}: it may push the edge image to ECR and pin it, nothing else, but it names {forbidden}")
    if PUBLISH_OTHER_REPO.search(text):
        bad.append(f"job {PUBLISH_JOB}: it may push only to the ECR repository metafore-dev/edge")
    return bad


def image_problems(workflow: dict) -> list[str]:
    """What the `image` job may be: a build that signs in to ECR as the pull-only role, and nothing else."""
    job = _jobs(workflow).get(IMAGE_JOB)
    if job is None:
        return []
    bad = []
    permissions = job.get("permissions")
    if permissions not in (None, {"contents": "read"}, PUBLISH_PERMISSIONS):
        bad.append(f"job {IMAGE_JOB}: permissions are {permissions!r}, they must be `contents: read` or exactly {PUBLISH_PERMISSIONS!r}")
    if "environment" in job:
        bad.append(f"job {IMAGE_JOB}: it runs in no environment (the pull-only role is for pull requests too), it has {job['environment']!r}")
    text = "\n".join(_strings(job))
    if "id-token" in str(job.get("permissions")) and IMAGE_PULL_ROLE not in text:
        bad.append(f"job {IMAGE_JOB}: it may sign in to AWS only as vars.{IMAGE_PULL_ROLE}")
    forbidden = sorted({m.lower() for m in IMAGE_FORBIDDEN.findall(text)})
    if forbidden:
        bad.append(f"job {IMAGE_JOB}: it may only pull base images and build, but it names {forbidden}")
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
        if path.name == OWN:
            # development.yml: every job but `publish` is held to the marker list; `publish` to its own rules
            rest = {**workflow, "jobs": {k: v for k, v in _jobs(workflow).items() if k not in (PUBLISH_JOB, IMAGE_JOB)}}
            found = DEPLOY_MARKERS.findall("\n".join(_strings(rest)))
            odd = sorted(set(events_of(workflow)) - {"push", "pull_request"})
            if odd:
                problems.append(f"{path.name}: it can only be started by a push or a pull request, not {odd}")
            problems += [f"{path.name}: {p}" for p in publish_problems(workflow) + image_problems(workflow)]
        else:
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
