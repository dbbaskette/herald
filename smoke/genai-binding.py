#!/usr/bin/env python3
"""Exercise one packaged bot JAR against a loopback stub in local and bound modes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import URLError
from urllib.request import Request, build_opener, ProxyHandler


class Stub(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.server.requests.append((self.path, self.headers.get("Authorization"), data))
        response = {"id": "smoke-1", "object": "chat.completion", "created": 1,
                    "model": data.get("model"),
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": "PONG"},
                                 "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
        body = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def jar_hash(jar):
    with jar.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
        return digest.hexdigest()


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run_mode(jar, java, stub, mode, timeout):
    with tempfile.TemporaryDirectory(prefix="herald-genai-") as directory:
        work = Path(directory)
        port = unused_port()
        # Allowlist: no inherited provider keys, Spring settings, agents, proxies or JVM options.
        env = {"PATH": os.defpath, "LANG": "en_US.UTF-8", "TMPDIR": directory,
               "HERALD_CRON_ENABLED": "false", "HERALD_MCP_CLIENT_ENABLED": "false",
               "HERALD_BOT_BIND_ADDRESS": "127.0.0.1", "HERALD_SERVER_PORT": str(port),
               "HERALD_DEFAULT_PROVIDER": "openai", "HERALD_MODEL_OPENAI": "local-model",
               "OPENAI_API_KEY": "local-key", "HERALD_AGENT_MEMORY_CONSOLIDATION_TRIGGER": "off"}
        base = f"http://127.0.0.1:{stub.server_port}/tenant/deployment"
        env["HERALD_PROVIDERS_OPENAI_BASE_URL"] = base + "/openai/v1"
        expected_model, expected_key = "local-model", "local-key"
        if mode != "local":
            expected_model, expected_key = "bound-model", "bound-key"
            # Keep conflicting local credentials to catch SDK getenv bypasses.
            if mode == "single":
                credentials = {"api_base": base + "/openai", "api_key": expected_key,
                               "model_name": expected_model, "wire_format": "openai"}
            else:
                credentials = {"endpoint": {"api_base": base, "api_key": expected_key}}
                env["HERALD_GENAI_BINDING_MODEL"] = expected_model
            env["VCAP_SERVICES"] = json.dumps({"renamed-ai-services": [
                {"instance_name": "smoke", "tags": ["genai", "llm"], "credentials": credentials}]})
            env["HERALD_GENAI_BINDING_SERVICE_NAME"] = "smoke"
        stub.requests.clear()
        opener = build_opener(ProxyHandler({}))
        with (work / "bot.log").open("wb") as log:
            process = subprocess.Popen([java, f"-Duser.home={directory}", "-jar", str(jar),
                                        "--spring.config.location=classpath:/application.yaml",
                                        "--herald.cron.enabled=false", "--herald.agent.memory.consolidation-trigger=off"],
                                       cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError(f"{mode}: bot exited before readiness (exit {process.returncode})")
                    try:
                        with opener.open(f"http://127.0.0.1:{port}/actuator/health", timeout=1) as response:
                            if json.load(response).get("status") == "UP":
                                break
                    except (URLError, TimeoutError, ConnectionError):
                        pass
                    time.sleep(0.25)
                else:
                    raise RuntimeError(f"{mode}: startup timed out")
                request = Request(f"http://127.0.0.1:{port}/api/chat",
                                  data=json.dumps({"message": "Reply PONG", "conversationId": "genai-smoke"}).encode(),
                                  headers={"Content-Type": "application/json"})
                with opener.open(request, timeout=max(1, deadline - time.monotonic())) as response:
                    result = json.load(response)
                if result.get("error") or result.get("reply") != "PONG":
                    raise RuntimeError(f"{mode}: chat did not return PONG")
                if not stub.requests:
                    raise RuntimeError(f"{mode}: no model request received")
                for path, authorization, body in stub.requests:
                    if (path != "/tenant/deployment/openai/v1/chat/completions"
                            or authorization != "Bearer " + expected_key or body.get("model") != expected_model):
                        raise RuntimeError(f"{mode}: incorrect request path, credential or model")
                print(f"PASS {mode}: chat, request path, key and model")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--timeout", type=float, default=120, help="Maximum seconds per bot run")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    if not args.java or args.timeout <= 0:
        parser.error("Java and a positive timeout are required")
    java = str(Path(args.java).resolve(strict=True))
    original = jar_hash(jar)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    server.requests = []
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        for mode in ("local", "single", "endpoint"):
            if jar_hash(jar) != original:
                raise RuntimeError("JAR bytes changed before launch")
            run_mode(jar, java, server, mode, args.timeout)
            if jar_hash(jar) != original:
                raise RuntimeError("JAR bytes changed during run")
        print(f"PASS unchanged JAR SHA-256: {original}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
