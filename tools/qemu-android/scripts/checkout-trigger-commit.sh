#!/usr/bin/env bash
set -euo pipefail

remote="${CI_REMOTE_URL:?CI_REMOTE_URL required}"
destination="${CI_CHECKOUT_DIR:?CI_CHECKOUT_DIR required}"
target="${CI_TARGET_SHA:?CI_TARGET_SHA required}"
event="${CI_EVENT_NAME:?CI_EVENT_NAME required}"
[[ "$target" =~ ^[0-9a-fA-F]{40}$ ]] || { printf 'CI_CHECKOUT_FAIL invalid target SHA\n' >&2; exit 1; }
[[ ! -e "$destination" && ! -L "$destination" ]] || { printf 'CI_CHECKOUT_FAIL destination already exists\n' >&2; exit 1; }
case "$event" in
  pull_request)
    number="${CI_PR_NUMBER:?CI_PR_NUMBER required for pull requests}"
    [[ "$number" =~ ^[1-9][0-9]*$ ]] || { printf 'CI_CHECKOUT_FAIL invalid PR number\n' >&2; exit 1; }
    ref="refs/pull/$number/head" ;;
  push) ref="$target" ;;
  *) printf 'CI_CHECKOUT_FAIL unsupported event\n' >&2; exit 1 ;;
esac
git clone --no-checkout --depth 1 "$remote" "$destination"
git -C "$destination" fetch --no-tags --depth 1 origin "$ref"
fetched="$(git -C "$destination" rev-parse FETCH_HEAD)"
[[ "${fetched,,}" == "${target,,}" ]] || { printf 'CI_CHECKOUT_FAIL fetched SHA mismatch\n' >&2; exit 1; }
git -C "$destination" checkout --detach "$fetched"
actual="$(git -C "$destination" rev-parse HEAD)"
[[ "${actual,,}" == "${target,,}" ]] || { printf 'CI_CHECKOUT_FAIL checked-out SHA mismatch\n' >&2; exit 1; }
printf 'CI_CHECKOUT_OK sha=%s\n' "$actual"
