# CI and releases

Two GitHub Actions workflows live in `.github/workflows/`:

| Workflow | Runs on | Does |
|---|---|---|
| Build (`build.yml`) | every pull request into `master` | Runs `./gradlew :app:assembleDebug`. Red means the PR doesn't compile. Also uploads a preview APK as an artifact, deleted after 1 day. |
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

`versionCode` is `(MAJOR*1000000 + MINOR*1000 + PATCH) * 100` (`1.4.2` gives `100400200`), because Android needs it to keep rising and only compares that number, never the version name. The last two digits are a slot: `00` for a release, `01`..`99` for a PR preview. The `* 100` is what leaves room between two consecutive releases. Local builds default to `0.1.0` and `1` unless you pass `-PappVersionName=… -PappVersionCode=…`, and Android refuses to install that over a release ("App not installed") because it looks like a downgrade.

## Preview builds

Every pull request build uploads `jarvis-<next version>-pr<PR number>.<slot>-debug.apk` as a workflow artifact (Actions > the run > Artifacts), kept for 1 day, the minimum GitHub allows. Downloading an artifact needs a GitHub login, and the download is a zip around the APK.

- The version name is the version the PR would release, for example `0.2.0-pr5.3`. The build is signed with the same debug key as releases.
- The version code is the last release's code plus the number of commits since that release, capped at 99. That is above the last release and below any next one (a patch bump adds at least 100), so a preview installs as an update over the last release, over an earlier preview of the same release cycle, and the next real release installs over it.
- Two PRs from the same base can get codes that don't order the way you built them. Installing the one with the lower code over the other is then refused as a downgrade; use `adb install -r -d`, or uninstall (which clears settings and downloaded models).
- Past 99 commits since a release, previews stop rising and share a code; they still reinstall over each other.

## Limits

The repo is public, so Actions is free. On a private repo the free plan has 2,000 minutes and 500 MB of artifact storage a month. A debug APK is about 150 MB, so the only artifact the pipeline stores is the PR preview, for 1 day, and a newer push to the same PR cancels the run before it. Releases go straight to the Releases page, where assets are capped at 2 GiB each with no total limit. The actions are pinned to major versions; pin to commit SHAs for stricter supply-chain control.

## What's verified

Tested locally: the version script against throwaway git histories (each bump type, merge commits, re-runs, tag sorting, overrides), a `CI=true` build through the workflow's `aapt2` and `apksigner` checks, and a clean build with an empty Android key folder, which still signed with `app/debug.keystore`. The workflows pass `actionlint` and the scripts pass `shellcheck`.

The workflow hasn't run on GitHub yet. Watch the first run, especially the release-notes step (`gh api …/generate-notes`) and `gh release create`.
