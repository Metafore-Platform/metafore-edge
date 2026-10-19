#!/usr/bin/env python3
"""Fail when any workflow file in .github/workflows/ references a non-Metafore-Platform repo or registry.

All CI/CD in this repository works within the Metafore-Platform GitHub organisation and the
metafore-dev ECR namespace. Any reference to a personal account (ilansehayekCA), a personal ECR
namespace (metafore-ilan), GHCR (ghcr.io), or a legacy cluster name is a sign that a stale or
out-of-org workflow has been introduced.

  python3 scripts/check_no_personal_repos.py [workflows-dir]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

FORBIDDEN: list[tuple[str, re.Pattern[str]]] = [
    ("ilansehayekCA personal account", re.compile(r"ilansehayekCA", re.IGNORECASE)),
    ("metafore-ilan ECR namespace", re.compile(r"metafore-ilan", re.IGNORECASE)),
    ("GHCR registry (ghcr.io)", re.compile(r"ghcr\.io", re.IGNORECASE)),
    ("legacy staging cluster/repo", re.compile(r"\bmetafore-staging\b", re.IGNORECASE)),
]

# `repository:` fields in `with:` blocks are always a repo checkout — must be Metafore-Platform/
REPO_FIELD = re.compile(r"^\s+repository\s*:\s*([^\s#\n]+)", re.MULTILINE)
_ORG_RE = re.compile(r"^[A-Za-z0-9_.-]+/")


def _check_file(path: Path) -> list[str]:
    text = path.read_text()
    problems: list[str] = []
    for label, pattern in FORBIDDEN:
        for m in pattern.finditer(text):
            line = text[: m.start()].count("\n") + 1
            problems.append(f"{path}:{line}: {label} — {m.group()!r}")
    for m in REPO_FIELD.finditer(text):
        repo = m.group(1).strip("\"'")
        if _ORG_RE.match(repo) and not repo.startswith("Metafore-Platform/"):
            line = text[: m.start()].count("\n") + 1
            problems.append(f"{path}:{line}: repository: {repo!r} is not in Metafore-Platform/")
    return problems


def main(workflows_dir: str = ".github/workflows") -> int:
    all_problems: list[str] = []
    checked = 0
    for path in sorted(Path(workflows_dir).glob("*.yml")):
        all_problems.extend(_check_file(path))
        checked += 1
    if all_problems:
        for p in all_problems:
            print(f"FAIL {p}", file=sys.stderr)
        return 1
    print(f"OK — {checked} workflow file(s) checked, no personal-repo references")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
