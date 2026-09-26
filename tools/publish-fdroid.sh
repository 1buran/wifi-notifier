#!/usr/bin/env bash
#
# Publishes the app into the self-hosted F-Droid repository that the F-Droid
# client on the phone subscribes to
# (https://1buran.github.io/wifi-notifier/repo).
#
# What it does, in order:
#   1. builds the signed release APK (keystore.properties in the project root);
#   2. copies the APK, the metadata and the fastlane listing into the
#      fdroidserver workdir outside the repository;
#   3. runs `fdroid update`, which writes the signed repository index
#      (index-v1.jar, index-v2.json, icons, screenshots) into <workdir>/repo;
#   4. pushes the content of <workdir>/repo to the gh-pages branch.
#
# GitHub Pages then serves the branch as
# https://1buran.github.io/wifi-notifier/repo, and the client adds that URL
# as a repository. Nothing secret lives in the git tree: the keystores are in
# ~/.config/wifi-notifier and the workdir in ~/.cache.
#
# Usage: tools/publish-fdroid.sh [--no-push]

set -euo pipefail

readonly APP_ID="com.buran.wifinotifier"
readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SECRETS_DIR="${WIFI_NOTIFIER_SECRETS_DIR:-$HOME/.config/wifi-notifier}"
readonly WORK_DIR="${WIFI_NOTIFIER_FDROID_DIR:-$HOME/.cache/wifi-notifier-fdroid}"
readonly FDROID_BIN="${FDROID_BIN:-$HOME/.local/share/fdroidserver-venv/bin/fdroid}"
readonly PAGES_BRANCH="gh-pages"

PUSH=1
[ "${1:-}" = "--no-push" ] && PUSH=0

fail() { echo "error: $*" >&2; exit 1; }

[ -x "$FDROID_BIN" ] || fail "fdroidserver not found at $FDROID_BIN (see AGENTS.md)"
[ -f "$SECRETS_DIR/keystore.properties" ] || fail "no keystore.properties in $SECRETS_DIR"
[ -f "$PROJECT_DIR/keystore.properties" ] || fail "no keystore.properties in $PROJECT_DIR"

echo "==> building the signed release APK"
cd "$PROJECT_DIR"
./gradlew --quiet assembleRelease
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || fail "release APK not found at $APK (is keystore.properties valid?)"

echo "==> preparing the workdir $WORK_DIR"
mkdir -p "$WORK_DIR/repo" "$WORK_DIR/metadata"

# config.yml holds the password, so it is generated on every run from
# keystore.properties instead of being committed. Both keys - the app one
# (wifi-notifier) and the repository index one (fdroid) - live in that single
# keystore, because fdroidserver expects one keystore per configuration.
read -r STORE_FILE STORE_PASSWORD <<<"$(awk -F= '/^storeFile=/{f=$2} /^storePassword=/{p=$2} END{print f, p}' "$SECRETS_DIR/keystore.properties")"
cat >"$WORK_DIR/config.yml" <<EOF
sdk_path: $HOME/Android/Sdk
repo_url: https://1buran.github.io/wifi-notifier/repo
repo_name: Wi-Fi Notifier
repo_description: Personal builds of Wi-Fi Notifier
keystore: $STORE_FILE
keystorepass: $STORE_PASSWORD
keypass: $STORE_PASSWORD
keyalias: wifi-notifier
repo_keyalias: fdroid
archive_older: 4
EOF
chmod 600 "$WORK_DIR/config.yml"

cp "$APK" "$WORK_DIR/repo/"
cp "$PROJECT_DIR/fdroid/metadata/$APP_ID.yml" "$WORK_DIR/metadata/"

# The repository icon shown by the F-Droid client next to the repository name.
# fdroidserver looks for repo_icon (a bare file name) in the workdir root and
# copies it into repo/icons itself.
cp "$PROJECT_DIR/fdroid/repo-icon.png" "$WORK_DIR/icon.png"

# Category names for the index: fdroidserver reads them from
# config/<locale>/categories.yml.
mkdir -p "$WORK_DIR/config/en-US"
cp "$PROJECT_DIR/fdroid/categories.yml" "$WORK_DIR/config/en-US/categories.yml"

# The classic per-app metadata layout: fdroidserver reads the listing from
# metadata/<id>/<locale>/ instead of cloning the repository for fastlane.
rm -rf "$WORK_DIR/metadata/$APP_ID"
for locale_dir in "$PROJECT_DIR"/fastlane/metadata/android/*/; do
    locale="$(basename "$locale_dir")"
    mkdir -p "$WORK_DIR/metadata/$APP_ID/$locale/images/phoneScreenshots"
    cp "$locale_dir"/*.txt "$WORK_DIR/metadata/$APP_ID/$locale/" 2>/dev/null || true
    cp "$locale_dir"/changelogs/*.txt "$WORK_DIR/metadata/$APP_ID/$locale/" 2>/dev/null || true
    cp "$locale_dir"/images/phoneScreenshots/*.png "$WORK_DIR/metadata/$APP_ID/$locale/images/phoneScreenshots/" 2>/dev/null || true
done

echo "==> indexing the repository"
cd "$WORK_DIR"
"$FDROID_BIN" update

echo "==> repository content"
ls "$WORK_DIR/repo" | head -20

if [ "$PUSH" = 0 ]; then
    echo "==> --no-push, stopping before the git step"
    exit 0
fi

echo "==> publishing to the $PAGES_BRANCH branch"
WORKTREE="$(mktemp -d)"
trap 'git -C "$PROJECT_DIR" worktree remove --force "$WORKTREE" 2>/dev/null || true; rm -rf "$WORKTREE"' EXIT
cd "$PROJECT_DIR"
if git show-ref --quiet "refs/heads/$PAGES_BRANCH"; then
    git worktree add --quiet "$WORKTREE" "$PAGES_BRANCH"
else
    # An empty root commit is the portable way to start an orphan branch
    # without switching the working tree off main.
    EMPTY_TREE="$(git hash-object -t tree /dev/null)"
    ROOT_COMMIT="$(git commit-tree "$EMPTY_TREE" -m "chore: initialize $PAGES_BRANCH")"
    git branch "$PAGES_BRANCH" "$ROOT_COMMIT"
    git worktree add --quiet "$WORKTREE" "$PAGES_BRANCH"
fi
find "$WORKTREE" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
# fdroidserver insists on a repo_url ending with /repo, so the published
# branch keeps the index in the repo/ subdirectory.
mkdir -p "$WORKTREE/repo"
cp -r "$WORK_DIR/repo/." "$WORKTREE/repo/"
# Without a page at the branch root the site answers 404 there, which looks
# like a broken repository when the link is opened in a browser.
cp "$PROJECT_DIR/fdroid/index.html" "$WORKTREE/index.html"
git -C "$WORKTREE" add -A
if git -C "$WORKTREE" diff --cached --quiet; then
    echo "==> nothing changed in the repository"
else
    VERSION="$(awk '/versionName:/{print $2; exit}' "$PROJECT_DIR/fdroid/metadata/$APP_ID.yml" | tr -d ' ')"
    git -C "$WORKTREE" commit --quiet -m "chore: publish $APP_ID $VERSION to the f-droid repository"
    git -C "$WORKTREE" push --quiet origin "$PAGES_BRANCH"
    echo "==> pushed $PAGES_BRANCH; the app is now available at the repository URL"
fi
