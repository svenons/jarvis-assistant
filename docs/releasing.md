# CI and releases

Two GitHub Actions workflows live in `.github/workflows/`:

| Workflow | Runs on | Does |
|---|---|---|
| Build (`build.yml`) | every pull request into `master` | Runs `./gradlew :app:assembleDebug`. Red means the PR doesn't compile. |
| Release (`release.yml`) | every push to `master`, or by hand | Picks the next version, builds the debug APK, tags it and publishes a GitHub Release with the APK and a SHA-256 file. |

Docs-only changes (`*.md`, `docs/`, `LICENSE`) don't cut a release.

## Setup

Enable Actions on the repo (forks start with it off). No secrets are needed: the release workflow uses the built-in `GITHUB_TOKEN`. To require the build check, add a branch protection rule for `master` and select "Build debug APK".

## The signing key

Releases are debug builds that install as `dk.foss.jarvis.debug` and are versioned like `1.2.3-debug`. Android refuses to update an app signed with a different key, and every CI runner would generate its own debug key. So all debug builds, local and CI, are signed with the committed `app/debug.keystore`: the standard Android debug key (`android` / `androiddebugkey`), copied from the maintainer's `~/.android/debug.keystore`.

- Each release installs over the previous one, and over a build from the maintainer's machine.
- The release workflow fails if the APK isn't signed with that key.
- The key is public. Anyone can sign an APK Android accepts as an update to this app, though you still have to install it. Never reuse it for anything real.

If a debug build signed with another key is installed (from `dist/`, say), uninstall it once first. That clears its settings and downloaded models.

## Versions

Versions are `vMAJOR.MINOR.PATCH` tags, starting at `v0.1.0`. `.github/scripts/next-version.sh` reads every commit since the last tag:

| If any commit has | Bump |
|---|---|
| a `type!:` subject (for example `feat!: new config format`) or a `BREAKING CHANGE:` line | major |
| a `feat:` or `feat(scope):` subject | minor |
| anything else | patch |

With squash merges the commit message is the PR title, so write titles like `feat: ...` or `fix: ...`. To force a bump, run the workflow by hand (Actions > Release > Run workflow). Re-running it on a released commit does nothing.

`versionCode` is `MAJOR*1000000 + MINOR*1000 + PATCH` (`1.4.2` gives `1004002`), because Android needs it to keep rising. Local builds default to `0.1.0` and `1` unless you pass `-PappVersionName=… -PappVersionCode=…`.

## Limits

The repo is public, so Actions is free. On a private repo the free plan has 2,000 minutes and 500 MB of artifact storage a month. The pipeline stores no artifacts (a debug APK is about 150 MB): the PR build uploads nothing and releases go straight to the Releases page, where assets are capped at 2 GiB each with no total limit. The actions are pinned to major versions; pin to commit SHAs for stricter supply-chain control.

## What's verified

Tested locally: the version script against throwaway git histories (each bump type, merge commits, re-runs, tag sorting, overrides), a `CI=true` build through the workflow's `aapt2` and `apksigner` checks, and a clean build with an empty Android key folder, which still signed with `app/debug.keystore`. The workflows pass `actionlint` and the scripts pass `shellcheck`.

The workflow hasn't run on GitHub yet. Watch the first run, especially the release-notes step (`gh api …/generate-notes`) and `gh release create`.
