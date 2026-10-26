#!/usr/bin/env python3
"""Tiny OpenAI-compatible upstream for a local gateway demo.

Not used by the test suite. Run it, point OPENAI_BASE_URL at it, and the
gateway will make a real HTTP call without an OpenAI account.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json

HOST = "127.0.0.1"
PORT = 8099


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            self._send(400, {"error": {"message": "invalid json", "type": "invalid_request_error"}})
            return

        authorized = self.headers.get("Authorization", "").startswith("Bearer ")
        if not authorized:
            self._send(401, {"error": {"message": "missing upstream key", "type": "authentication_error"}})
            return

        model = payload.get("model", "dev-model")
        print(
            f"{self.command} {self.path} model={model} stream={bool(payload.get('stream'))} "
            f"request_id={self.headers.get('X-Request-Id', '-')}"
        )
        if payload.get("stream"):
            self._send_stream(model)
        else:
            self._send(200, completion(model))

    def _send_stream(self, model):
        frames = [
            {"id": "chatcmpl-dev", "object": "chat.completion.chunk", "model": model,
             "choices": [{"index": 0, "delta": {"role": "assistant", "content": "hello from the dev upstream"}, "finish_reason": None}]},
            {"id": "chatcmpl-dev", "object": "chat.completion.chunk", "model": model,
             "choices": [], "usage": {"prompt_tokens": 6, "completion_tokens": 5, "total_tokens": 11}},
        ]
        body = "".join(f"data: {json.dumps(frame)}\n\n" for frame in frames) + "data: [DONE]\n\n"
        encoded = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _send(self, status, payload):
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, fmt, *args):
        return


def completion(model):
    return {
        "id": "chatcmpl-dev",
        "object": "chat.completion",
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": "hello from the dev upstream"},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 8, "completion_tokens": 5, "total_tokens": 13},
    }


if __name__ == "__main__":
    print(f"dev upstream listening on http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
