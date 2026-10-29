#!/usr/bin/env bash
# Create the Orchestella Studio release keystore.
#
# This script is interactive on purpose: the passwords are typed, never passed
# on a command line (shell history, process list) and never written into the
# repository. It does not modify build files, does not print the passwords back,
# and refuses to overwrite an existing keystore.
#
# Usage (Git Bash on Windows, or any POSIX shell):
#     bash tools/release/create-release-keystore.sh [output-path]
#
# The decision to run this belongs to the maintainer — see
# docs/standards/RELEASE-SIGNING.md for custody rules and the CI wiring that
# follows.

set -euo pipefail

KEYSTORE_PATH="${1:-$HOME/orchestella-release.jks}"
ALIAS="orchestella-release"
VALIDITY_DAYS=10000
KEY_SIZE=4096

if [ -e "$KEYSTORE_PATH" ]; then
    echo "refusing to overwrite existing keystore: $KEYSTORE_PATH" >&2
    echo "move it away or pass a different path" >&2
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "keytool not found; install a JDK (17 is what the build uses) and retry" >&2
    exit 1
fi

printf 'Distinguished name — CN (your name or org): '
read -r DNAME_CN
printf 'Distinguished name — O (organisation, optional): '
read -r DNAME_O
printf 'Distinguished name — C (two-letter country code, e.g. CN): '
read -r DNAME_C

DNAME="CN=${DNAME_CN:-Orchestella Studio}"
if [ -n "${DNAME_O:-}" ]; then DNAME="$DNAME, O=$DNAME_O"; fi
if [ -n "${DNAME_C:-}" ]; then DNAME="$DNAME, C=$DNAME_C"; fi

printf 'Keystore password (typed, not echoed): '
read -rs STORE_PASS
printf '\n'
printf 'Key password (press Enter to reuse the keystore password): '
read -rs KEY_PASS
printf '\n'
if [ -z "${KEY_PASS:-}" ]; then KEY_PASS="$STORE_PASS"; fi

if [ "${#STORE_PASS}" -lt 12 ]; then
    echo "keystore password must be at least 12 characters" >&2
    exit 1
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"

keytool -genkeypair \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize "$KEY_SIZE" \
    -sigalg SHA256withRSA \
    -validity "$VALIDITY_DAYS" \
    -dname "$DNAME" \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS"

chmod 600 "$KEYSTORE_PATH"

echo
echo "keystore created: $KEYSTORE_PATH"
echo "alias: $ALIAS (valid ${VALIDITY_DAYS} days)"
echo
echo "certificate SHA-256 (record this in the release manifest):"
keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$ALIAS" -storepass "$STORE_PASS" \
    | grep -E "SHA256:|SHA-256:" || true
echo
echo "next steps:"
echo "  1. back the keystore up twice, offline, separate from its passwords"
echo "  2. put these four lines in ~/.gradle/gradle.properties (NOT in the repo):"
echo "       orchestella.signing.storeFile=<absolute path to the keystore>"
echo "       orchestella.signing.storePassword=<keystore password>"
echo "       orchestella.signing.keyAlias=$ALIAS"
echo "       orchestella.signing.keyPassword=<key password>"
echo "  3. wire the same four values into the CI secrets listed in"
echo "     docs/standards/RELEASE-SIGNING.md"
echo "  4. verify a build with tools/release/verify_apk.py before tagging"
