#!/usr/bin/env bash
set -euo pipefail

NEW_VERSION="$1"

sed -i "s/version = \"[^\"]*\"/version = \"${NEW_VERSION}\"/" build.gradle.kts

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add build.gradle.kts

# If the version is already NEW_VERSION (e.g. cutting a release that doesn't need a version
# change), there's nothing to commit - skip it rather than creating an empty commit. An empty
# commit here previously caused two real problems: build.yml's paths-ignore filter silently
# drops push events with zero changed files, so the commit never triggered anything, and this
# script now runs inline within the same job that builds/publishes rather than relying on a
# second triggered run anyway - the commit is just a source-of-truth record, not a trigger.
if git diff --cached --quiet; then
  echo "Version already ${NEW_VERSION}, nothing to commit."
  exit 0
fi

git commit -q -m "chore: bump version to ${NEW_VERSION} [ci-bump]"

# actions/checkout leaves a persistent `http.https://github.com/.extraheader` Authorization
# header (its own GITHUB_TOKEN) in the local git config for the whole job, and that header
# applies to ANY https://github.com/... URL regardless of embedded userinfo, silently
# overriding the PAT in the URL below. `-c http...extraheader=` clears it for just this
# invocation so the PAT is what actually authenticates.
#
# Pushed straight to main regardless of which branch triggered this run - not required for the
# release itself (build/publish happens in the same job, doesn't depend on this push landing or
# retriggering anything), just keeps main's version string in sync. Non-fast-forward failures
# (e.g. triggered from a branch whose shallow single-commit checkout can't prove ancestry against
# main) are logged, not fatal.
REMOTE="https://x-access-token:${VORD_PUSH_TOKEN}@github.com/RATR2/vord.git"
git -c http.https://github.com/.extraheader= push "${REMOTE}" HEAD:main \
  || echo "::warning::Could not push version bump to main (non-fast-forward or unrelated history) - release build continues anyway."
