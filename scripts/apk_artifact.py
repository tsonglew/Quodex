#!/usr/bin/env python3
"""Package an APK with provenance, or verify a downloaded deployment artifact."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


def verify(directory, source_sha):
    directory = Path(directory)
    metadata = json.loads((directory / 'metadata.json').read_text())
    if metadata.get('schema') != 1 or metadata.get('source_sha') != source_sha:
        raise ValueError('Artifact source does not match the tested commit')
    if not re.fullmatch(r'[0-9a-f]{40}', source_sha):
        raise ValueError('Invalid source SHA')
    if not re.fullmatch(r'[0-9a-f]{40}', metadata.get('build_sha', '')):
        raise ValueError('Invalid build SHA')
    if metadata.get('application_id') != 'com.tsonglew.quodex':
        raise ValueError('Unexpected application ID')
    apk = directory / 'quodex.apk'
    if apk.is_symlink() or not apk.is_file() or not apk.read_bytes().startswith(b'PK'):
        raise ValueError('Missing or invalid APK')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if metadata.get('sha256') != digest:
        raise ValueError('APK checksum mismatch')
    if (directory / 'SHA256SUMS').read_text() != f'{digest}  quodex.apk\n':
        raise ValueError('Checksum manifest mismatch')
    return metadata


def package(apk, output_metadata, destination, source_sha, build_sha):
    if not all(re.fullmatch(r'[0-9a-f]{40}', value) for value in [source_sha, build_sha]):
        raise ValueError('Invalid commit SHA')
    built = json.loads(Path(output_metadata).read_text())
    elements = built['elements']
    if len(elements) != 1:
        raise ValueError('Expected one universal APK')
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, destination / 'quodex.apk')
    digest = hashlib.sha256((destination / 'quodex.apk').read_bytes()).hexdigest()
    metadata = dict(schema=1, source_sha=source_sha, build_sha=build_sha,
                    application_id=built['applicationId'], version_name=elements[0]['versionName'],
                    version_code=elements[0]['versionCode'], sha256=digest)
    (destination / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (destination / 'SHA256SUMS').write_text(f'{digest}  quodex.apk\n')
    verify(destination, source_sha)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('package')
    for name in ['apk', 'output-metadata', 'destination', 'source-sha', 'build-sha']:
        create.add_argument('--' + name, required=True)
    check = commands.add_parser('verify')
    check.add_argument('--directory', required=True)
    check.add_argument('--source-sha', required=True)
    args = vars(parser.parse_args())
    command = args.pop('command')
    if command == 'package':
        package(**args)
    else:
        print(json.dumps(verify(**args)))
