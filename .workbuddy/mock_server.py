#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mock 服务器：验证 APP GET/POST FaceSyncTasks 的 TaskIdentifier。
GET /hpt/v2/Accounts/FaceSyncTasks -> 返回带 TaskIdentifier 的任务（照片地址无效，触发下载失败但不影响上报）
POST /hpt/v2/Accounts/FaceSyncTasks -> 记录 body，验证是否带 TaskIdentifier
"""
import http.server
import json
import datetime

LOG = r"E:\WorkBuddy\FacialFeatureExtraction\.workbuddy\mock_traffic.log"
FACE_IMG = r"E:\WorkBuddy\FacialFeatureExtraction\.workbuddy\face_test.png"

GET_BODY = json.dumps({
    "clientID": 8,
    "TaskIdentifier": "TESTTK-001",
    "accountInfos": [
        {"accountID": "u1", "faceDataUrl": "http://127.0.0.1:18080/face_test.png"},
        {"accountID": "u2", "faceDataUrl": "http://127.0.0.1:18080/face_test.png"}
    ]
}).encode("utf-8")


class H(http.server.BaseHTTPRequestHandler):
    def _log(self, body=b""):
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("=== %s %s %s\n" % (
                datetime.datetime.now().strftime("%H:%M:%S"),
                self.command, self.path))
            for k, v in self.headers.items():
                f.write("H: %s: %s\n" % (k, v))
            if body:
                f.write("BODY: %s\n" % body.decode("utf-8", "replace"))

    def do_GET(self):
        self._log()
        if self.path.startswith("/face_test.png"):
            with open(FACE_IMG, "rb") as f:
                img = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(img)))
            self.end_headers()
            self.wfile.write(img)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(GET_BODY)))
        self.end_headers()
        self.wfile.write(GET_BODY)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(n) if n else b""
        self._log(data)
        resp = b"{}"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    http.server.HTTPServer(("127.0.0.1", 18080), H).serve_forever()
