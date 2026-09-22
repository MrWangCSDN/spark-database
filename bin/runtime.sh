#!/usr/bin/env bash
# Shared by foreground/background start and stop. Never echo credentials.
COMPARE_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COMPARE_HOME
cd "$COMPARE_HOME"
if [[ -f config/bd.env ]]; then
  if LC_ALL=C grep $'\r' config/bd.env >/dev/null; then
    echo 'config/bd.env 含 Windows CRLF，请先转换为 Unix LF。' >&2
    exit 1
  fi
  set -a
  source config/bd.env
  set +a
fi
pid_file="$COMPARE_HOME/bd.pid"
log_file="$COMPARE_HOME/logs/bd-console.log"

bd_alive() {
  [[ "$1" =~ ^[0-9]+$ ]] && (( $1 > 1 )) || return 1
  local state
  state="$(ps -p "$1" -o stat= 2>/dev/null)" || return 1
  [[ -n "$state" && "$state" != *Z* ]]
}
bd_owned() {
  local command
  command="$(ps -ww -p "$1" -o args= 2>/dev/null)" || return 1
  [[ "$command" == *"-Dbd.home=$COMPARE_HOME "* && "$command" == *"$COMPARE_HOME/lib/compare-service-1.0.0-SNAPSHOT.jar"* ]]
}
bd_lock() {
  if ! mkdir "$COMPARE_HOME/.bd-control.lock" 2>/dev/null; then
    echo '已有启停操作进行中；若上次操作被强制中断，确认后删除 .bd-control.lock 空目录。' >&2
    exit 1
  fi
  trap 'rmdir "$COMPARE_HOME/.bd-control.lock" 2>/dev/null || true' EXIT
}
bd_positive() {
  [[ "$2" =~ ^[1-9][0-9]*$ ]] || { echo "$1 必须为正整数。" >&2; exit 1; }
}
bd_check() {
  local path driver_class driver_found=0
  for path in lib/compare-service-1.0.0-SNAPSHOT.jar jobs/compare-spark-1.0.0-SNAPSHOT.jar config/application-local.properties; do
    [[ -f "$path" ]] || { echo "缺少文件：$COMPARE_HOME/$path" >&2; return 1; }
  done
  java_cmd="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
  jar_cmd="${JAVA_HOME:+${JAVA_HOME}/bin/}jar"
  "$java_cmd" -version 2>&1 | grep -E 'version "17([.\"]|$)' >/dev/null || {
    echo '请通过 JAVA_HOME 配置可运行的 JDK 17。' >&2; return 1;
  }
  [[ -z "${SPARK_HOME:-}" || -x "$SPARK_HOME/bin/spark-submit" ]] || {
    echo 'SPARK_HOME 下没有可执行的 bin/spark-submit。' >&2; return 1;
  }
  if [[ "${TSS_METADATA_PASSWORD:-}" == CHANGE_ME* || "${TSS_METADATA_URL:-}" == *CHANGE_ME* ]]; then
    echo '请先填写 config/bd.env 的数据库配置占位值。' >&2; return 1
  fi
  if [[ "${TSS_METADATA_DRIVER:-}" == org.opengauss.Driver && "${TSS_METADATA_URL:-}" != jdbc:opengauss:* ]]; then
    echo 'org.opengauss.Driver 请配套使用 jdbc:opengauss: URL。' >&2; return 1
  fi
  if [[ -n "${BD_JDBC_JAR:-}" && ! -f "$BD_JDBC_JAR" ]]; then
    echo 'BD_JDBC_JAR 指定的驱动文件不存在。' >&2; return 1
  fi
  driver_class="${TSS_METADATA_DRIVER:-}"
  driver_class="${driver_class//.//}.class"
  for path in "$COMPARE_HOME"/drivers/*.jar; do
    [[ -f "$path" ]] || continue
    if "$jar_cmd" tf "$path" | grep -Fx "$driver_class" >/dev/null; then driver_found=1; fi
  done
  if [[ -n "${TSS_METADATA_DRIVER:-}" && "$driver_found" != 1 ]]; then
    echo "drivers/ 中找不到驱动类 ${TSS_METADATA_DRIVER}，请核对厂商 JAR。" >&2; return 1
  fi
  if [[ -z "${COMPARE_SPARK_JARS:-}" ]]; then
    COMPARE_SPARK_JARS=""
    for path in "$COMPARE_HOME"/drivers/*.jar; do
      [[ -f "$path" ]] || continue
      COMPARE_SPARK_JARS="${COMPARE_SPARK_JARS:+${COMPARE_SPARK_JARS},}${path}"
    done
    export COMPARE_SPARK_JARS
  fi
}
