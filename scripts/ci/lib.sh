# Shared helpers for the build-and-publish CI scripts.

read_version() {
  grep -oE 'version = "[^"]*"' build.gradle.kts | head -1 | cut -d'"' -f2
}

build_apk() {
  ./gradlew assembleRelease --console=plain
}

# Publishes the Manager APK as a GitHub Release on this repo. Unlike nilum, there's exactly one
# artifact per version here, so it goes straight to a release rather than a separate mirror repo
# (nilum's nilum-builds exists to keep noisy per-commit game-server jars out of the main repo's
# history, which doesn't apply to a single small APK per version).
publish_release() {
  local version="$1" sha="$2"
  local apk="manager/build/outputs/apk/release/manager-release.apk"
  local renamed="manager/build/outputs/apk/release/vord-${version}.apk"

  cp "${apk}" "${renamed}"

  gh release create "v${version}" "${renamed}" \
    --repo RATR2/vord \
    --target "${sha}" \
    --title "Vord ${version}" \
    --notes "Vord Manager ${version}."
}
