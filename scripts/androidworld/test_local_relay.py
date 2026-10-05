"""Transport contract tests. Only loopback test servers; no public API calls."""
import asyncio
import gzip
import hashlib
import json
import unittest
from unittest.mock import Mock
from aiohttp import ClientSession, ClientTimeout, web
from aiohttp.test_utils import TestClient, TestServer
from multidict import CIMultiDict
from local_relay import Relay, ORIGIN, PATHS, clean_headers
from relay_connection import ReverseLease

AUTH = "Bearer local-test-value-not-a-model-key"
BODY = b'{ "model": "unchanged", "messages": [{"content":"\\u4f60"}], "stream":false }'


class Content:
    def __init__(self, chunks): self.chunks = chunks
    async def iter_chunked(self, size):
        for chunk in self.chunks:
            if callable(chunk): await chunk()
            else: yield chunk


class Upstream:
    def __init__(self, body=b'{"usage":{"tokens":7}}', status=200, headers=None, chunks=None):
        self.status = status
        self.headers = CIMultiDict(headers or {"Content-Type":"application/json", "Content-Length":str(len(body))})
        self.content = Content(chunks if chunks is not None else [body])
        self.exited = False
    async def __aenter__(self): return self
    async def __aexit__(self, *args): self.exited = True


class Session:
    def __init__(self, upstream): self.upstream=upstream; self.calls=[]; self.closed=False
    def request(self, *args, **kwargs): self.calls.append((args,kwargs)); return self.upstream
    async def close(self): self.closed=True


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self): self.resources=[]
    async def asyncTearDown(self):
        for item in reversed(self.resources): await item.close()
    async def client(self, upstream=None, timeout=315, factory=None):
        self.events=[]; self.session=Session(upstream or Upstream())
        self.relay=Relay(hashlib.sha256(AUTH.encode()).hexdigest(),self.events.append,"test-instance",
                         session_factory=factory or (lambda:self.session),timeout=timeout)
        c=TestClient(TestServer(self.relay.app(),handler_cancellation=True,auto_decompress=False)); await c.start_server(); self.relay.device_port=c.server.port; self.resources.append(c); return c
    async def post(self,c,path="/api/v1/chat/completions",**kw):
        return await c.post(path,data=kw.pop("data",BODY),headers=kw.pop("headers",{"Authorization":AUTH}),**kw)
    async def test_two_paths_raw_body_usage_and_no_extra_retry(self):
        c=await self.client()
        for path in sorted(PATHS):
            async with await self.post(c,path) as response:
                self.assertEqual(response.status,200); self.assertEqual(await response.read(),b'{"usage":{"tokens":7}}')
            args,kw=self.session.calls[-1];self.assertEqual(args,("POST",ORIGIN+path)); self.assertEqual(kw['data'],BODY)
            self.assertEqual(kw['headers']['Authorization'],AUTH); self.assertFalse(kw['allow_redirects'])
        self.assertEqual(len(self.session.calls),2)
        self.assertNotIn(AUTH,json.dumps(self.events)); self.assertNotIn('unchanged',json.dumps(self.events))
    async def test_unauthorized_paths_query_method_and_authority_never_forward(self):
        c=await self.client()
        for path in ['/api/v1/models','/api/v1/chat/completions?url=https://evil.invalid']:
            # encoded path tested via raw_url below if client normalizes it.
            if '%63' in path: continue
            async with await self.post(c,path) as response:self.assertEqual(response.status,404)
        async with await self.post(c,headers={}) as response:self.assertEqual(response.status,401)
        async with await self.post(c,headers={'Authorization':'Bearer wrong'}) as response:self.assertEqual(response.status,401)
        async with await self.post(c,headers={'Authorization':AUTH,'Host':'evil.invalid'}) as response:self.assertEqual(response.status,400)
        async with await c.get('/api/v1/chat/completions') as response:self.assertEqual(response.status,404)
        for raw_path in ['/api/v1/%63hat/completions','//evil.invalid/api/v1/chat/completions']:
            reader, writer = await asyncio.open_connection(c.server.host,c.server.port)
            writer.write((f"POST {raw_path} HTTP/1.1\r\nHost: 127.0.0.1:{c.server.port}\r\nAuthorization: {AUTH}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").encode())
            await writer.drain()
            self.assertIn(b" 404 ",await reader.readline())
            writer.close();await writer.wait_closed()
        self.assertFalse(self.session.calls)
    async def test_adb_reverse_preserves_device_host_not_host_listen_port(self):
        c=await self.client(); self.relay.device_port=35556
        self.assertNotEqual(c.server.port,35556)
        async with await self.post(c,headers={'Authorization':AUTH,'Host':'127.0.0.1:35556'}) as response:
            self.assertEqual(response.status,200)
        async with await self.post(c) as response:self.assertEqual(response.status,400)
        self.assertEqual(len(self.session.calls),1)
    async def test_compressed_requests_are_explicitly_rejected(self):
        c=await self.client()
        async with await self.post(c,data=gzip.compress(BODY),headers={'Authorization':AUTH,'Content-Encoding':'gzip'}) as response:self.assertEqual(response.status,415)
        self.assertFalse(self.session.calls)
    async def test_upstream_auth_error_passes_without_retry(self):
        c=await self.client(Upstream(b'error bytes',401))
        async with await self.post(c) as response:self.assertEqual(response.status,401);self.assertEqual(await response.read(),b'error bytes')
        self.assertEqual(len(self.session.calls),1)
    async def test_redirect_refused(self):
        c=await self.client(Upstream(b'',307,{'Location':'https://evil.invalid','Content-Length':'0'}))
        async with await self.post(c) as response:self.assertEqual(response.status,502);self.assertNotIn('Location',response.headers)
        self.assertEqual(len(self.session.calls),1)
    async def test_compressed_response_bytes_and_length(self):
        payload=gzip.compress(b'{"usage":7}')
        c=await self.client(Upstream(payload,headers={'Content-Encoding':'gzip','Content-Length':str(len(payload))}))
        async with await self.post(c,auto_decompress=False) as response:
            self.assertEqual(await response.read(),payload);self.assertEqual(int(response.headers['Content-Length']),len(payload))
    async def test_incremental_response_and_client_cancel_releases_upstream(self):
        held=asyncio.Event()
        async def stall(): await held.wait()
        u=Upstream(headers={'Content-Type':'text/event-stream'},chunks=[b'first\n',stall,b'last\n'])
        c=await self.client(u)
        response=await self.post(c)
        self.assertEqual(await asyncio.wait_for(response.content.read(6),1),b'first\n')
        response.close()
        for _ in range(50):
            if u.exited: break
            await asyncio.sleep(.01)
        self.assertTrue(u.exited);self.assertEqual(self.relay.active,0);self.assertEqual(self.events[-1]['outcome'],'downstream_cancelled')
    async def test_timeout_releases_session_without_retry(self):
        class Slow(Upstream):
            async def __aenter__(self):await asyncio.sleep(1);return self
        c=await self.client(Slow(),timeout=.02)
        async with await self.post(c) as response:self.assertEqual(response.status,504)
        self.assertEqual(len(self.session.calls),1);self.assertEqual(self.relay.active,0)
        await c.close();self.assertTrue(self.session.closed)
    async def test_actual_aiohttp_post_disconnect_is_not_retried(self):
        calls=[]
        async def disconnect(req):calls.append(1);req.transport.close();return web.Response()
        app=web.Application();app.router.add_post('/api/v1/chat/completions',disconnect)
        upstream=TestServer(app);await upstream.start_server();self.resources.append(upstream)
        class LocalSession:
            def __init__(self):self.real=ClientSession(timeout=ClientTimeout(total=1))
            def request(self,method,url,**kw):return self.real.request(method,upstream.make_url('/api/v1/chat/completions'),**kw)
            async def close(self):await self.real.close()
        c=await self.client(factory=LocalSession)
        async with await self.post(c) as response:self.assertEqual(response.status,502)
        self.assertEqual(len(calls),1)
    def test_hop_headers_removed_encoding_preserved(self):
        h=CIMultiDict({'Connection':'keep-alive, X-Private-Hop','X-Private-Hop':'drop','Host':'old',
                      'Transfer-Encoding':'chunked','Content-Encoding':'gzip','Content-Length':'10','Authorization':AUTH})
        clean=clean_headers(h,request=True)
        for n in ['Connection','X-Private-Hop','Host','Transfer-Encoding']:self.assertNotIn(n,clean)
        self.assertEqual(clean['Content-Encoding'],'gzip');self.assertEqual(clean['Content-Length'],'10')


