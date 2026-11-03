import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('publish_update_feed', Path(__file__).parents[1] / 'publish_update_feed.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PublishUpdateFeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tag = 'build-123-1'
        self.apk = self.root / 'Quodex-0.1.0-alpha33.apk'
        self.apk.write_bytes(b'fixture-apk')
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.provenance = dict(tag='v0.1.0-alpha33', source_sha='a'*40, mode='signing-check',
                               sha256=self.digest, version_code=33, signing_cert_sha256='b'*64)
        self.info = dict(schema=1, version_code=33, version_name='0.1.0-alpha33', source_sha='a'*40,
                         signing_cert_sha256='b'*64, apk_name=self.apk.name)
        self.release = dict(tag_name=self.tag, draft=False, published_at='2026-11-03T00:00:00Z',
                            html_url=f'https://github.com/owner/repo/releases/tag/{self.tag}',
                            body='Fixture\n<!-- quodex-update\n'+json.dumps(self.info)+'\n-->',
                            assets=[dict(name=self.apk.name, state='uploaded', size=self.apk.stat().st_size,
                                         digest='sha256:'+self.digest,
                                         browser_download_url=f'https://github.com/owner/repo/releases/download/{self.tag}/{self.apk.name}')])
        self.branch = False
        self.existing = None
        self.writes = []
        self.conflict = None

    def http_error(self, command, status):
        raise subprocess.CalledProcessError(1, command, stderr=f'gh: fixture (HTTP {status})')

    def execute(self, command, **kwargs):
        endpoint = command[2]
        method = command[command.index('--method')+1] if '--method' in command else 'GET'
        payload = json.loads(Path(command[command.index('--input')+1]).read_text()) if '--input' in command else None
        if '/releases/tags/' in endpoint:
            result = self.release
        elif '/git/ref/heads/' in endpoint:
            if not self.branch:
                self.http_error(command, 404)
            result = {}
        elif '/git/refs' in endpoint:
            self.assertEqual(dict(ref='refs/heads/update-feed', sha='a'*40), payload)
            self.branch = True
            result = {}
        elif method == 'GET':
            if self.existing is None:
                self.http_error(command, 404)
            result = dict(sha='current-sha', content=base64.b64encode(json.dumps(self.existing).encode()).decode())
        elif method == 'PUT':
            self.writes.append(payload)
            if self.conflict is not None:
                self.existing = self.conflict
                self.conflict = None
                self.http_error(command, 409)
            result = {}
        else:
            raise AssertionError(command)
        return subprocess.CompletedProcess(command, 0, json.dumps(result))

    def publish(self):
        (self.root / 'provenance.json').write_text(json.dumps(self.provenance))
        return module.publish(self.root, 'owner/repo', self.tag, 'a'*40, self.execute)

    def entry(self, code):
        result = copy.deepcopy(self.release)
        result['body'] = '<!-- quodex-update\n'+json.dumps(dict(self.info, version_code=code))+'\n-->'
        return [result]

    def test_public_verified_release_creates_branch_and_exact_index(self):
        self.assertTrue(self.publish())
        self.assertTrue(self.branch)
        self.assertEqual('update-feed', self.writes[0]['branch'])
        self.assertNotIn('sha', self.writes[0])
        entry = json.loads(base64.b64decode(self.writes[0]['content']))[0]
        self.assertEqual(self.release['assets'], entry['assets'])
        self.assertEqual(self.release['body'], entry['body'])
        self.assertEqual(self.tag, entry['tag_name'])

    def test_existing_index_uses_compare_and_swap(self):
        self.branch, self.existing = True, self.entry(32)
        self.assertTrue(self.publish())
        self.assertEqual('current-sha', self.writes[0]['sha'])

    def test_older_or_equal_build_cannot_replace_index(self):
        for code in (33, 34):
            self.existing = self.entry(code)
            self.assertFalse(self.publish())
            self.assertEqual([], self.writes)

    def test_conflict_rechecks_newer_index_before_retrying(self):
        self.existing, self.conflict = self.entry(31), self.entry(34)
        self.assertFalse(self.publish())
        self.assertEqual(1, len(self.writes))

    def test_conflict_can_retry_after_older_release(self):
        self.existing, self.conflict = self.entry(31), self.entry(32)
        self.assertTrue(self.publish())
        self.assertEqual(2, len(self.writes))

    def test_invalid_public_asset_never_writes_index(self):
        original = copy.deepcopy(self.release)
        for field, value in [('size', 0), ('digest', 'sha256:'+'c'*64), ('state', 'new'),
                             ('browser_download_url', 'https://elsewhere.invalid/file.apk')]:
            with self.subTest(field=field):
                self.release = copy.deepcopy(original)
                self.release['assets'][0][field] = value
                with self.assertRaises(ValueError):
                    self.publish()
                self.assertFalse(self.branch)
                self.assertEqual([], self.writes)

    def test_draft_missing_asset_and_tampered_metadata_are_rejected(self):
        original = copy.deepcopy(self.release)
        for changes in [dict(draft=True), dict(published_at=None), dict(assets=[]), dict(body='missing'),
                        dict(body=original['body']+original['body']), dict(html_url='https://elsewhere.invalid')]:
            self.release = dict(original, **changes)
            with self.assertRaises(ValueError):
                self.publish()
            self.assertFalse(self.branch)
            self.assertEqual([], self.writes)

    def test_tampered_local_bytes_are_rejected(self):
        self.apk.write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.publish()
        self.assertFalse(self.branch)

    def test_formal_release_requires_matching_tag_and_mode(self):
        old = self.tag
        self.tag = 'v0.1.0-alpha33'
        self.release['tag_name'] = self.tag
        self.release['html_url'] = self.release['html_url'].replace(old, self.tag)
        self.release['assets'][0]['browser_download_url'] = self.release['assets'][0]['browser_download_url'].replace(old, self.tag)
        with self.assertRaisesRegex(ValueError, 'provenance'):
            self.publish()
        self.provenance['mode'] = 'release'
        self.assertTrue(self.publish())

    def test_corrupt_existing_index_is_not_overwritten(self):
        self.existing = []
        with self.assertRaisesRegex(ValueError, 'existing'):
            self.publish()
        self.assertEqual([], self.writes)


if __name__ == '__main__':
    unittest.main()
