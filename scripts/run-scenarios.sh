#!/bin/bash
# 故障剧本评测一键跑批（计划书第 8 章对照基线的 mock 形态）
# 前置：sentry-agent 在 8084 运行（mock 模式零依赖）；dev-api.enabled=true（默认）
B=${1:-http://localhost:8084}
echo "== 故障剧本评测跑批 =="
curl -s --max-time 120 "$B/api/dev/eval/scenarios" \
  | python -c "
import json,sys
d=json.load(sys.stdin)
print('Summary:', d['summary'])
print('Elapsed:', d['elapsedMs'], 'ms')
print()
for r in d['results']:
    print(('PASS' if r['pass'] else 'FAIL'), r['id'],
          '| outcome=' + r['finalOutcome'], '| tokens=' + str(r['tokens']),
          '|', r['detail'] if not r['pass'] else '')
" 2>/dev/null || curl -s --max-time 120 "$B/api/dev/eval/scenarios"
