"""Pinned certificate checks for local build artifacts; execute only on GitHub runners."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

EXPECTED_SHA256 = 'a9a53928e2d288aba0ed134d1c92bf1e8ba29886f22b23578f56cd34ef1d32e6'
ROOT = Path(__file__).resolve().parent.parent


def certificate(apk, signer):
    result = subprocess.check_output([
        str(signer), 'verify', '--verbose', '--print-certs', '--min-sdk-version', '21',
        str(apk)], text=True)
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', result, re.M)
    if len(digests) != 1:
        raise RuntimeError('Expected exactly one verified APK signing certificate')
    return digests[0]


def require_pinned_signer(apk, signer):
    actual = certificate(apk, signer)
    print('APK certificate SHA-256:', actual)
    if actual != EXPECTED_SHA256:
        raise RuntimeError('Signing certificate differs from the pinned certificate; artifact rejected')
    return actual


def require_upgrade_signer(regression_apk, production_apk, signer):
    """Compare this run's artifacts to the pinned certificate, without remote APK baselines."""
    if not Path(regression_apk).is_file():
        raise RuntimeError('Current regression APK missing; upgrade baseline must be built in this run')
    baseline = require_pinned_signer(regression_apk, signer)
    actual = require_pinned_signer(production_apk, signer)
    if baseline != actual:
        raise RuntimeError('Current regression and production APK signing certificates differ')
    return actual


def main():
    parser = argparse.ArgumentParser(description='Verify current APK artifacts against the pinned signer')
    parser.add_argument('apks', type=Path, nargs='+')
    args = parser.parse_args()
    signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/apksigner'
    report = {apk.name: {'certificate_sha256': require_pinned_signer(apk, signer)} for apk in args.apks}
    evidence = ROOT / 'evidence'
    evidence.mkdir(exist_ok=True)
    (evidence / 'current-signatures.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS current artifacts use the pinned certificate')


if __name__ == '__main__':
    main()
