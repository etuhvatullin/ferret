#!/usr/bin/env bash
# Випуск Ferret на GitHub Releases:
#   ./release.sh "що нового — одним-двома реченнями"
# 1) пише update.json: програми шукатимуть оновлення в latest.json останнього випуску цього репозиторію (ключ не
#    потрібен — випуски публічні); 2) перевірки й jar (./build.sh); 3) пакети для Windows і Mac (package.sh — див. його
#    шапку: JDK_WIN, FERRET_MAC_RUN); 4) випуск v<версія> з архівами й latest.json.
# Потрібен gh (gh auth login). Перед випуском підніми VERSION: оновлювач порівнює номери.
set -euo pipefail
cd "$(dirname "$0")"
V=$(cat VERSION)
NOTES=${1:?'Опиши, що нового: ./release.sh "текст"'}
REPO=${FERRET_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}
TAG="v$V"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then echo "Версію $V уже випущено — підніми VERSION"; exit 1; fi
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
echo "Випущено Ferret $V: $BASE/tag/$TAG"
