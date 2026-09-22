#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bin/runtime.sh"
bd_lock
wait_seconds="${BD_START_WAIT_SECONDS:-30}"
bd_positive BD_START_WAIT_SECONDS "$wait_seconds"
if [[ -f "$pid_file" ]]; then
  existing_pid="$(cat "$pid_file")"
  if bd_alive "$existing_pid"; then
    if bd_owned "$existing_pid"; then echo "服务已在运行，PID=$existing_pid"; exit 0; fi
    echo 'PID 对应其他进程，拒绝覆盖；请核对 bd.pid。' >&2; exit 1
  fi
  rm -f "$pid_file"
fi
bd_check
mkdir -p "$COMPARE_HOME/logs"
if [[ -f "$log_file" ]]; then mv "$log_file" "$COMPARE_HOME/logs/bd-console-$(date +%Y%m%d-%H%M%S)-$$.log"; fi
nohup "$java_cmd" "-Dbd.home=$COMPARE_HOME" "-Dloader.path=$COMPARE_HOME/drivers" -jar "$COMPARE_HOME/lib/compare-service-1.0.0-SNAPSHOT.jar" "$@" >> "$log_file" 2>&1 < /dev/null &
service_pid=$!
printf '%s\n' "$service_pid" > "$pid_file"
for ((i=0; i<wait_seconds; i++)); do
  if ! bd_alive "$service_pid"; then
    rm -f "$pid_file"
    echo "启动失败，请查看 $log_file" >&2; exit 1
  fi
  if grep -F 'Started CompareApplication' "$log_file" >/dev/null; then
    echo "服务已输出启动完成日志，PID=$service_pid，端口=${SERVER_PORT:-8899}"
    echo "日志：$log_file"; exit 0
  fi
  sleep 1
done
echo "进程 PID=$service_pid 仍运行，但 ${wait_seconds} 秒内未发现启动完成日志；请检查 $log_file。" >&2
exit 1
