#!/usr/bin/env bash
set -euo pipefail

app_home="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pid_file="$app_home/bd.pid"
timeout_seconds="${BD_STOP_TIMEOUT_SECONDS:-120}"

if [[ ! -f "$pid_file" ]]; then
  echo "未找到PID文件，服务可能没有启动：$pid_file" >&2
  exit 1
fi

service_pid="$(cat "$pid_file")"
if [[ ! "$service_pid" =~ ^[0-9]+$ ]]; then
  echo "PID文件内容无效：$pid_file" >&2
  exit 1
fi

if ! kill -0 "$service_pid" 2>/dev/null; then
  rm -f "$pid_file"
  echo "服务进程已经停止，已清理失效的PID文件。"
  exit 0
fi

echo "正在停止服务，PID=$service_pid"
kill "$service_pid"

deadline=$((SECONDS + timeout_seconds))
while kill -0 "$service_pid" 2>/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "等待 ${timeout_seconds} 秒后服务仍未退出，请检查Spark作业和日志；未执行强制终止。" >&2
    exit 1
  fi
  sleep 1
done

rm -f "$pid_file"
echo "服务已停止。"
