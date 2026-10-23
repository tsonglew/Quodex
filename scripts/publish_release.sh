#!/usr/bin/env bash
# Runs only after the version/source check and every CI gate have passed.
set -euo pipefail
umask 077
: "${ANDROID_KEYSTORE_BASE64:?Missing environment signing key}"
: "${ANDROID_STORE_PASSWORD:?Missing store password}"
: "${ANDROID_KEY_ALIAS:?Missing signing alias}"
: "${ANDROID_KEY_PASSWORD:?Missing key password}"
: "${ANDROID_SIGNING_CERT_SHA256:?Missing expected signing certificate fingerprint}"
: "${RELEASE_TAG:?Missing release tag}"
: "${RELEASE_SHA:?Missing source SHA}"
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$ ]]
work_dir="$(mktemp -d)"
trap 'rm -r "$work_dir"' EXIT
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$work_dir/release.keystore"
tools="$ANDROID_HOME/build-tools/35.0.0"
"$tools/zipalign" -f -P 16 4 unsigned/app-release-unsigned.apk "$work_dir/aligned.apk"
mkdir -p artifacts/release
apk="artifacts/release/Quodex-${RELEASE_TAG#v}.apk"
"$tools/apksigner" sign --ks "$work_dir/release.keystore" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_STORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$apk" "$work_dir/aligned.apk"
"$tools/apksigner" verify --verbose --print-certs "$apk" > "$work_dir/certificate.txt"
"$tools/zipalign" -c -P 16 4 "$apk"
python3 - "$apk" "$work_dir/certificate.txt" <<'PY'
import hashlib, json, os, re, sys
from pathlib import Path
apk = Path(sys.argv[1])
certificate = Path(sys.argv[2]).read_text()
actual = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', certificate)[1].lower()
expected = os.environ['ANDROID_SIGNING_CERT_SHA256'].replace(':', '').lower()
assert re.fullmatch(r'[0-9a-f]{64}', expected) and actual == expected, 'Signing certificate mismatch'
assert re.fullmatch(r'[0-9a-f]{40}', os.environ['RELEASE_SHA']), 'Invalid source SHA'
metadata = json.loads(Path('unsigned/output-metadata.json').read_text())
assert metadata['applicationId'] == 'com.tsonglew.quodex'
assert metadata['elements'][0]['versionName'] == os.environ['RELEASE_TAG'][1:]
version_code = metadata['elements'][0]['versionCode']
assert type(version_code) is int and 1 <= version_code <= 2100000000, 'Invalid version code'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(apk.parent / 'SHA256SUMS').write_text(f'{digest}  {apk.name}\n')
provenance = dict(
    source_sha=os.environ['RELEASE_SHA'], tag=os.environ['RELEASE_TAG'],
    mode='signing-check' if os.environ.get('RELEASE_SIGN_ONLY') == 'true' else 'release',
    version_code=version_code, sha256=digest, signing_cert_sha256=actual)
(apk.parent / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
# The public Releases list includes this block, so clients can check versions
# without downloading every release's provenance asset. Never use run IDs as versions.
update = dict(schema=1, version_code=provenance['version_code'], version_name=provenance['tag'][1:],
              source_sha=provenance['source_sha'], signing_cert_sha256=provenance['signing_cert_sha256'],
              apk_name=apk.name)
(apk.parent / 'release-notes.md').write_text(
    'Android 10+。Codex 额度监控与桌面小组件，实验性兼容接入。\n\n'
    'Quodex 使用新的 Android applicationId com.tsonglew.quodex。旧版 QuotaPal 不会原地升级；旧版更新检查也无法跟随 GitHub 仓库改名跳转，请从新的 Quodex Releases 页面手动下载。安装后需要重新连接账号，旧数据不会自动迁移。两者可暂时并存，确认新版本正常后再手动卸载旧版。\n\n'
    '后台更新可能延迟；请核对最后成功更新时间。此版本使用固定发布签名，'
    '与 CI debug APK 的签名不同；首次切换渠道需要卸载 debug 版并重新连接。\n\n'
    f"源提交：{os.environ['RELEASE_SHA']}\n\n"
    '安装前可使用 SHA256SUMS 核对 APK；签名指纹与来源见 provenance.json。\n\n'
    '<!-- quodex-update\n' + json.dumps(update, separators=(',', ':')) + '\n-->\n')
PY
if [[ "${RELEASE_SIGN_ONLY:-false}" == true ]]; then
  printf 'Verified fixed-signature APK for %s at commit %s; no Release published\n' "$RELEASE_TAG" "$RELEASE_SHA"
  exit 0
fi
if draft="$(gh release view "$RELEASE_TAG" --json isDraft --jq .isDraft 2>/dev/null)"; then
  test "$draft" = true || { echo 'Release already published; refusing to replace its assets' >&2; exit 1; }
else
  release_args=(--draft --verify-tag --title "Quodex ${RELEASE_TAG#v}" --notes-file artifacts/release/release-notes.md)
  if [[ "$RELEASE_TAG" == *-* ]]; then release_args+=(--prerelease); fi
  gh release create "$RELEASE_TAG" "${release_args[@]}"
fi
gh release upload "$RELEASE_TAG" "$apk" artifacts/release/SHA256SUMS artifacts/release/provenance.json --clobber
edit_args=(--draft=false --notes-file artifacts/release/release-notes.md)
if [[ "$RELEASE_TAG" == *-* ]]; then edit_args+=(--prerelease); else edit_args+=(--prerelease=false); fi
gh release edit "$RELEASE_TAG" "${edit_args[@]}"
printf 'Published %s at commit %s\n' "$RELEASE_TAG" "$RELEASE_SHA"
