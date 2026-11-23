#!/usr/bin/env bash
# Echo 本地测试入口：Gradle 锁重试 + JVM 单测 + mock SSE 冒烟。
# 用法: scripts/run_tests.sh [--smoke]
set -o pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SMOKE=0
if [ $# -gt 0 ] && [ "$1" = "--smoke" ]; then SMOKE=1; fi

log() { echo "[run_tests] $*"; }
fail() { echo "[run_tests][失败] $*" >&2; exit 1; }

if [ -z "$JAVA_HOME" ]; then
  for c in /d/jdk-17.0.20.1+1 /usr/lib/jvm/temurin-17-jdk-amd64; do
    if [ -d "$c" ]; then JAVA_HOME="$c"; break; fi
  done
fi
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] || fail "未找到 JDK 17，请设置 JAVA_HOME"
export JAVA_HOME
log "JAVA_HOME=$JAVA_HOME"

if [ -x "$ROOT/gradlew" ] && [ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
  GRADLE_CMD="$ROOT/gradlew"
else
  GRADLE_CMD="$(command -v gradle || true)"
  if [ -z "$GRADLE_CMD" ] && [ -x /d/gradle/gradle-8.9/bin/gradle ]; then GRADLE_CMD=/d/gradle/gradle-8.9/bin/gradle; fi
  [ -n "$GRADLE_CMD" ] || fail "未找到 Gradle 8.9（仓库无 wrapper，请安装 gradle 或在 PATH 提供）"
fi
log "Gradle=$GRADLE_CMD"

gradle_retry() {
  attempt=1
  max=6
  while [ $attempt -le $max ]; do
    out=$("$@" 2>&1)
    rc=$?
    echo "$out"
    if [ $rc -eq 0 ]; then return 0; fi
    if echo "$out" | grep -qiE 'Timeout waiting to lock|Could not open|waiting to acquire'; then
      log "Gradle 缓存被占用（第 $attempt/$max 次），5 秒后重试"
      attempt=$((attempt + 1))
      sleep 5
      continue
    fi
    return $rc
  done
  return 1
}

cd "$ROOT" || fail "无法进入 $ROOT"

log "步骤 1/3: :app:compileDebugKotlin"
gradle_retry "$GRADLE_CMD" :app:compileDebugKotlin --console=plain --no-daemon || fail "Kotlin 编译失败"
log "步骤 2/3: :app:testDebugUnitTest"
gradle_retry "$GRADLE_CMD" :app:testDebugUnitTest --console=plain --no-daemon || fail "JVM 单测失败（报告: app/build/reports/tests）"
log "步骤 3/3: mock SSE selftest"
python3 "$HERE/mock_sse_server.py" --selftest || fail "mock SSE selftest 失败"

if [ "$SMOKE" = "1" ]; then
  PORT=18080
  python3 "$HERE/mock_sse_server.py" --port "$PORT" >/dev/null 2>&1 &
  SERVER_PID=$!
  trap 'kill $SERVER_PID 2>/dev/null' EXIT
  i=0
  while [ $i -lt 50 ]; do
    if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then break; fi
    i=$((i + 1))
    sleep 0.1
  done
  normal=$(curl -fsS -X POST "http://127.0.0.1:$PORT/anthropic/v1/messages?mode=normal" -d '{}')
  echo "$normal" | grep -q 'message_stop' || fail "normal 模式缺少 message_stop"
  trunc=$(curl -fsS -X POST "http://127.0.0.1:$PORT/anthropic/v1/messages?mode=truncated" -d '{}')
  if echo "$trunc" | grep -q 'message_stop'; then fail "truncated 模式不应出现 message_stop"; fi
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/anthropic/v1/messages?mode=429" -d '{}')
  [ "$code" = "429" ] || fail "429 模式返回 $code"
  log "冒烟通过: normal 有 message_stop / truncated 无 / 429 状态码正确"
fi

log "全部通过。单测报告: app/build/reports/tests/debugUnitTest/index.html"
