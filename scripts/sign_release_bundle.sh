#!/usr/bin/env bash
# Signs a release App Bundle with YOUR Play upload key so it can be uploaded to
# Play Console. The key and its passwords never enter the repository or a log:
# jarsigner asks for them on the terminal.
#
#   scripts/sign_release_bundle.sh app-release.aab /path/to/upload-keystore.jks upload-alias
#
# Needs a JDK (jarsigner). Writes <name>-signed.aab next to the input.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <unsigned.aab> <keystore> <key-alias>" >&2
    exit 2
fi
AAB="$1"; KEYSTORE="$2"; ALIAS="$3"
[[ -f "$AAB" ]] || { echo "No such bundle: $AAB" >&2; exit 1; }
[[ -f "$KEYSTORE" ]] || { echo "No such keystore: $KEYSTORE" >&2; exit 1; }
command -v jarsigner >/dev/null || { echo "jarsigner not found (install a JDK)" >&2; exit 1; }

SIGNED="${AAB%.aab}-signed.aab"
cp "$AAB" "$SIGNED"

# SHA-256 with RSA is what Android Studio uses for upload keys.
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 \
    -keystore "$KEYSTORE" "$SIGNED" "$ALIAS"

echo
jarsigner -verify -certs "$SIGNED" | sed -n '1,6p'
echo
echo "Signed bundle: $SIGNED"
echo "Upload it in Play Console. Keep mapping.txt for this version code."
