#!/usr/bin/env bash
# Publishes a Ferret release on GitHub:
#   ./release.sh "what's new — one or two sentences"
# 1) writes update.json: installed apps will look for updates in latest.json of this repo's latest release (no key —
#    releases are public); 2) tests and jar (./build.sh); 3) Windows and macOS packages (see package.sh: JDK_WIN,
#    FERRET_MAC_RUN); 4) release v<version> with the archives and latest.json.
# Needs gh (gh auth login). Bump VERSION first: the updater compares version numbers.
set -euo pipefail
cd "$(dirname "$0")"
V=$(cat VERSION)
NOTES=${1:?'Describe the release: ./release.sh "notes"'}
REPO=${FERRET_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}
TAG="v$V"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then echo "Version $V is already released — bump VERSION"; exit 1; fi
BASE="https://github.com/$REPO/releases"
printf '{"url": "%s/latest/download/latest.json"}\n' "$BASE" > update.json

./build.sh
./package.sh win
./package.sh mac

python3 - "$V" "$NOTES" "$BASE/download/$TAG" > build/latest.json <<'PY'
import hashlib, json, sys
v, notes, base = sys.argv[1:4]
def entry(name):
    return {"url": f"{base}/{name}", "sha256": hashlib.sha256(open(f"dist/{name}", "rb").read()).hexdigest()}
print(json.dumps({"version": v, "notes": notes, "mac": entry(f"Ferret-{v}-mac.zip"),
                  "windows": entry(f"Ferret-{v}-windows.zip")}, ensure_ascii=False, indent=2))
PY
gh release create "$TAG" -R "$REPO" --title "Ferret $V" --notes "$NOTES" \
    "dist/Ferret-$V-windows.zip" "dist/Ferret-$V-mac.zip" "dist/Ferret-$V-mac.dmg" build/latest.json
echo "Released Ferret $V: $BASE/tag/$TAG"
