#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/runtime.sh"
java_cmd="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
exec "$java_cmd" -cp "$COMPARE_HOME/drivers/*" "$COMPARE_HOME/bin/CheckJdbcDriver.java" "${TSS_METADATA_DRIVER:?请填写驱动类}" "${TSS_METADATA_URL:?请填写元数据库URL}"
