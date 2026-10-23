"""Exercise release decisions with command doubles; cryptographic checks are separate."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'publish_release.sh'


class ReleaseContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'unsigned').mkdir()
        (self.root / 'unsigned/app-release-unsigned.apk').write_bytes(b'PKtest-apk')
        (self.root / 'unsigned/output-metadata.json').write_text(json.dumps(dict(
            applicationId='com.tsonglew.quodex', elements=[dict(versionName='0.1.0-alpha04', versionCode=4)])))
        self.tools = self.root / 'sdk/build-tools/35.0.0'
        self.tools.mkdir(parents=True)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.executable(self.tools / 'zipalign', '''import shutil,sys
if '-c' not in sys.argv: shutil.copyfile(sys.argv[-2],sys.argv[-1])''')
        self.executable(self.tools / 'apksigner', '''import shutil,sys
if sys.argv[1]=='sign': shutil.copyfile(sys.argv[-1],sys.argv[sys.argv.index('--out')+1])
else: print('Signer #1 certificate SHA-256 digest: '+ 'a'*64)''')
        self.executable(self.bin / 'gh', '''import json,os,sys
with open('gh-calls.jsonl','a') as f: f.write(json.dumps(sys.argv[1:])+'\\n')
if sys.argv[1:3]==['release','view']:
    state=os.environ.get('TEST_DRAFT','absent')
    if state=='absent': sys.exit(1)
    print(state)''')
        self.env = dict(os.environ, PATH=f'{self.bin}{os.pathsep}{os.environ["PATH"]}',
                        ANDROID_HOME=str(self.root / 'sdk'), ANDROID_KEYSTORE_BASE64=base64.b64encode(b'test-key').decode(),
                        ANDROID_STORE_PASSWORD='test-password', ANDROID_KEY_ALIAS='test', ANDROID_KEY_PASSWORD='test-password',
                        ANDROID_SIGNING_CERT_SHA256='a'*64, RELEASE_TAG='v0.1.0-alpha04', RELEASE_SHA='b'*40)

    def executable(self, path, body):
        path.write_text('#!/usr/bin/env python3\n'+body+'\n')
        path.chmod(0o700)

    def run_release(self):
        return subprocess.run(['bash', str(SCRIPT)], cwd=self.root, env=self.env, capture_output=True, text=True)

    def calls(self):
        path = self.root / 'gh-calls.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def update_metadata(self):
        notes = (self.root / 'artifacts/release/release-notes.md').read_text()
        blocks = re.findall(r'<!-- quodex-update\n(.*?)\n-->', notes, flags=re.DOTALL)
        self.assertEqual(1, len(blocks))
        return json.loads(blocks[0])

    def test_upload_finishes_before_draft_is_published(self):
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        self.assertEqual(['view', 'create', 'upload', 'edit'], [c[1] for c in calls])
        self.assertIn('--draft', calls[1])
        self.assertIn('--prerelease', calls[1])
        self.assertIn('--draft=false', calls[-1])
        self.assertTrue((self.root / 'artifacts/release/provenance.json').exists())
        self.assertEqual(dict(schema=1, version_code=4, version_name='0.1.0-alpha04', source_sha='b'*40,
                              signing_cert_sha256='a'*64, apk_name='Quodex-0.1.0-alpha04.apk'),
                         self.update_metadata())

    def test_signing_check_produces_assets_without_github_mutation(self):
        self.env['RELEASE_SIGN_ONLY'] = 'true'
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.calls())
        provenance = json.loads((self.root / 'artifacts/release/provenance.json').read_text())
        self.assertEqual('signing-check', provenance['mode'])
        self.assertEqual('a'*64, provenance['signing_cert_sha256'])
        update = self.update_metadata()
        self.assertEqual(provenance['signing_cert_sha256'], update['signing_cert_sha256'])
        self.assertEqual(provenance['source_sha'], update['source_sha'])
        self.assertEqual(provenance['version_code'], update['version_code'])

    def test_invalid_version_codes_never_call_github(self):
        for code in (0, -1, True, '4', 4.5, 2100000001):
            with self.subTest(code=code):
                (self.root / 'unsigned/output-metadata.json').write_text(json.dumps(dict(
                    applicationId='com.tsonglew.quodex',
                    elements=[dict(versionName='0.1.0-alpha04', versionCode=code)])))
                result = self.run_release()
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Invalid version code', result.stderr)
                self.assertEqual([], self.calls())

    def test_signing_check_still_rejects_certificate_mismatch(self):
        self.env['RELEASE_SIGN_ONLY'] = 'true'
        self.env['ANDROID_SIGNING_CERT_SHA256'] = 'c'*64
        self.assertNotEqual(0, self.run_release().returncode)
        self.assertEqual([], self.calls())

    def test_certificate_mismatch_never_calls_github(self):
        self.env['ANDROID_SIGNING_CERT_SHA256'] = 'c'*64
        self.assertNotEqual(0, self.run_release().returncode)
        self.assertEqual([], self.calls())

    def test_published_release_cannot_be_overwritten(self):
        self.env['TEST_DRAFT'] = 'false'
        self.assertNotEqual(0, self.run_release().returncode)
        self.assertEqual(['view'], [c[1] for c in self.calls()])

    def test_missing_secret_never_calls_github(self):
        self.env['ANDROID_KEYSTORE_BASE64'] = ''
        self.assertNotEqual(0, self.run_release().returncode)
        self.assertEqual([], self.calls())

    def test_wrong_tag_never_calls_github(self):
        self.env['RELEASE_TAG'] = 'v0.1.0-alpha05'
        self.assertNotEqual(0, self.run_release().returncode)
        self.assertEqual([], self.calls())
