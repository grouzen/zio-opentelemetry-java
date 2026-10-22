#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
results="$repo_root/.gradle/compatibility/results"
mkdir -p "$results"

bash scripts/prepare-zio-telemetry.sh 2.13.18 > "$results/prepare-2.13.log" 2>&1
bash scripts/prepare-zio-telemetry.sh 3.3.8 > "$results/prepare-3.log" 2>&1
failed=0
for scala in 2.13 3; do
  for zio in 2.1.25 2.1.27; do
    for jdk in 11 17 21; do
      lane="jdk${jdk}-scala${scala}-zio${zio}"
      # Keep each run's XML separate; never mix it with an earlier failed attempt.
      rm -rf "$results/$lane"
      if [[ "$jdk" == 11 && "$zio" == 2.1.27 ]]; then
        echo "SKIP $lane: published ZIO 2.1.27 requires JDK 17" | tee "$results/$lane.log"
        continue
      fi
      echo "Running $lane"
      if gradle clean build spotlessCheck --no-build-cache --no-daemon --console=plain \
        "-PscalaVersion=$scala" "-PzioVersion=$zio" "-PtestJavaVersion=$jdk" \
        > "$results/$lane.log" 2>&1; then
        echo "PASS $lane"
      else
        echo "FAIL $lane (see $results/$lane.log)"
        failed=1
      fi
      mkdir -p "$results/$lane"
      for module in instrumentation/zio-2.1 instrumentation/zio-telemetry-agent-4.0 smoke-tests; do
        if [[ -d "$module/build/test-results" ]]; then
          cp -R "$module/build/test-results" "$results/$lane/$(basename "$module")"
        fi
      done
    done
  done
done
exit "$failed"
