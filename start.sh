#!/usr/bin/env bash
set -euo pipefail

app_home="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pid_file="$app_home/bd.pid"
log_file="$app_home/logs/bd-console.log"

mkdir -p "$app_home/logs"

if [[ -f "$pid_file" ]]; then
  existing_pid="$(cat "$pid_file")"
  if [[ "$existing_pid" =~ ^[0-9]+$ ]] && kill -0 "$existing_pid" 2>/dev/null; then
    echo "服务已经运行，PID=$existing_pid" >&2
    exit 1
  fi
  rm -f "$pid_file"
fi

nohup "$app_home/bin/start.sh" "$@" >> "$log_file" 2>&1 < /dev/null &
service_pid=$!
printf '%s\n' "$service_pid" > "$pid_file"

# 尽早发现缺少JAR、JDK或配置文件等启动错误。
sleep 2
if ! kill -0 "$service_pid" 2>/dev/null; then
  rm -f "$pid_file"
  echo "服务启动失败，请检查日志：$log_file" >&2
  tail -n 50 "$log_file" >&2 || true
  exit 1
fi

echo "服务启动中，PID=$service_pid"
echo "日志：$log_file"
