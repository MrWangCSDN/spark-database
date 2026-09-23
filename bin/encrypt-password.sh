#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_file="$project_dir/lib/compare-service-1.0.0-SNAPSHOT.jar"
if [[ ! -f "$jar_file" ]]; then
  echo "缺少服务 JAR：$jar_file" >&2
  exit 1
fi
if [[ -z "${JASYPT_ENCRYPTOR_PASSWORD:-}" ]]; then
  echo '请先从安全的外部环境注入 JASYPT_ENCRYPTOR_PASSWORD。' >&2
  exit 1
fi
read -r -s -p '请输入元数据库明文密码：' plain_password
printf '\n' >&2
if [[ -z "$plain_password" ]]; then
  echo '数据库密码不能为空。' >&2
  exit 1
fi
printf '%s' "$plain_password" | "${JAVA_HOME:+${JAVA_HOME}/bin/}java" -jar "$jar_file" --encrypt-password
unset plain_password
