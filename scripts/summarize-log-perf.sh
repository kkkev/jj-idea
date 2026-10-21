#!/usr/bin/env bash
# Summarises the paged log's perf lines in an idea.log, for tuning PageSizePolicy (jj-idea-2570.9).
# Usage: scripts/summarize-log-perf.sh path/to/idea.log
# Run one IDE session per setting (-Djj.log.pageTargetMs=... -Djj.log.pageMaxRows=...), scroll the same
# distance, then compare: fewer log-apply lines with a lower max/p95 is better.
set -euo pipefail
log=${1:?usage: $0 idea.log}

stat() { # name, regex for "took Nms", label
  { grep -E "$2" "$log" || true; } | sed -E 's/.*took ([0-9]+)ms.*/\1/' | sort -n | awk -v n="$1" '
    { v[NR] = $1; s += $1 }
    END { if (NR == 0) { print n ": none"; exit }
          printf "%-16s count=%-5d total=%-8d p50=%-6d p95=%-6d max=%d (ms)\n", n, NR, s, v[int((NR+1)/2)], v[int(NR*0.95 + 0.5)], v[NR] }'
}

stat log-page      'perf: log-page took'
stat log-apply     'perf: log-apply took'
stat log-edt-wait  'perf: log-edt-wait took'
echo "last page limits: $({ grep -E 'perf: log-page took' "$log" || true; } | sed -E 's/.*limit=([0-9,]+).*/\1/' | tail -8 | tr '\n' ' ')"
