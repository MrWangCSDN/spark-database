#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
mvn "$@" verify
jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar | grep -q '^BOOT-INF/classes/static/index.html$'
mkdir -p "$project_dir/target"
staging_dir="$(mktemp -d "$project_dir/target/tss-package.XXXXXX")"
# 只清理本次创建的临时目录，避免把旧解压目录中的现场配置打入新发布包。
trap 'rm -rf "$staging_dir"' EXIT
bundle="$staging_dir/tss-compare"
mkdir -p "$bundle"/{lib,jobs,drivers,config,bin}
cp compare-service/target/compare-service-1.0.0-SNAPSHOT.jar "$bundle/lib/"
cp compare-spark/target/compare-spark-1.0.0-SNAPSHOT.jar "$bundle/jobs/"
cp compare-spark/target/jdbc/*.jar "$bundle/drivers/"
cp config/application-local.properties.example "$bundle/config/"
cp config/application-local.properties.example "$bundle/config/application-local.properties"
# 仅打包占位模板，禁止把本机真实环境凭据带入发布包。
cp config/bd.env.example "$bundle/config/bd.env"
chmod 600 "$bundle/config/bd.env"
cp bin/start.sh bin/runtime.sh bin/check-driver.sh bin/CheckJdbcDriver.java "$bundle/bin/"
cp start.sh stop.sh "$bundle/"
cp -R sql "$bundle/"
cp README.md "$bundle/"
tar -czf "$staging_dir/tss-compare-1.0.0-SNAPSHOT.tar.gz" -C "$staging_dir" tss-compare
mv "$staging_dir/tss-compare-1.0.0-SNAPSHOT.tar.gz" target/
printf '发布包：%s/target/tss-compare-1.0.0-SNAPSHOT.tar.gz\n' "$project_dir"
