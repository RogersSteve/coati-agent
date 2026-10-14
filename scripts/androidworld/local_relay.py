"""Single-instance loopback transport for fixed model POST endpoints.

Profile openrouter (default): two fixed OpenRouter POST endpoints. Profile google:
Gemini generateContent on the official Google API (allowlisted models) under
/google, and Jev on api.typesafe.ai under /jev. The relay validates upstream TLS
normally using the host clock. It never loads model credentials: only
authorization digests are supplied over parent stdin. POST requests are
streamed back without retries, redirects or decompression.
"""
import argparse
import asyncio
import hashlib
import hmac
import json
import logging
import re
import signal
import ssl
import sys
import time
from pathlib import Path

from aiohttp import ClientSession, ClientTimeout, DummyCookieJar, TCPConnector, web
from multidict import CIMultiDict

ORIGIN = "https://openrouter.ai"
PATHS = frozenset(("/api/v1/chat/completions", "/api/v1/systemone"))
GOOGLE_ORIGIN = "https://generativelanguage.googleapis.com"
JEV_ORIGIN = "https://api.typesafe.ai"
GOOGLE_MODELS = frozenset(("gemini-3.8-flash",))
# The Gemini gateway authenticates with a key query parameter; nothing else may follow it.
GOOGLE_PATH = re.compile(r"/google(/v1beta/models/([A-Za-z0-9.-]+):generateContent)\?key=([A-Za-z0-9_.-]+)")
JEV_PATH = "/jev/v1/systemone"
HOP = frozenset(("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                 "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection"))
MAX_BODY = 32 * 1024 * 1024
MAX_RESPONSE = 64 * 1024 * 1024
# The app has a 300s whole-call timeout. Do not introduce an earlier relay timeout.
UPSTREAM_TIMEOUT = 315


def clean_headers(headers, request=False):
    connection_tokens = {token.strip().lower() for value in headers.getall("Connection", [])
                         for token in value.split(",")}
    blocked = HOP | connection_tokens | ({"host", "expect"} if request else set())
    return CIMultiDict((name, value) for name, value in headers.items() if name.lower() not in blocked)


class Relay:
    def __init__(self, auth_digest, metrics, instance, session_factory=None, timeout=UPSTREAM_TIMEOUT, device_port=None,
                 google_key_digest=None):
        for value in (auth_digest, google_key_digest):
            if value is not None and (len(value) != 64 or any(c not in "0123456789abcdef" for c in value)):
                raise ValueError("Authorization digest required")
        if auth_digest is None:
            raise ValueError("Authorization digest required")
        self.auth_digest, self.metrics, self.instance = auth_digest, metrics, instance
        # Present only for the google profile.
        self.google_key_digest = google_key_digest
        self.session_factory, self.timeout = session_factory, timeout
        self.device_port = device_port
        self.session = None
        self.active = 0

    async def startup(self, app):
        if self.session_factory:
            self.session = self.session_factory()
        else:
            context = ssl.create_default_context()
            if not context.check_hostname or context.verify_mode != ssl.CERT_REQUIRED:
                raise RuntimeError("Standard upstream TLS required")
            self.session = ClientSession(connector=TCPConnector(ssl=context, limit=4, ttl_dns_cache=300),
                timeout=ClientTimeout(total=self.timeout), auto_decompress=False,
                cookie_jar=DummyCookieJar(), trust_env=False,
                skip_auto_headers={"User-Agent", "Accept", "Accept-Encoding", "Content-Type"})

    async def shutdown(self, app):
        if self.session is not None:
            await self.session.close()

    def target(self, raw_path):
        """(upstream URL, path safe to log, query credential or None for Bearer auth), or None."""
        if self.google_key_digest is None:
            return (ORIGIN+raw_path, raw_path, None) if raw_path in PATHS else None
        if raw_path == JEV_PATH:
            return JEV_ORIGIN+"/v1/systemone", JEV_PATH, None
        match = GOOGLE_PATH.fullmatch(raw_path)
        if match and match[2] in GOOGLE_MODELS:
            return GOOGLE_ORIGIN+match[1]+"?key="+match[3], "/google"+match[1], match[3]
        return None

    async def handle(self, request):
        start = time.monotonic()
        target = self.target(request.raw_path)
        event = {"instance": self.instance, "route": "loopback_adb_reverse_host_https",
                 # Never the raw path: a Gemini path carries its key.
                 "path": target[1] if target else "rejected_path",
                 "host_monotonic_start": start,
                 "upstream": target[0].split("/")[2] if target else "openrouter.ai" if self.google_key_digest is None else "none",
                 "upstream_requests": 0, "request_bytes": 0,
                 "response_bytes": 0, "status": None, "outcome": "rejected"}
        response = None
        acquired = False
        try:
            if request.host not in {f"127.0.0.1:{self.device_port}", f"localhost:{self.device_port}"}:
                return web.Response(status=400, text="Invalid local authority")
            if target is None or request.method != "POST":
                return web.Response(status=404, text="Unsupported route")
            url, _, query_key = target
            auth = request.headers.getall("Authorization", [])
            if query_key is not None:
                if auth or not hmac.compare_digest(hashlib.sha256(query_key.encode()).hexdigest(), self.google_key_digest):
                    return web.Response(status=401, text="Local authorization required")
            elif len(auth) != 1 or not auth[0].startswith("Bearer ") or not hmac.compare_digest(
                    hashlib.sha256(auth[0].encode()).hexdigest(), self.auth_digest):
                return web.Response(status=401, text="Local authorization required")
            if request.headers.get("Content-Encoding", "identity").lower() != "identity":
                return web.Response(status=415, text="Compressed requests are not supported")
            if self.active >= 4:
                return web.Response(status=503, text="Relay capacity reached")
            self.active += 1
            acquired = True
            async with asyncio.timeout(self.timeout):
                # Raw bytes are bounded, read once, and never parsed or reserialized.
                body = await request.read()
                event["request_bytes"] = len(body)
                headers = clean_headers(request.headers, request=True)
                event["local_before_upstream_ms"] = (time.monotonic()-start)*1000
                upstream_start = time.monotonic()
                event["upstream_requests"] = 1
                # aiohttp retries only idempotent methods; POST is not one of them.
                # No application retry loop exists here. Never enable redirects.
                async with self.session.request("POST", url, data=body,
                        headers=headers, allow_redirects=False) as upstream:
                    event["upstream_headers_wait_ms"] = (time.monotonic()-upstream_start)*1000
                    if 300 <= upstream.status < 400:
                        event["outcome"] = "redirect_rejected"
                        return web.Response(status=502, text="Upstream redirect refused")
                    response = web.StreamResponse(status=upstream.status, headers=clean_headers(upstream.headers))
                    event["status"] = upstream.status
                    await response.prepare(request)
                    async for chunk in upstream.content.iter_chunked(65536):
                        event["response_bytes"] += len(chunk)
                        if event["response_bytes"] > MAX_RESPONSE:
                            raise ValueError("Response resource limit")
                        await response.write(chunk)
                    await response.write_eof()
                    event["outcome"] = "forwarded"
                    return response
        except asyncio.CancelledError:
            event["outcome"] = "downstream_cancelled"
            raise
        except web.HTTPRequestEntityTooLarge:
            event["outcome"] = "request_limit"
            return web.Response(status=413, text="Request resource limit")
        except Exception as error:
            event["outcome"] = "timeout" if isinstance(error, TimeoutError) else "transport_error"
            event["error_type"] = type(error).__name__  # Never exception text/headers/body.
            if response is not None and response.prepared:
                if request.transport:
                    request.transport.close()
                return response
            return web.Response(status=504 if isinstance(error, TimeoutError) else 502,
                                text="Relay transport failed")
        finally:
            if acquired:
                self.active -= 1
            event["wall_ms"] = (time.monotonic()-start)*1000
            self.metrics(event)

    def app(self):
        app = web.Application(client_max_size=MAX_BODY)
        app.router.add_route("*", "/{tail:.*}", self.handle)
        app.on_startup.append(self.startup)
        app.on_cleanup.append(self.shutdown)
        return app


async def serve(args):
    # Private parent pipe: digest only, no raw model key or request body.
    config = json.loads(sys.stdin.readline())
    legacy, google = {"authorization_sha256", "instance", "device_port"}, {"authorization_sha256", "google_key_sha256", "instance", "device_port"}
    if set(config) not in (legacy, google) or type(config["device_port"]) is not int or not 1024 <= config["device_port"] <= 65535:
        raise ValueError("Invalid control message")
    path = Path(args.metrics)
    path.parent.mkdir(parents=True, exist_ok=True)
    import os
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", buffering=1) as log:
        def metrics(event):
            log.write(json.dumps(event, separators=(",", ":"))+"\n")
        relay = Relay(config["authorization_sha256"], metrics, config["instance"], device_port=config["device_port"],
                      google_key_digest=config.get("google_key_sha256"))
        runner = web.AppRunner(relay.app(), access_log=None, handler_cancellation=True, auto_decompress=False, shutdown_timeout=5)
        await runner.setup()
        site = web.TCPSite(runner, "127.0.0.1", args.port)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        print(json.dumps({"ready": True, "host": "127.0.0.1", "port": port,
                          "instance": config["instance"]}), flush=True)
        stop = asyncio.Event()
        for sig in (signal.SIGTERM, signal.SIGINT):
            asyncio.get_running_loop().add_signal_handler(sig, stop.set)
        try:
            await stop.wait()
        finally:
            await runner.cleanup()


if __name__ == "__main__":
    logging.disable(logging.CRITICAL)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--port", type=int, default=0)
    p.add_argument("--metrics", required=True)
    args = p.parse_args()
    if not 0 <= args.port <= 65535:
        raise SystemExit("Invalid loopback port")
    try:
        asyncio.run(serve(args))
    except Exception as error:
        print(json.dumps({"error_type": type(error).__name__}), file=sys.stderr)
        raise SystemExit(1)
