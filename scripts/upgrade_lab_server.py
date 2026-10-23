"""Loopback-only synthetic HTTPS service for production APK verification.

Never forwards traffic. Only the two adapter hosts and fixed fixture routes work.
Use a task-owned emulator with direct service DNS blocked and a temporary system
test CA. Optional direct TLS supports adb reverse without changing the emulator's
global proxy, preserving OS network validation during background observations.
No production APK, trust policy, signing key or real account is modified.
"""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import subprocess
import threading
import time
from urllib.parse import parse_qs


def certificates(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    # Fresh keys for this lab, entirely unrelated to the APK release keystore.
    ca_config = directory / 'ca.cnf'
    ca_config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n'
                         '[dn]\nCN=Quodex disposable upgrade lab\n[ca]\n'
                         'basicConstraints=critical,CA:true,pathlen:0\n'
                         'keyUsage=critical,keyCertSign,cRLSign\n')
    leaf_config = directory / 'server.cnf'
    leaf_config.write_text('basicConstraints=critical,CA:false\n'
                           'keyUsage=critical,digitalSignature,keyEncipherment\n'
                           'extendedKeyUsage=serverAuth\n'
                           'subjectAltName=DNS:auth.openai.com,DNS:chatgpt.com\n')
    commands = [
        ['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-x509', '-days', '2',
         '-config', str(ca_config), '-keyout', str(directory / 'ca.key'), '-out', str(directory / 'ca.pem')],
        ['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=auth.openai.com',
         '-keyout', str(directory / 'server.key'), '-out', str(directory / 'server.csr')],
        ['openssl', 'x509', '-req', '-days', '2', '-sha256', '-in', str(directory / 'server.csr'),
         '-CA', str(directory / 'ca.pem'), '-CAkey', str(directory / 'ca.key'), '-CAcreateserial',
         '-extfile', str(leaf_config), '-out', str(directory / 'server.pem')],
    ]
    for command in commands:
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    for name in ('ca.key', 'server.key'):
        (directory / name).chmod(0o600)


class Fixture:
    account = 'quodex-synthetic-upgrade-account'

    def __init__(self):
        self.lock = threading.Lock()
        self.used = 38
        self.require_renewal = False
        self.counts = dict(challenges=0, polls=0, exchanges=0, renewals=0, usage=0, rejected=0, blocked=0)
        self.access = {version: self.token(version) for version in (1, 2)}

    def token(self, version):
        def encode(value):
            return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=').decode()
        return encode({'alg': 'none'}) + '.' + encode({
            'exp': int(time.time()) + 86400, 'version': version,
            'https://api.openai.com/auth': {'chatgpt_account_id': self.account}}) + '.'

    def session(self, version):
        return dict(access_token=self.access[version], refresh_token=f'synthetic-refresh-{version}', expires_in=86400)

    def reply(self, host, method, path, headers, body):
        with self.lock:
            if host is None and method == 'GET' and path == '/lab/state':
                return 200, dict(self.counts, used=self.used, requireRenewal=self.require_renewal)
            if host is None and method == 'POST' and path == '/lab/after-upgrade':
                self.used, self.require_renewal = 17, True
                return 200, {'ready': True}
            if host == 'auth.openai.com' and method == 'POST':
                if path == '/api/accounts/deviceauth/usercode':
                    self.counts['challenges'] += 1
                    return 200, dict(device_auth_id='synthetic-device', user_code='LAB-ONLY', interval='1')
                if path == '/api/accounts/deviceauth/token':
                    self.counts['polls'] += 1
                    return 200, dict(authorization_code='synthetic-code', code_verifier='synthetic-verifier')
                if path == '/oauth/token':
                    if headers.get('Content-Type', '').startswith('application/json'):
                        request = json.loads(body)
                        if request.get('grant_type') == 'refresh_token' and request.get('refresh_token') == 'synthetic-refresh-1':
                            self.counts['renewals'] += 1
                            return 200, self.session(2)
                    else:
                        request = parse_qs(body.decode())
                        if request.get('code') == ['synthetic-code'] and request.get('code_verifier') == ['synthetic-verifier']:
                            self.counts['exchanges'] += 1
                            return 200, self.session(1)
            if host == 'chatgpt.com' and method == 'GET' and path == '/backend-api/wham/usage':
                self.counts['usage'] += 1
                expected = self.access[2 if self.require_renewal else 1]
                if headers.get('Authorization') != 'Bearer ' + expected or headers.get('ChatGPT-Account-Id') != self.account:
                    self.counts['rejected'] += 1
                    return 401, {'error': 'synthetic authorization rejected'}
                return 200, dict(account_id=self.account, plan_type='synthetic', rate_limit=dict(
                    primary_window=dict(used_percent=self.used, limit_window_seconds=604800)))
            self.counts['blocked'] += 1
            return 403, {'error': 'fixture route blocked'}


