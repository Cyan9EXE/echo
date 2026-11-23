#!/usr/bin/env python3
"""Mock Anthropic-compatible SSE server for Echo (app-native).

Stdlib only, no third-party packages. Point a debug build at it with
-PapiOrigin=http://127.0.0.1:18080; it is also used by scripts/run_tests.sh
and CI as a protocol smoke test.

Modes (query string ?mode= or the last path segment):

  normal     complete stream: message_start -> text deltas -> message_delta -> message_stop
  truncated  same prefix, connection closes BEFORE message_stop (tests retry logic)
  error_sse  one SSE error frame (overloaded_error) instead of a result
  429        plain JSON rate-limit body, HTTP 429
  500        plain JSON server-error body, HTTP 500

Usage::

    python3 scripts/mock_sse_server.py --port 18080
    curl -N -X POST 'http://127.0.0.1:18080/anthropic/v1/messages?mode=normal'
    python3 scripts/mock_sse_server.py --selftest
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODES = ("normal", "truncated", "error_sse", "429", "500")

USAGE_START = {
    "input_tokens": 42,
    "cache_read_input_tokens": 40,
    "cache_creation_input_tokens": 0,
    "output_tokens": 1,
}
USAGE_FINAL = {
    "input_tokens": 42,
    "cache_read_input_tokens": 40,
    "cache_creation_input_tokens": 0,
    "output_tokens": 15,
}

TEXT = "你好，这是 Echo 的 mock SSE 流。"


def frame(event, payload):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return ("event: " + event + "\ndata: " + body + "\n\n").encode("utf-8")


def _text_delta(text):
    return {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": text}}


def normal_frames():
    return [
        (
            "message_start",
            {
                "type": "message_start",
                "message": {
                    "id": "msg_mock_0001",
                    "type": "message",
                    "role": "assistant",
                    "model": "deepseek-flash",
                    "content": [],
                    "usage": USAGE_START,
                },
            },
        ),
        ("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
        ("content_block_delta", _text_delta(TEXT[:5])),
        ("content_block_delta", _text_delta(TEXT[5:])),
        ("content_block_stop", {"type": "content_block_stop", "index": 0}),
        ("ping", {"type": "ping"}),
        (
            "message_delta",
            {
                "type": "message_delta",
                "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                "usage": USAGE_FINAL,
            },
        ),
        ("message_stop", {"type": "message_stop"}),
    ]


def truncated_frames():
    # Cut the stream before message_delta/message_stop: the client must notice
    # the missing terminator and offer retry instead of treating it as done.
    return normal_frames()[:5]


def error_sse_frame():
    return [
        (
            "error",
            {
                "type": "error",
                "error": {"type": "overloaded_error", "message": "服务器繁忙，请稍后重试"},
            },
        )
    ]


ERROR_BODIES = {
    "429": {"error": {"type": "rate_limit_error", "message": "Too Many Requests"}},
    "500": {"error": {"type": "server_error", "message": "Internal Server Error"}},
}


class MockHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    frame_delay = 0.02

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock-sse] " + (fmt % args) + "\n")

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        if path in ("", "/health"):
            self._send_json(200, {"status": "ok", "modes": list(MODES)})
        else:
            self._send_json(404, {"error": {"type": "not_found", "message": "unknown path " + path}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)  # drain request body; content is irrelevant here
        mode = self._mode()
        if mode in ERROR_BODIES:
            self._send_json(int(mode), ERROR_BODIES[mode])
            return
        self._send_sse(mode)

    def _mode(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        for candidate in query.get("mode", []):
            if candidate in MODES:
                return candidate
        last = parsed.path.rstrip("/").rsplit("/", 1)[-1]
        return last if last in MODES else self.server.default_mode

    def _send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _send_sse(self, mode):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Mock-Mode", mode)
        self.send_header("Connection", "close")
        self.end_headers()
        if mode == "truncated":
            frames = truncated_frames()
        elif mode == "error_sse":
            frames = error_sse_frame()
        else:
            frames = normal_frames()
        try:
            for event, payload in frames:
                self.wfile.write(frame(event, payload))
                self.wfile.flush()
                if self.frame_delay:
                    time.sleep(self.frame_delay)
        except (BrokenPipeError, ConnectionResetError):
            pass
        self.close_connection = True


class MockServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, default_mode="normal"):
        self.default_mode = default_mode
        super().__init__(address, MockHandler)


def _post(base, path):
    req = urllib.request.Request(
        base + path,
        data=b'{"model":"deepseek-flash","stream":true}',
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8")


def selftest():
    server = MockServer(("127.0.0.1", 0))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = "http://127.0.0.1:" + str(server.server_port)
    checks = []
    try:
        status, body = _post(base, "/anthropic/v1/messages")
        checks.append(("normal HTTP 200", status == 200))
        checks.append(("normal has message_stop", "event: message_stop" in body))
        checks.append(("normal has text deltas", "content_block_delta" in body))
        checks.append(("normal usage output_tokens=15", '"output_tokens":15' in body))
        checks.append(("normal prompt cache usage", '"cache_read_input_tokens":40' in body))

        status, body = _post(base, "/anthropic/v1/messages/truncated")
        checks.append(("truncated HTTP 200", status == 200))
        checks.append(("truncated has no message_stop", "message_stop" not in body))
        checks.append(("truncated keeps partial text", "text_delta" in body))

        status, body = _post(base, "/anthropic/v1/messages/error_sse")
        checks.append(("error_sse HTTP 200", status == 200))
        checks.append(("error_sse error frame", "event: error" in body and "overloaded_error" in body))

        status, body = _post(base, "/anthropic/v1/messages?mode=429")
        checks.append(("429 status", status == 429))
        checks.append(("429 body", "rate_limit_error" in body))

        status, body = _post(base, "/anthropic/v1/messages?mode=500")
        checks.append(("500 status", status == 500))
        checks.append(("500 body", "server_error" in body))

        body = urllib.request.urlopen(base + "/health", timeout=5).read().decode()
        checks.append(("health endpoint", '"status":"ok"' in body))
    finally:
        server.shutdown()

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(("PASS " if ok else "FAIL ") + name)
    print("mock sse selftest: {0}/{1} passed".format(len(checks) - len(failed), len(checks)))
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description="Mock Anthropic-compatible SSE server (stdlib only)")
    parser.add_argument("--port", type=int, default=18080, help="listen port (default: 18080)")
    parser.add_argument("--host", default="127.0.0.1", help="bind address (default: 127.0.0.1)")
    parser.add_argument("--delay", type=float, default=0.02, help="seconds between frames (default: 0.02)")
    parser.add_argument("--default-mode", choices=MODES, default="normal", help="mode when path has none")
    parser.add_argument("--selftest", action="store_true", help="run built-in protocol checks and exit")
    args = parser.parse_args()
    MockHandler.frame_delay = args.delay

    if args.selftest:
        return selftest()

    server = MockServer((args.host, args.port), args.default_mode)
    print("mock SSE server on http://{0}:{1}  modes={2}".format(args.host, server.server_port, ",".join(MODES)), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
