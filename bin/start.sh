#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/runtime.sh"
bd_check
exec "$java_cmd" "-Dbd.home=$COMPARE_HOME" "-Dloader.path=$COMPARE_HOME/drivers" -jar "$COMPARE_HOME/lib/compare-service-1.0.0-SNAPSHOT.jar" "$@"
