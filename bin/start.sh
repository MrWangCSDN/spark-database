#!/usr/bin/env bash
set -euo pipefail
COMPARE_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COMPARE_HOME
cd "$COMPARE_HOME"
if [[ ! -f lib/compare-service-1.0.0-SNAPSHOT.jar ]]; then
  echo '请先运行 bin/package.sh，解压 target/tss-compare-1.0.0-SNAPSHOT.tar.gz 后使用包内 bin/start.sh 启动。' >&2
  exit 1
fi
# 外部 GaussDB / OceanBase 等驱动供服务和 Spark 共用；不改变原表中的驱动类名。
if [[ -z "${COMPARE_SPARK_JARS:-}" ]]; then
  COMPARE_SPARK_JARS=""
  for driver in "$COMPARE_HOME"/drivers/*.jar; do
    [[ -f "$driver" ]] || continue
    COMPARE_SPARK_JARS="${COMPARE_SPARK_JARS:+${COMPARE_SPARK_JARS},}${driver}"
  done
  export COMPARE_SPARK_JARS
fi
java_cmd="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
# JVM参数可通过标准 JAVA_TOOL_OPTIONS 设置，避免shell重新解释参数内容。
exec "$java_cmd" -Dloader.path="$COMPARE_HOME/drivers" -jar "$COMPARE_HOME/lib/compare-service-1.0.0-SNAPSHOT.jar" "$@"
