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
git archive HEAD pom.xml compare-service compare-spark bin config sql README.md docs start.sh stop.sh tests \
  | tar -x -C "$staging_dir/$root_name"
git rev-parse HEAD > "$staging_dir/$root_name/SOURCE-COMMIT.txt"

# Put the built frontend inside backend resources, so the source ZIP needs no UI project.
test -s compare-ui/dist/index.html || {
  printf '缺少已构建前端：请先在外网执行 mvn verify，再生成源码包。\n' >&2
  exit 1
}
static_dir="$staging_dir/$root_name/compare-service/src/main/resources/static"
mkdir -p "$static_dir"
cp -R compare-ui/dist/. "$static_dir/"

required=(
  pom.xml
  compare-service/pom.xml
  compare-spark/pom.xml
  compare-service/src/main/resources/static/index.html
  docs/migration/intranet-build.md
  sql/add_bcomp_max_partition_num_new.sql
  sql/add_bcomp_max_partition_num_gdb.sql
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
