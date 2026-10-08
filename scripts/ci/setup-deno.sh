#!/usr/bin/env bash
set -Eeuo pipefail

# Keep the CI runtime exact without resolving a release range over the network.
deno_version=2.9.7
archive_sha256=c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]]
: "${RUNNER_TEMP:?}" "${GITHUB_PATH:?}"
tool_cache="${RUNNER_TOOL_CACHE:-$RUNNER_TEMP/tools}"
install_dir="$tool_cache/now-deno/$deno_version/x64"

is_expected_deno() {
  [[ -x "$1" ]] || return 1
  local actual
  actual="$(timeout 10 "$1" --version)" || return 1
  [[ "${actual%%$'\n'*}" == "deno $deno_version (stable, release, x86_64-unknown-linux-gnu)" ]]
}

publish_deno() {
  printf '%s\n' "$(dirname "$1")" >> "$GITHUB_PATH"
  "$1" --version
}

for candidate in "$(command -v deno || true)" "$tool_cache/deno/$deno_version/x64/deno" "$HOME/.deno/bin/deno" "$install_dir/deno"; do
  if is_expected_deno "$candidate"; then
    publish_deno "$candidate"
    exit 0
  fi
done

mkdir -p "$install_dir"
exec 9>"$install_dir/install.lock"
flock -w 60 9
if is_expected_deno "$install_dir/deno"; then
  publish_deno "$install_dir/deno"
  exit 0
fi

download_dir="$(mktemp -d "$RUNNER_TEMP/now-deno.XXXXXX")"
trap 'rm -rf -- "$download_dir"' EXIT
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
  --connect-timeout 10 --max-time 30 --retry 1 --retry-max-time 60 \
  --output "$download_dir/deno.zip" \
  "https://github.com/denoland/deno/releases/download/v$deno_version/deno-x86_64-unknown-linux-gnu.zip"
printf '%s  %s\n' "$archive_sha256" "$download_dir/deno.zip" | sha256sum --check --status
unzip -q "$download_dir/deno.zip" -d "$download_dir"
chmod 0755 "$download_dir/deno"
is_expected_deno "$download_dir/deno"
install -m 0755 "$download_dir/deno" "$install_dir/deno.next"
mv -f "$install_dir/deno.next" "$install_dir/deno"
publish_deno "$install_dir/deno"
