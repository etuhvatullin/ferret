#!/usr/bin/env bash
# Збирання Ferret (Java-версія для Mac і Windows) — у WSL / Linux / Mac, потрібна лише Java 21 (javac, jar):
#   ./build.sh         — бібліотеки (з перевіркою sha1), компіляція, перевірки, build/ferret.jar
#   ./build.sh fast    — те саме без перевірок
#   ./build.sh ui      — плюс перевірки вікна справжніми натисканнями (потрібен екран; у WSL — WSLg, DISPLAY=:0)
# Пакування в програму для Mac і Windows — package-mac.sh / package-win.ps1 (jpackage збирає лише під свою систему).
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
    if [[ -n "$want" && "$want" != "$have" ]]; then echo "✗ $f: sha1 не збігся"; rm -f "$f"; exit 1; fi
done < libs.txt

rm -rf build/classes build/test-classes && mkdir -p build/classes build/test-classes
CP="lib/*"
"$JAVAC" --release 21 -encoding UTF-8 -Xlint:-options -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
mkdir -p build/classes/ferret/assets && cp assets/wordmark.png assets/icon.png build/classes/ferret/assets/
printf '%s\n' "$VERSION" > build/classes/ferret/assets/VERSION

if [[ "${1:-}" != fast ]]; then
    "$JAVAC" --release 21 -encoding UTF-8 -cp "build/classes:$CP" -d build/test-classes $(find src/test/java -name '*.java')
    FERRET_TEST_CACHE="$PWD/.cache" "$JAVA" -Djava.awt.headless=true -cp "build/test-classes:build/classes:$CP" ferret.tests.All
    if [[ "${1:-}" == ui ]]; then
        FERRET_TEST_CACHE="$PWD/.cache" DISPLAY="${DISPLAY:-:0}" "$JAVA" -cp "build/test-classes:build/classes:$CP" ferret.ui.UiTests
    fi
fi

"$JAR" --create --file build/ferret.jar --main-class ferret.App -C build/classes .
echo "Зібрано: build/ferret.jar (версія $VERSION)"
