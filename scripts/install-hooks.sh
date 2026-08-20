#!/usr/bin/env bash
#
# 安装仓库自带的 git hooks（让"禁止打 tag"等约定自动生效）。
# 用法：在仓库根目录执行 `bash scripts/install-hooks.sh`

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p .git/hooks

# pre-push: 拦截任何 refs/tags/* push
cp -f scripts/pre-push .git/hooks/pre-push
chmod +x .git/hooks/pre-push

echo "✅ 已部署 .git/hooks/pre-push："
echo "   - 本仓库禁止 push 任何 tag"
echo "   - 强行绕过: git push --no-verify"