class ReverseTests(unittest.TestCase):
    def setUp(self):
        self.c={'adb_path':'/private/adb','adb_server_port':5039,'console_port':5556,'device_adb_port':5557,'grpc_port':8556,'serial':'emulator-5556'}
        self.mapping=None;self.calls=[];self.boot='boot1';self.device_id='expected'
        def run(*args):
            self.calls.append(args)
            if args==('get-serialno',):return 'emulator-5556'
            if args[:2]==('shell','settings'):return self.device_id
            if args[:2]==('shell','cat'):return self.boot
            if args==('reverse','--list'):return '' if self.mapping is None else 'emulator-5556 tcp:35556 '+self.mapping
            if args[:2]==('reverse','--no-rebind'):
                if self.mapping is not None:raise RuntimeError('occupied')
                self.mapping=args[3];return ''
            if args[:2]==('reverse','--remove'):self.mapping=None;return ''
            raise AssertionError(args)
        self.lease=ReverseLease(self.c,35556,48001,'expected',run=run)
    def test_create_verify_reconnect_restore_and_cleanup_only_owned(self):
        self.lease.ensure();self.lease.ensure();self.assertEqual(self.mapping,'tcp:48001')
        self.mapping=None;self.boot='boot2';self.lease.ensure();self.assertEqual(self.lease.boot_id,'boot2')
        self.lease.close();self.assertIsNone(self.mapping)
        self.assertNotIn(('reverse','--remove-all'),self.calls)
        self.assertEqual(self.lease.adb,['/private/adb','-H','127.0.0.1','-P','5039','-s','emulator-5556'])
    def test_existing_foreign_mapping_never_rebound_or_removed(self):
        self.mapping='tcp:other'
        with self.assertRaises(RuntimeError):self.lease.ensure()
        self.lease.close();self.assertEqual(self.mapping,'tcp:other')
    def test_mapping_changed_after_ownership_never_removed(self):
        self.lease.ensure();self.mapping='tcp:other';self.lease.close();self.assertEqual(self.mapping,'tcp:other')
    def test_wrong_device_and_shared_server_fail_closed(self):
        self.device_id='other'
        with self.assertRaises(RuntimeError):self.lease.ensure()
        for port in [5037,5038]:
            with self.assertRaises(ValueError):ReverseLease(dict(self.c,adb_server_port=port),35556,48001,'expected')


if __name__=='__main__':unittest.main(verbosity=2)
