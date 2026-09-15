#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 tools/demo_smtp.py &
smtp_pid=$!
trap 'kill "$smtp_pid" 2>/dev/null || true' EXIT
echo 'Open http://127.0.0.1:8081 after the application starts.'
java -jar run/mail-dispatch-1.0.0.jar
