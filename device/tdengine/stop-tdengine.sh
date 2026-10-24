#!/bin/bash
# Stop TDengine. Data stays in ~/tdengine/data.
# Waits until the process is REALLY gone (a dying taosd still matches pgrep,
# which previously fooled the start script).
pkill -x taosadapter 2>/dev/null
sleep 1
pkill -x taosd 2>/dev/null
for i in $(seq 1 10); do
  pgrep -x taosd >/dev/null || break
  sleep 1
done
if pgrep -x taosd >/dev/null; then
  echo "  taosd still alive -> force kill"
  pkill -9 -x taosd
  sleep 2
fi
echo "  taosd running: $(pgrep -c -x taosd || true)"
echo "  taosAdapter running: $(pgrep -c -x taosadapter || true)"
