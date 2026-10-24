#!/bin/bash
# Start TDengine (taosd + taosAdapter) as the NORMAL user.
#
# IMPORTANT: the readiness check is "is 6030 LISTENING", not "does the process
# exist". A taosd that is shutting down still shows up in pgrep, which made an
# earlier version print "already running" and skip the start -- leaving
# taosAdapter alive with no server behind it (REST returns "Unable to
# establish connection"). Verified 2026-10-24.
set -u
cd "$HOME/tdengine" || exit 1
mkdir -p data log tmp

listening() { ss -lnt 2>/dev/null | grep -q ":$1 "; }

if listening 6030; then
  echo "  taosd already listening on 6030"
else
  if pgrep -x taosd >/dev/null; then
    echo "  taosd process exists but 6030 not open -> force restart"
    pkill -9 -x taosd
    sleep 2
  fi
  echo "  starting taosd ..."
  setsid nohup ./bin/taosd -c "$HOME/tdengine/cfg" > "$HOME/tdengine/log/taosd.stdout" 2>&1 &
  for i in $(seq 1 30); do listening 6030 && break; sleep 1; done
  if listening 6030; then
    echo "  OK: taosd listening on 6030"
  else
    echo "  FAIL: 6030 still not listening -- check log/taosd.stdout"
    exit 1
  fi
fi

if listening 6041; then
  echo "  taosAdapter already listening on 6041"
else
  echo "  starting taosAdapter ..."
  setsid nohup env LD_LIBRARY_PATH="$HOME/tdengine/lib" ./bin/taosadapter -c "$HOME/tdengine/cfg/taosadapter.toml" > "$HOME/tdengine/log/adapter.stdout" 2>&1 &
  for i in $(seq 1 25); do
    if curl -s -o /dev/null -u root:taosdata -d 'select 1' http://127.0.0.1:6041/rest/sql; then
      echo "  OK: taosAdapter ready on 6041"
      exit 0
    fi
    sleep 1
  done
  echo "  FAIL: 6041 not answering -- check log/adapter.stdout"
  exit 1
fi
exit 0
