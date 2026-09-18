#!/usr/bin/env bash
# Works out the next release version from git tags and the commits since the last release.
#
# Run from the repository root with full history and tags. Prints key=value lines, meant to be
# appended to $GITHUB_OUTPUT:
#   skip      true if HEAD is already a released commit (nothing to do), else false
#   previous  the last release tag, or empty for the first release
#   version   e.g. 1.4.0        tag  e.g. v1.4.0
#   code      Android versionCode: MAJOR*1000000 + MINOR*1000 + PATCH
#   bump      initial | major | minor | patch
#
# Bump rules (BUMP=auto, the default), applied to every commit since the last tag, so it works for
# squash, merge and rebase merges alike:
#   major  a "type!:" subject (feat!: ...) or a "BREAKING CHANGE:" line
#   minor  a "feat:" / "feat(scope):" subject or first body line
#   patch  anything else
# BUMP=major|minor|patch overrides the rules. The first release (no tag yet) is 0.1.0.
set -euo pipefail

BUMP="${BUMP:-auto}"
case "$BUMP" in
  auto | major | minor | patch) ;;
  *) echo "invalid BUMP '$BUMP' (auto, major, minor or patch)" >&2; exit 2 ;;
esac

TAG_GLOB='v[0-9]*.[0-9]*.[0-9]*'
TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+$'

# Re-running a workflow on a commit that already carries a release tag must not cut another one.
if git describe --tags --exact-match --match "$TAG_GLOB" HEAD 2>/dev/null | grep -qE "$TAG_RE"; then
  echo "skip=true"
  exit 0
fi

previous="$(git tag --list "$TAG_GLOB" --sort=-v:refname | grep -E "$TAG_RE" | head -n1 || true)"

if [ -z "$previous" ]; then
  major=0 minor=1 patch=0
  bump="initial"
else
  IFS=. read -r major minor patch <<<"${previous#v}"
  range="$previous..HEAD"

  if [ "$BUMP" = auto ]; then
    messages="$(git log --format='%s%n%b' "$range")"
    if grep -qiE '^[a-z]+(\([^)]*\))?!:|^BREAKING[ -]CHANGE' <<<"$messages"; then
      bump="major"
    elif grep -qiE '^feat(\([^)]*\))?:' <<<"$messages"; then
      bump="minor"
    else
      bump="patch"
    fi
  else
    bump="$BUMP"
  fi

  case "$bump" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
  esac
fi

if [ "$minor" -ge 1000 ] || [ "$patch" -ge 1000 ]; then
  echo "minor/patch must stay below 1000 to fit the versionCode scheme" >&2
  exit 1
fi

version="$major.$minor.$patch"
echo "skip=false"
echo "previous=$previous"
echo "version=$version"
echo "tag=v$version"
echo "code=$((major * 1000000 + minor * 1000 + patch))"
echo "bump=$bump"