def make_server(directory, port=9443, *, fixture=None, direct_tls=False):
    fixture = fixture if fixture is not None else Fixture()
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(str(Path(directory) / 'server.pem'), str(Path(directory) / 'server.key'))

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        tunnel_host = None

        def log_message(self, *_):
            pass  # Never log headers, request bodies, query strings or credentials.

        def finish(self):
            try:
                super().finish()
            finally:
                # CONNECT replaces the original socket; the server only owns
                # that original descriptor, so close the TLS socket here too.
                self.connection.close()

        def respond(self, status, value):
            payload = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(payload)
            self.close_connection = True

        def do_CONNECT(self):
            if direct_tls:
                self.respond(403, {'error': 'nested tunnel disabled'})
                return
            if self.path not in ('auth.openai.com:443', 'chatgpt.com:443'):
                with fixture.lock:
                    fixture.counts['blocked'] += 1
                self.respond(403, {'error': 'external forwarding disabled'})
                return
            self.tunnel_host = self.path.split(':')[0]
            self.send_response(200)
            self.end_headers()
            self.wfile.flush()
            self.connection.settimeout(20)
            try:
                self.connection = tls.wrap_socket(self.connection, server_side=True)
                self.rfile = self.connection.makefile('rb')
                self.wfile = self.connection.makefile('wb')
                self.handle_one_request()
            except (ssl.SSLError, TimeoutError, ConnectionError):
                pass
            self.close_connection = True

        def handle_fixture(self):
            try:
                if direct_tls:
                    authority = self.headers.get('Host', '')
                    if authority not in ('auth.openai.com', 'auth.openai.com:443', 'chatgpt.com', 'chatgpt.com:443'):
                        with fixture.lock:
                            fixture.counts['blocked'] += 1
                        self.respond(403, {'error': 'fixture authority blocked'})
                        return
                    self.tunnel_host = authority.split(':')[0]
                length = int(self.headers.get('Content-Length', '0'))
                if length < 0 or length > 16384:
                    self.respond(413, {'error': 'fixture body too large'})
                    return
                if self.tunnel_host and self.headers.get('Host', '').split(':')[0] != self.tunnel_host:
                    self.respond(403, {'error': 'tunnel authority mismatch'})
                    return
                body = self.rfile.read(length)
                status, value = fixture.reply(self.tunnel_host, self.command, self.path, self.headers, body)
                self.respond(status, value)
            except (ValueError, json.JSONDecodeError):
                self.respond(400, {'error': 'malformed fixture request'})

        do_GET = do_POST = handle_fixture

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    if direct_tls:
        server.socket = tls.wrap_socket(server.socket, server_side=True)
    return server, fixture


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--port', type=int, default=9443)
    parser.add_argument('--create-certificates', action='store_true')
    parser.add_argument('--direct-tls-port', type=int, help='Optional loopback TLS listener for owned-emulator adb reverse; shares fixture state')
    args = parser.parse_args()
    if args.create_certificates:
        certificates(args.directory)
    server, fixture = make_server(args.directory, args.port)
    direct = None
    if args.direct_tls_port is not None:
        direct, _ = make_server(args.directory, args.direct_tls_port, fixture=fixture, direct_tls=True)
        threading.Thread(target=direct.serve_forever, daemon=True).start()
        print(f'Synthetic direct TLS listening on 127.0.0.1:{direct.server_port}', flush=True)
    print(f'Synthetic upgrade fixture listening on 127.0.0.1:{server.server_port}; no external forwarding', flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        if direct is not None:
            direct.shutdown()
            direct.server_close()
