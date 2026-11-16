#!/bin/bash
# usage: run.sh <log> <files-or-dir> [extra e2e args]; writes <log> and appends EXIT=<code>
export PATH=/Users/alexander.borohov/.nvm/versions/node/v24.18.0/bin:$PATH
export NPM_CONFIG_USERCONFIG=/Users/alexander.borohov/.kiro/settings/e2e.npmrc
cd /Users/alexander.borohov/projects/loaders/foremen/foremen-qa-auto/e2e || exit 1
LOG="$1"; shift
npx e2e run "$@" --output .e2e-for11-01 --action-timeout 5000 --retries 0 > "$LOG" 2>&1
echo "EXIT=$?" >> "$LOG"
