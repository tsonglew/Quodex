"""Publish a small static update index only after the signed Release is public."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

BRANCH = 'update-feed'
FILE = 'updates.json'
METADATA = re.compile(r'<!-- quodex-update\n(.*?)\n-->', re.DOTALL)


def metadata(release):
    blocks = METADATA.findall(release.get('body', ''))
    if len(blocks) != 1:
        raise ValueError('Invalid update metadata')
    value = json.loads(blocks[0])
    code = value.get('version_code')
    if type(code) is not int or not 1 <= code <= 2100000000:
        raise ValueError('Invalid version code')
    return value


def publish(directory, repo, tag, source, execute=subprocess.run):
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', repo) or not re.fullmatch(r'(?:build-\d+-\d+|v\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?)', tag):
        raise ValueError('Invalid release identity')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('Invalid source')

    def gh(*args, payload=None):
        if payload is None:
            return execute(['gh', *args], check=True, capture_output=True, text=True).stdout
        with tempfile.NamedTemporaryFile(mode='w', prefix='quodex-update-feed-', suffix='.json') as file:
            json.dump(payload, file)
            file.flush()
            return execute(['gh', *args, '--input', file.name], check=True, capture_output=True, text=True).stdout

    def missing(error):
        return '(HTTP 404)' in (error.stderr or '')

    root = Path(directory)
    provenance = json.loads((root / 'provenance.json').read_text())
    release = json.loads(gh('api', f'repos/{repo}/releases/tags/{tag}'))
    info = metadata(release)
    version = provenance['tag'].removeprefix('v')
    apk = root / f'Quodex-{version}.apk'
    expected = dict(schema=1, version_code=provenance['version_code'], version_name=version,
                    source_sha=source, signing_cert_sha256=provenance['signing_cert_sha256'], apk_name=apk.name)
    if (info != expected or type(info.get('schema')) is not int or provenance['source_sha'] != source
            or provenance['mode'] != ('release' if tag.startswith('v') else 'signing-check')
            or (tag.startswith('v') and tag != provenance['tag']) or apk.is_symlink() or not apk.is_file()):
        raise ValueError('Release does not match verified provenance')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != provenance['sha256']:
        raise ValueError('APK checksum mismatch')
    base = f'https://github.com/{repo}/releases'
    if (release.get('draft') is not False or not release.get('published_at')
            or release.get('tag_name') != tag or release.get('html_url') != f'{base}/tag/{tag}'):
        raise ValueError('Release is not public')
    assets = [a for a in release.get('assets', []) if a.get('name') == apk.name]
    if len(assets) != 1:
        raise ValueError('Missing or duplicate APK asset')
    asset = assets[0]
    if (asset.get('state') != 'uploaded' or asset.get('size') != apk.stat().st_size
            or asset.get('digest') != f'sha256:{digest}'
            or asset.get('browser_download_url') != f'{base}/download/{tag}/{apk.name}'):
        raise ValueError('Public APK asset does not match verified bytes')
    entry = {key: release[key] for key in ('tag_name', 'draft', 'html_url', 'body')}
    entry['assets'] = [{key: asset[key] for key in ('name', 'state', 'size', 'digest', 'browser_download_url')}]
    content = base64.b64encode((json.dumps([entry], ensure_ascii=False, separators=(',', ':')) + '\n').encode()).decode()
    # The branch is publication metadata, not a source or deployment branch.
    try:
        gh('api', f'repos/{repo}/git/ref/heads/{BRANCH}')
    except subprocess.CalledProcessError as error:
        if not missing(error):
            raise
        try:
            gh('api', f'repos/{repo}/git/refs', '--method', 'POST',
               payload=dict(ref=f'refs/heads/{BRANCH}', sha=source))
        except subprocess.CalledProcessError as conflict:
            if '(HTTP 422)' not in (conflict.stderr or ''):
                raise
            gh('api', f'repos/{repo}/git/ref/heads/{BRANCH}')
    endpoint = f'repos/{repo}/contents/{FILE}'
    for attempt in range(3):
        sha = None
        try:
            existing = json.loads(gh('api', f'{endpoint}?ref={BRANCH}'))
            old = json.loads(base64.b64decode(existing['content']))
            if not isinstance(old, list) or len(old) != 1:
                raise ValueError('Invalid existing update index')
            # A slow/older release job must never roll the index back.
            old_code = metadata(old[0])['version_code']
            # A version tag can promote the identical source/version/signing
            # identity, but an automatic build cannot demote a tagged release.
            promote = (old_code == info['version_code'] and tag.startswith('v')
                       and str(old[0].get('tag_name', '')).startswith('build-')
                       and metadata(old[0]) == info)
            if old_code > info['version_code'] or old_code == info['version_code'] and not promote:
                return False
            sha = existing['sha']
        except subprocess.CalledProcessError as error:
            if not missing(error):
                raise
        payload = dict(message=f'Publish update index for {tag}', branch=BRANCH, content=content)
        if sha:
            payload['sha'] = sha
        try:
            gh('api', endpoint, '--method', 'PUT', payload=payload)
            return True
        except subprocess.CalledProcessError as error:
            if '(HTTP 409)' not in (error.stderr or '') or attempt == 2:
                raise
    raise RuntimeError('Update index was not published')


if __name__ == '__main__':
    repo = os.environ['GITHUB_REPOSITORY']
    if os.environ.get('GITHUB_EVENT_NAME') == 'push' and os.environ.get('GITHUB_REF') == 'refs/heads/master':
        tag = f"build-{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}"
        source = os.environ['GITHUB_SHA']
    elif (os.environ.get('GITHUB_EVENT_NAME') in ('push', 'workflow_dispatch')
          and os.environ.get('GITHUB_REF') == 'refs/tags/' + os.environ.get('RELEASE_TAG', '')):
        tag, source = os.environ['RELEASE_TAG'], os.environ['RELEASE_SHA']
    else:
        raise SystemExit('Update index publication requires a master push or a validated release tag')
    changed = publish('artifacts/release', repo, tag, source)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f'\nStatic update index: {"published" if changed else "already current"}.\n')
