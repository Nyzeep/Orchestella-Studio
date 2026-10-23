#!/usr/bin/env python3
"""List or delete failed GitHub Actions runs for one repository.

Dry-run is the default. Deletion requires both --apply and a token with
actions:write (classic PAT or fine-grained token). The script never prints
the token and never deletes a run whose conclusion is not failure.

Examples:

    python tools/ci/cleanup_failed_runs.py --self-test
    GITHUB_TOKEN=... python tools/ci/cleanup_failed_runs.py
    GITHUB_TOKEN=... python tools/ci/cleanup_failed_runs.py --apply
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable

API = "https://api.github.com"
DEFAULT_REPO = "Nyzeep/Orchestella-Studio"
USER_AGENT = "orchestella-ci-cleanup/1"


def _request(url: str, token: str, method: str = "GET") -> tuple[int, Any]:
    request = urllib.request.Request(
        url,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        return error.code, body[:200]


def list_failed_runs(repo: str, token: str, per_page: int = 100) -> list[dict[str, Any]]:
    status, payload = _request(
        f"{API}/repos/{repo}/actions/runs?status=failure&per_page={per_page}", token
    )
    if status != 200 or not isinstance(payload, dict):
        raise RuntimeError(f"GitHub API returned {status}: {payload}")
    return list(payload.get("workflow_runs") or [])


def _created_at(run: dict[str, Any]) -> datetime | None:
    raw = run.get("created_at")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def filter_runs(
    runs: Iterable[dict[str, Any]],
    branch: str | None = None,
    workflow: str | None = None,
    older_than_days: int | None = None,
    now: datetime | None = None,
) -> list[dict[str, Any]]:
    now = now or datetime.now(timezone.utc)
    cutoff = now - timedelta(days=older_than_days) if older_than_days else None
    selected: list[dict[str, Any]] = []
    for run in runs:
        if run.get("conclusion") != "failure":
            continue
        if branch and run.get("head_branch") != branch:
            continue
        if workflow and run.get("name") != workflow:
            continue
        if cutoff is not None:
            created = _created_at(run)
            if created is None or created >= cutoff:
                continue
        selected.append(run)
    return selected


def delete_run(repo: str, token: str, run_id: int) -> tuple[int, Any]:
    return _request(f"{API}/repos/{repo}/actions/runs/{run_id}", token, method="DELETE")


def _self_test() -> None:
    now = datetime(2026, 10, 23, tzinfo=timezone.utc)
    runs = [
        {"id": 1, "conclusion": "failure", "head_branch": "main", "name": "CI",
         "created_at": "2026-10-22T00:00:00Z"},
        {"id": 2, "conclusion": "success", "head_branch": "main", "name": "CI",
         "created_at": "2026-10-22T00:00:00Z"},
        {"id": 3, "conclusion": "failure", "head_branch": "old", "name": "CI",
         "created_at": "2026-10-01T00:00:00Z"},
        {"id": 4, "conclusion": "failure", "head_branch": "main", "name": "Other",
         "created_at": "2026-10-23T00:00:00Z"},
        {"id": 5, "conclusion": "cancelled", "head_branch": "main", "name": "CI",
         "created_at": "2026-10-20T00:00:00Z"},
    ]
    assert [r["id"] for r in filter_runs(runs, now=now)] == [1, 3, 4]
    assert [r["id"] for r in filter_runs(runs, branch="main", now=now)] == [1, 4]
    assert [r["id"] for r in filter_runs(runs, older_than_days=7, now=now)] == [3]
    assert [r["id"] for r in filter_runs(runs, workflow="Other", now=now)] == [4]
    print("SELF_TEST_OK")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--branch", default=None, help="only this head branch")
    parser.add_argument("--workflow", default=None, help="only this workflow name")
    parser.add_argument("--older-than-days", type=int, default=None)
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--apply", action="store_true", help="actually delete; default dry-run")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        _self_test()
        return 0

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print("GITHUB_TOKEN_REQUIRED: set a token with actions:write", file=sys.stderr)
        return 2

    try:
        runs = list_failed_runs(args.repo, token, per_page=max(1, min(args.limit, 100)))
        selected = filter_runs(
            runs,
            branch=args.branch,
            workflow=args.workflow,
            older_than_days=args.older_than_days,
        )[: args.limit]
    except RuntimeError as error:
        print(f"GITHUB_API_ERROR: {error}", file=sys.stderr)
        return 3

    if not selected:
        print("NO_FAILED_RUNS")
        return 0

    for run in selected:
        run_id = run.get("id")
        branch = run.get("head_branch") or "-"
        sha = (run.get("head_sha") or "")[:8]
        created = (run.get("created_at") or "")[:10]
        action = "DELETE" if args.apply else "DRY_RUN"
        print(f"{action} id={run_id} name={run.get('name')} branch={branch} sha={sha} created={created}")
        if args.apply:
            status, payload = delete_run(args.repo, token, int(run_id))
            if status not in (204, 200):
                print(f"DELETE_FAILED id={run_id} status={status} {payload}", file=sys.stderr)
                return 4
    if not args.apply:
        print("DRY_RUN_ONLY: pass --apply to delete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
