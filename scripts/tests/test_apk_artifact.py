import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('apk_artifact', Path(__file__).parents[1] / 'apk_artifact.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'built.apk'
        self.apk.write_bytes(b'PK\x03\x04test-apk')
        self.output = self.root / 'output-metadata.json'
        self.output.write_text(json.dumps(dict(applicationId='com.tsonglew.quodex', elements=[dict(versionName='0.1.0-alpha04', versionCode=4)])))
        self.directory = self.root / 'artifact'
        module.package(self.apk, self.output, self.directory, 'a' * 40, 'b' * 40)

    def test_artifact_preserves_build_and_source_commits(self):
        metadata = module.verify(self.directory, 'a' * 40)
        self.assertEqual('b' * 40, metadata['build_sha'])
        self.assertEqual('0.1.0-alpha04', metadata['version_name'])

    def test_tampered_apk_is_rejected(self):
        (self.directory / 'quodex.apk').write_bytes(b'PKtampered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            module.verify(self.directory, 'a' * 40)

    def test_artifact_from_other_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'source'):
            module.verify(self.directory, 'c' * 40)

    def test_symlink_cannot_be_used_as_apk(self):
        (self.directory / 'quodex.apk').unlink()
        (self.directory / 'quodex.apk').symlink_to(self.apk)
        with self.assertRaisesRegex(ValueError, 'APK'):
            module.verify(self.directory, 'a' * 40)

    def test_wrong_checksum_manifest_is_rejected(self):
        (self.directory / 'SHA256SUMS').write_text('wrong checksum\n')
        with self.assertRaisesRegex(ValueError, 'manifest'):
            module.verify(self.directory, 'a' * 40)

    def test_other_application_is_rejected(self):
        metadata = json.loads((self.directory / 'metadata.json').read_text())
        metadata['application_id'] = 'other.application'
        (self.directory / 'metadata.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, 'application'):
            module.verify(self.directory, 'a' * 40)
