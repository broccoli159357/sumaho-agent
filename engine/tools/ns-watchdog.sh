cd /data/local/tmp/hextest
export LD_LIBRARY_PATH=/data/local/tmp/hextest:/system/lib64:/vendor/lib64 ADSP_LIBRARY_PATH=/data/local/tmp/hextest GGML_HEXAGON_HOSTBUF=0
memavail() { sed -n 's/^MemAvailable:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo; }
P="The history of computing began with mechanical calculators and evolved through vacuum tubes, transistors and integrated circuits. Today, small devices like phones can run large language models that answer questions, write code and plan tasks."
NODESTATS_OUT=$OUTF ./llama-node-stats -m /data/local/tmp/bmoe/npu.gguf -p "$P" -c 256 -b 64 -ub 64 -t 8 -ctk q8_0 -ctv q8_0 -fa on "$@" > ns.log 2>&1 &
PID=$!
T0=$(date +%s)
while kill -0 $PID 2>/dev/null; do
  M=$(memavail); RSS=$(sed -n 's/^RssAnon:[[:space:]]*\([0-9]*\).*/\1/p' /proc/$PID/status 2>/dev/null)
  if [ "${M:-9999999}" -lt 1572864 ] || [ "${RSS:-0}" -gt ${RSSMAX:-6291456} ]; then kill -9 $PID; echo "WATCHDOG killed memavail=$((M/1024)) rss=$((${RSS:-0}/1024))"; break; fi
  if grep -q "synchronize failed" ns.log; then sleep 2; kill -9 $PID; echo "WATCHDOG killed: DSP error"; break; fi
  if [ $(( $(date +%s) - T0 )) -gt ${TMAX:-1800} ]; then kill -9 $PID; echo "WATCHDOG killed: timeout"; break; fi
  sleep 0.5
done
wait $PID; echo "exit=$?"
grep -E "tokens =|wrote|error|failed" ns.log | head -5; wc -l $OUTF
pkill -9 -x llama-node-stats 2>/dev/null; true
