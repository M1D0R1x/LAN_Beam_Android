#!/usr/bin/env bash
# Measures real LAN Beam throughput between this computer and the phone over your actual Wi-Fi.
#   scripts/lanbeam-bench.sh http://192.168.1.37:8765 [size_mb]
# Uploads a random file (resumable chunk API, same path the web page uses), downloads it back,
# does a resumed download from 96 %, and prints MB/s for each. Leaves one file in "Received".
set -euo pipefail
BASE="${1:?usage: $0 http://PHONE_IP:8765 [size_mb]}"
MB="${2:-256}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="$WORK/bench.bin"
python3 -c "import os,sys; f=open(sys.argv[1],'wb'); [f.write(os.urandom(1<<20)) for _ in range(int(sys.argv[2]))]" "$SRC" "$MB"
BYTES=$(wc -c < "$SRC" | tr -d ' ')
NAME="lanbeam-bench-$(date +%s).bin"

rate() { python3 -c "import sys; b,t=float(sys.argv[1]),float(sys.argv[2]); print(f'{b/1048576/t:.1f} MB/s ({t:.2f} s)')" "$1" "$2"; }

echo "Latency (20 x /api/ping):"
for _ in $(seq 1 20); do curl -s -o /dev/null -w '%{time_total}\n' "$BASE/api/ping"; done \
  | python3 -c "import sys; v=sorted(float(x)*1000 for x in sys.stdin); print(f'  median {v[len(v)//2]:.1f} ms, p90 {v[int(len(v)*.9)]:.1f} ms')"

echo "Upload ${MB} MB (single streaming PUT):"
T=$(curl -s -o /dev/null -H 'Expect:' -w '%{time_total}' -T "$SRC" "$BASE/api/upload/raw?name=$NAME")
echo "  $(rate "$BYTES" "$T")"

ENC=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote('uploads/'+sys.argv[1]))" "$NAME")
echo "Download ${MB} MB:"
T=$(curl -s -o "$WORK/back.bin" -w '%{time_total}' "$BASE/api/download?path=$ENC")
echo "  $(rate "$BYTES" "$T")"
cmp -s "$SRC" "$WORK/back.bin" && echo "  integrity: identical" || echo "  integrity: MISMATCH"

START=$((BYTES * 96 / 100))
echo "Resume download from 96 %:"
T=$(curl -s -o "$WORK/tail.bin" -m 30 -r "$START-" -w '%{time_total}' "$BASE/api/download?path=$ENC" || echo FAIL)
if [ "$T" = FAIL ]; then echo "  did not complete within 30 s"; else echo "  completed in ${T}s ($(wc -c < "$WORK/tail.bin" | tr -d ' ') of $((BYTES - START)) bytes)"; fi
