#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$repo_root/.gradle/compatibility/zio-telemetry"
fixture_ref=c50f756f6f020eb012633484926a00b284786edf
fixture_version=0.0.0-bridge-c50f756
scala_version="${1:-2.13.18}"
case "$scala_version" in
  2.13.18|3.3.8) ;;
  *) echo "Supported fixture Scala versions: 2.13.18, 3.3.8" >&2; exit 1 ;;
esac

# Never edit a developer's zio-telemetry checkout. Reuse only our exact prepared fixture.
if [[ ! -d "$fixture_dir/.git" ]]; then
  git clone --no-checkout https://github.com/zio/zio-telemetry.git "$fixture_dir"
  git -C "$fixture_dir" checkout --detach "$fixture_ref"
fi
test "$(git -C "$fixture_dir" rev-parse HEAD)" = "$fixture_ref"
if ! git -C "$fixture_dir" apply --reverse --check "$repo_root/compatibility/zio-telemetry-agent.patch" 2>/dev/null; then
  git -C "$fixture_dir" apply --check "$repo_root/compatibility/zio-telemetry-agent.patch"
  git -C "$fixture_dir" apply "$repo_root/compatibility/zio-telemetry-agent.patch"
fi
git -C "$fixture_dir" apply --reverse --check "$repo_root/compatibility/zio-telemetry-agent.patch"

cd "$fixture_dir"
sbt -batch \
  "++$scala_version!" \
  "set ThisBuild / version := \"$fixture_version\"" \
  'opentelemetryCore/publishM2' \
  'opentelemetryAgent/publishM2' \
  'opentelemetry/publishM2'
