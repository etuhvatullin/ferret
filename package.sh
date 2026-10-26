#!/usr/bin/env bash
# Packages Ferret into an app with Java inside (jlink — only the needed Java modules, jpackage — the app).
# jpackage builds only for its own OS, therefore:
#   ./package.sh win   — in WSL with a Windows JDK 21 (JDK_WIN=/mnt/c/…/jdk-21) → dist/Ferret-<version>-windows.zip
#                        (a Ferret folder + "Install Ferret.cmd"; the updater takes only the Ferret folder)
#   ./package.sh mac   — on a Mac, or from another machine via FERRET_MAC_RUN (a command that runs a shell on the Mac,
#                        e.g. "ssh mymac") → dist/Ferret-<version>-mac.zip (for the updater) and .dmg
# Run ./build.sh first. The update URL is embedded only if update.json exists (release.sh writes it).
# FERRET_VENDOR and FERRET_MAC_ID — vendor and macOS bundle id; JAVA_HOME on the Mac — JDK 21 (default: Homebrew).
set -euo pipefail
cd "$(dirname "$0")"
V=$(cat VERSION)
VENDOR=${FERRET_VENDOR:-etuhvatullin}
MAC_ID=${FERRET_MAC_ID:-io.github.etuhvatullin.ferret}
MODULES="java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.naming.dns,jdk.security.auth,jdk.security.jgss,jdk.net,jdk.management,jdk.accessibility,jdk.unsupported.desktop,jdk.xml.dom,jdk.httpserver"
[[ -f build/ferret.jar ]] || { echo "Run ./build.sh first"; exit 1; }
JAR=build/ferret.jar
if [[ -f update.json ]]; then   # update URL goes into a copy of the jar, not into build/ferret.jar
    rm -rf build/pkg-jar && mkdir -p build/pkg-jar/ferret/assets && cp update.json build/pkg-jar/ferret/assets/
    cp build/ferret.jar build/ferret-release.jar && (cd build/pkg-jar && jar --update --file ../ferret-release.jar ferret/assets/update.json)
    JAR=build/ferret-release.jar
fi
mkdir -p dist
zipdir() { python3 - "$1" "$2" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
PY
}

# Steps on the Mac — in a folder with ferret.jar, lib/ and assets/; args: version, modules, vendor, bundle id.
mac_steps() { cat <<'SH'
set -euo pipefail
V=$1; MODULES=$2; VENDOR=$3; MAC_ID=$4
J=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin
rm -rf input runtime out dmg Ferret-*-mac.zip Ferret-*-mac.dmg
mkdir input && cp ferret.jar input/ && cp lib/*.jar input/
"$J/jlink" --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output runtime
"$J/jpackage" --type app-image --name Ferret --app-version "$V" --vendor "$VENDOR" --mac-package-identifier "$MAC_ID" \
    --input input --main-jar ferret.jar --main-class ferret.App --runtime-image runtime --icon assets/Ferret.icns \
    --java-options '-Xmx2g' --java-options '-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua' --dest out
codesign --force --deep -s - out/Ferret.app
ditto -c -k --keepParent out/Ferret.app "Ferret-$V-mac.zip"
mkdir dmg && ditto out/Ferret.app dmg/Ferret.app && ln -s /Applications dmg/Applications   # the .dmg shows where to drag it
hdiutil create -quiet -volname Ferret -srcfolder dmg -ov -format UDZO "Ferret-$V-mac.dmg"
SH
}

case "${1:-}" in
win)
    JDK=${JDK_WIN:?Set JDK_WIN — a Windows JDK 21 visible from WSL (e.g. /mnt/c/Users/…/jdk-21)}
    WTEMP=$(wslpath "$(cmd.exe /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')")
    W=$(mktemp -d "$WTEMP/ferret-pkg.XXXX")   # Windows tools need a folder on a Windows drive
    mkdir -p "$W/input" "$W/zip"
    cp "$JAR" "$W/input/ferret.jar" && cp lib/*.jar "$W/input/" && cp assets/Ferret.ico "$W/"
    "$JDK/bin/jlink.exe" --add-modules "$MODULES,jdk.crypto.mscapi" --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
        --output "$(wslpath -w "$W/runtime")"
    "$JDK/bin/jpackage.exe" --type app-image --name Ferret --app-version "$V" --vendor "$VENDOR" --description "Ferret — SQL console" \
        --input "$(wslpath -w "$W/input")" --main-jar ferret.jar --main-class ferret.App --runtime-image "$(wslpath -w "$W/runtime")" \
        --icon "$(wslpath -w "$W/Ferret.ico")" --java-options "-Xmx2g" --dest "$(wslpath -w "$W/zip")"
    cp install-windows.cmd "$W/zip/Install Ferret.cmd"
    rm -f "dist/Ferret-$V-windows.zip" && zipdir "$W/zip" "dist/Ferret-$V-windows.zip"
    rm -rf "$W"
    echo "Windows: dist/Ferret-$V-windows.zip ($(du -h "dist/Ferret-$V-windows.zip" | cut -f1))"
    ;;
mac)
    if [[ "$(uname)" == Darwin ]]; then
        P=$(mktemp -d)
        cp "$JAR" "$P/ferret.jar" && mkdir -p "$P/lib" "$P/assets" && cp lib/*.jar "$P/lib/" && cp assets/Ferret.icns "$P/assets/"
        (cd "$P" && mac_steps | bash -s -- "$V" "$MODULES" "$VENDOR" "$MAC_ID")
        cp "$P/Ferret-$V-mac.zip" "$P/Ferret-$V-mac.dmg" dist/
        rm -rf "$P"
    else
        RUN=${FERRET_MAC_RUN:?Not a Mac: set FERRET_MAC_RUN — a command that runs a shell on the Mac (e.g. "ssh mymac")}
        S=$(mktemp -d) && cp "$JAR" "$S/ferret.jar" && mkdir -p "$S/lib" "$S/assets" && cp lib/*.jar "$S/lib/" && cp assets/Ferret.icns "$S/assets/"
        tar cf - -C "$S" . | $RUN 'rm -rf ~/.cache/ferret-pkg && mkdir -p ~/.cache/ferret-pkg && cd ~/.cache/ferret-pkg && tar xf -'
        rm -rf "$S"
        mac_steps | $RUN "cd ~/.cache/ferret-pkg && bash -s -- '$V' '$MODULES' '$VENDOR' '$MAC_ID'"
        $RUN "cat ~/.cache/ferret-pkg/Ferret-$V-mac.zip" > "dist/Ferret-$V-mac.zip"
        $RUN "cat ~/.cache/ferret-pkg/Ferret-$V-mac.dmg" > "dist/Ferret-$V-mac.dmg"
    fi
    echo "Mac: dist/Ferret-$V-mac.zip ($(du -h "dist/Ferret-$V-mac.zip" | cut -f1)), dist/Ferret-$V-mac.dmg"
    ;;
*) echo "./package.sh win | mac"; exit 1 ;;
esac
