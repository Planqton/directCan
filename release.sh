#!/bin/bash

# DirectCAN Release Script
# Baut die APK, erstellt Tag und pusht zu GitHub

set -e

echo "========================================="
echo "       DirectCAN Release Script"
echo "========================================="
echo ""

# Prüfen ob wir im richtigen Verzeichnis sind
if [ ! -f "gradlew" ]; then
    echo "Fehler: gradlew nicht gefunden. Bitte im Projektverzeichnis ausführen."
    exit 1
fi

# Prüfen ob es uncommitted changes gibt
if [ -n "$(git status --porcelain)" ]; then
    echo "Warnung: Es gibt uncommitted changes:"
    git status --short
    echo ""
    read -p "Trotzdem fortfahren? (j/n): " continue
    if [ "$continue" != "j" ]; then
        echo "Abgebrochen."
        exit 1
    fi
fi

# Letzte Tags anzeigen
echo "Letzte Versionen:"
git tag --sort=-v:refname | head -5 || echo "  (keine Tags vorhanden)"
echo ""

# Version abfragen
read -p "Neue Version (z.B. v1.0.0): " VERSION

# Validieren
if [[ ! $VERSION =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Fehler: Version muss Format v1.2.3 haben"
    exit 1
fi

# Prüfen ob Tag schon existiert
if git rev-parse "$VERSION" >/dev/null 2>&1; then
    echo "Fehler: Tag $VERSION existiert bereits!"
    exit 1
fi

echo ""
echo "Building APK..."
echo "========================================="

# Java 17 setzen
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# APK bauen
./gradlew assembleDebug

# Prüfen ob APK erstellt wurde
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "Fehler: APK wurde nicht erstellt!"
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo ""
echo "APK erfolgreich gebaut: $APK_SIZE"
echo ""

# Zusammenfassung
echo "========================================="
echo "Release Zusammenfassung:"
echo "  Version: $VERSION"
echo "  APK:     $APK_SIZE"
echo "========================================="
echo ""

read -p "Release erstellen und pushen? (j/n): " confirm
if [ "$confirm" != "j" ]; then
    echo "Abgebrochen."
    exit 1
fi

# Tag erstellen
echo ""
echo "Erstelle Tag $VERSION..."
git tag "$VERSION"

# Pushen
echo "Pushe zu GitHub..."
git push origin master 2>/dev/null || git push origin main 2>/dev/null || true
git push origin "$VERSION"

echo ""
echo "========================================="
echo "Release $VERSION erfolgreich erstellt!"
echo ""
echo "GitHub Actions baut jetzt die APK..."
echo "Release erscheint in ~2-3 Minuten unter:"
echo "https://github.com/$(git remote get-url origin | sed 's/.*github.com[:/]\(.*\)\.git/\1/')/releases"
echo "========================================="
