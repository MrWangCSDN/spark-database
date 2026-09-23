#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if ! git diff --quiet || ! git diff --cached --quiet; then
  printf '源码工作区存在未提交修改，请先提交后再生成源码包。\n' >&2
  exit 1
fi

version="${SOURCE_VERSION:-1.0.0-SNAPSHOT}"
root_name="tss-compare-source-${version}"
output_dir="$project_dir/target"
mkdir -p "$output_dir"
staging_dir="$(mktemp -d "$output_dir/source-package.XXXXXX")"
trap 'rm -rf "$staging_dir"' EXIT

mkdir -p "$staging_dir/$root_name"
git archive HEAD | tar -x -C "$staging_dir/$root_name"
git rev-parse HEAD > "$staging_dir/$root_name/SOURCE-COMMIT.txt"

required=(
  pom.xml
  compare-service/pom.xml
  compare-spark/pom.xml
  compare-ui/package.json
  compare-ui/package-lock.json
  compare-ui/src/main.tsx
  docs/migration/intranet-build.md
)
for file in "${required[@]}"; do
  test -f "$staging_dir/$root_name/$file" || {
    printf '源码包缺少必要文件：%s\n' "$file" >&2
    exit 1
  }
done

archive="$output_dir/${root_name}.zip"
rm -f "$archive" "$archive.sha256"
(cd "$staging_dir" && zip -qr "$archive" "$root_name")
(cd "$output_dir" && shasum -a 256 "$(basename "$archive")" > "$(basename "$archive").sha256")

printf '源码包：%s\n' "$archive"
printf '校验文件：%s.sha256\n' "$archive"
