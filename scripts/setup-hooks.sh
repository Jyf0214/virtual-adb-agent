#!/usr/bin/env bash
#
# setup-hooks.sh — 配置 Git 使用项目 .githooks 目录
#
# 用法: bash scripts/setup-hooks.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "▸ 配置 Git hooks 路径..."
git config core.hooksPath "$PROJECT_ROOT/.githooks"

echo "▸ 验证 hook 文件..."
hooks=("pre-commit" "pre-push")
for hook in "${hooks[@]}"; do
    hook_path="$PROJECT_ROOT/.githooks/$hook"
    if [ -f "$hook_path" ]; then
        if [ -x "$hook_path" ]; then
            echo "  ✓ $hook 已就绪"
        else
            echo "  ✗ $hook 缺少执行权限"
            chmod +x "$hook_path"
            echo "  ✓ 已修复执行权限"
        fi
    else
        echo "  ⚠ $hook 不存在"
    fi
done

echo ""
echo "═══ Git hooks 配置完成 ═══"
echo "  路径: $(git config core.hooksPath)"
echo "  pre-commit : 提交前检查代码格式与敏感信息"
echo "  pre-push   : 推送前语法检查（括号匹配、XML 格式、Manifest 校验）"
