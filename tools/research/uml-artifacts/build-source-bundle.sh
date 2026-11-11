#!/usr/bin/env bash
# Assemble or verify the GPL-2.0 corresponding-source bundle for one UML
# research artifact set.
#
# Research scope: the bundle is a recorded obligation artifact. It does not
# create a GitHub Release, does not sign anything and does not touch the
# product build/release workflows.
#
# Usage:
#   tools/research/uml-artifacts/build-source-bundle.sh \
#       --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
#       --out /tmp/uml-fast-arm64-source.tar.gz \
#       [--kernel-source /path/to/linux-um-arm64-v0.3.0.tar.gz]
#
#   tools/research/uml-artifacts/build-source-bundle.sh \
#       --verify-source-bundle /tmp/uml-fast-arm64-source.tar.gz \
#       --expect-manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json
#
# The implementation lives in check_uml_artifacts.py so the manifest checker
# and the bundle builder share one definition of a valid pin.
set -euo pipefail

HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$HERE/check_uml_artifacts.py" "$@"
