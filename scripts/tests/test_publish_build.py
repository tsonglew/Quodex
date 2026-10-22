import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('publish_build', Path(__file__).parents[1] / 'publish_build.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PublishBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'QuotaPal-0.1.0-alpha17.apk'
        self.apk.write_bytes(b'test-signed-apk')
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.provenance = dict(tag='v0.1.0-alpha17', source_sha='a'*40, mode='signing-check', sha256=digest,
                               version_code=17, signing_cert_sha256='b'*64)
        (self.root / 'provenance.json').write_text(json.dumps(self.provenance))
        (self.root / 'SHA256SUMS').write_text(f'{digest}  {self.apk.name}\n')
        self.update = dict(schema=1, version_code=17, version_name='0.1.0-alpha17', source_sha='a'*40,
                           signing_cert_sha256='b'*64, apk_name=self.apk.name)
        self.write_notes()
        self.calls = []

    def write_notes(self):
        (self.root / 'release-notes.md').write_text(
            'Test release notes\n\n<!-- quotapal-update\n' + json.dumps(self.update) + '\n-->\n')

    def execute(self, command, **kwargs):
        self.calls.append(command)
        return subprocess.CompletedProcess(command, 0, '')

    def publish(self, **kwargs):
        return module.publish(self.root, 'a'*40, '123', '2', 'owner/repo', kwargs.get('execute', self.execute))

    def test_source_tag_and_assets_are_published_in_order(self):
        url = self.publish()
        self.assertEqual('https://github.com/owner/repo/releases/tag/build-123-2', url)
        self.assertIn('sha='+'a'*40, self.calls[0])
        self.assertIn('ref=refs/tags/build-123-2', self.calls[0])
        self.assertEqual(['create','upload','edit'], [call[2] for call in self.calls[1:]])
        self.assertIn('--draft', self.calls[1])
        self.assertIn('--prerelease', self.calls[-1])
        self.assertIn('--latest=false', self.calls[-1])
        self.assertNotIn('--clobber', self.calls[2])
        self.assertIn(str(self.apk), self.calls[2])
        self.assertTrue((self.root / 'build-release-notes.md').read_text().startswith(
            (self.root / 'release-notes.md').read_text()))

    def test_update_metadata_must_match_signed_provenance(self):
        original = self.update.copy()
        for field, wrong in dict(version_code=18, version_name='0.1.0-alpha18', source_sha='c'*40,
                                 signing_cert_sha256='c'*64, apk_name='unrelated.apk', schema=True).items():
            with self.subTest(field=field):
                self.update = dict(original, **{field: wrong})
                self.write_notes()
                with self.assertRaisesRegex(ValueError, 'metadata'):
                    self.publish()
                self.assertEqual([], self.calls)

    def test_missing_or_duplicate_update_metadata_never_publishes(self):
        valid = (self.root / 'release-notes.md').read_text()
        for notes in ('No update metadata', valid + valid):
            with self.subTest(notes=notes):
                (self.root / 'release-notes.md').write_text(notes)
                with self.assertRaisesRegex(ValueError, 'metadata'):
                    self.publish()
                self.assertEqual([], self.calls)

    def test_invalid_signed_metadata_never_publishes(self):
        original = self.provenance.copy()
        for field, wrong in (('version_code', True), ('version_code', 0), ('version_code', 2100000001),
                             ('signing_cert_sha256', 'not-a-certificate'), ('signing_cert_sha256', None)):
            with self.subTest(field=field, wrong=wrong):
                (self.root / 'provenance.json').write_text(json.dumps(dict(original, **{field: wrong})))
                with self.assertRaisesRegex(ValueError, 'Invalid'):
                    self.publish()
                self.assertEqual([], self.calls)

    def test_tampered_apk_is_never_published(self):
        self.apk.write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.publish()
        self.assertEqual([], self.calls)

    def test_wrong_source_is_never_published(self):
        self.provenance['source_sha'] = 'b'*40
        (self.root / 'provenance.json').write_text(json.dumps(self.provenance))
        with self.assertRaisesRegex(ValueError, 'source'):
            self.publish()
        self.assertEqual([], self.calls)

    def test_upload_failure_leaves_release_unpublished(self):
        def execute(command, **kwargs):
            self.calls.append(command)
            if command[1:3] == ['release','upload']:
                raise subprocess.CalledProcessError(1, command)
            return subprocess.CompletedProcess(command, 0, '')
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish(execute=execute)
        self.assertFalse(any(c[1:3] == ['release','edit'] for c in self.calls))

    def test_existing_tag_stops_without_replacing_release(self):
        def execute(command, **kwargs):
            self.calls.append(command)
            raise subprocess.CalledProcessError(1, command)
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish(execute=execute)
        self.assertEqual(1,len(self.calls))

    def test_invalid_build_identity_has_no_remote_effect(self):
        with self.assertRaisesRegex(ValueError, 'identity'):
            module.publish(self.root, 'a'*40, '../bad', '1', 'owner/repo', self.execute)
        self.assertEqual([], self.calls)
