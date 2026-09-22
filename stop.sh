#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bin/runtime.sh"
bd_lock
timeout_seconds="${BD_STOP_TIMEOUT_SECONDS:-120}"
bd_positive BD_STOP_TIMEOUT_SECONDS "$timeout_seconds"
[[ -f "$pid_file" ]] || { echo '服务未运行（没有 bd.pid）。'; exit 0; }
service_pid="$(cat "$pid_file")"
[[ "$service_pid" =~ ^[0-9]+$ ]] && (( service_pid > 1 )) || { echo 'bd.pid 内容无效，未发送停止信号。' >&2; exit 1; }
if ! bd_alive "$service_pid"; then rm -f "$pid_file"; echo '服务已停止，已清理失效 PID。'; exit 0; fi
bd_owned "$service_pid" || { echo 'PID 不是本目录启动的 bd 进程，拒绝停止；请人工核对旧版进程。' >&2; exit 1; }
echo "正在停止服务，PID=$service_pid"
kill -TERM "$service_pid"
deadline=$((SECONDS + timeout_seconds))
while bd_alive "$service_pid"; do
  if (( SECONDS >= deadline )); then
    echo "等待 ${timeout_seconds} 秒后仍未退出；保留 PID，请检查在途 Spark 作业和日志。" >&2; exit 1
  fi
  sleep 1
done
rm -f "$pid_file"
echo '服务已停止。'
