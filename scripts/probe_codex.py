#!/usr/bin/env python3
"""Read-only protocol probe. Never prints tokens, identity, or actual quota values."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--auth-file', type=Path, default=Path.home() / '.codex/auth.json')
args = parser.parse_args()
auth = json.loads(args.auth_file.read_text())
tokens = auth.get('tokens') or {}
if not tokens.get('access_token'):
    raise SystemExit('No ChatGPT access token available; authenticate using Codex first.')
headers = {'Authorization': 'Bearer ' + tokens['access_token'], 'Accept': 'application/json',
           'User-Agent': 'Quodex/0.1 protocol-validation'}
if tokens.get('account_id'):
    headers['ChatGPT-Account-Id'] = tokens['account_id']
request = urllib.request.Request('https://chatgpt.com/backend-api/wham/usage', headers=headers)
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
        rate = payload.get('rate_limit') or {}
        windows = [rate[k] for k in ('primary_window', 'secondary_window') if isinstance(rate.get(k), dict)]
        print(json.dumps({'status': response.status, 'top_level_fields': sorted(payload),
                          'window_count': len(windows),
                          'window_fields': [sorted(w) for w in windows],
                          'has_account_identity': bool(payload.get('account_id')),
                          'note': 'No credentials, identities, or quota values persisted.'}, ensure_ascii=False))
except urllib.error.HTTPError as error:
    print(json.dumps({'status': error.code, 'note': 'Response body omitted to protect account data.'}))
    raise SystemExit(1)
except (urllib.error.URLError, TimeoutError):
    raise SystemExit('Network probe failed. No credentials or response body logged.')
