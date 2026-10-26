#!/usr/bin/env bash
# Builds Ferret (Java app for macOS and Windows) on Linux / WSL / macOS; needs only Java 21 (javac, jar):
#   ./build.sh         — libraries (sha1-checked), compile, tests, build/ferret.jar
#   ./build.sh fast    — the same without tests
#   ./build.sh ui      — plus UI tests with real key presses (needs a display; in WSL — WSLg, DISPLAY=:0)
# Packaging into an app — package.sh (jpackage builds only for its own OS).
set -euo pipefail
cd "$(dirname "$0")"
JAVAC=${JAVAC:-javac}
JAR=${JAR:-jar}
JAVA=${JAVA:-java}
VERSION=$(cat VERSION)

mkdir -p lib
while read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    IFS=: read -r g a v <<< "$line"
    f="lib/$a-$v.jar"
    url="https://repo1.maven.org/maven2/${g//.//}/$a/$v/$a-$v.jar"
    if [[ ! -f "$f" ]]; then curl -sfL -o "$f.part" "$url" && mv "$f.part" "$f"; fi
    want=$(curl -sfL "$url.sha1" | cut -c1-40 || true)
    have=$( (sha1sum "$f" 2>/dev/null || shasum -a 1 "$f") | cut -c1-40)
    if [[ -n "$want" && "$want" != "$have" ]]; then echo "✗ $f: sha1 mismatch"; rm -f "$f"; exit 1; fi
done < libs.txt

rm -rf build/classes build/test-classes && mkdir -p build/classes build/test-classes
CP="lib/*"
"$JAVAC" --release 21 -encoding UTF-8 -Xlint:-options -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
mkdir -p build/classes/ferret/assets && cp assets/wordmark.png assets/icon.png build/classes/ferret/assets/
cp -r src/main/resources/. build/classes/   # UI texts (ferret/i18n/messages*.properties)
printf '%s\n' "$VERSION" > build/classes/ferret/assets/VERSION

if [[ "${1:-}" != fast ]]; then
    "$JAVAC" --release 21 -encoding UTF-8 -cp "build/classes:$CP" -d build/test-classes $(find src/test/java -name '*.java')
    FERRET_TEST_CACHE="$PWD/.cache" "$JAVA" -Djava.awt.headless=true -cp "build/test-classes:build/classes:$CP" ferret.tests.All
    if [[ "${1:-}" == ui ]]; then
        FERRET_TEST_CACHE="$PWD/.cache" DISPLAY="${DISPLAY:-:0}" "$JAVA" -cp "build/test-classes:build/classes:$CP" ferret.ui.UiTests
    fi
fi

"$JAR" --create --file build/ferret.jar --main-class ferret.App -C build/classes .
echo "Built: build/ferret.jar (version $VERSION)"
