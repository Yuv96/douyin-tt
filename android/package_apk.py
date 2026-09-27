"""Package ABI-specific unsigned APKs on GitHub, without retaining old signatures."""
import copy
import os
from pathlib import Path
import re
import shutil
import zipfile

SUPPORTED_ABIS = frozenset(('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'))


def signature_entry(name):
    upper = name.upper()
    if not upper.startswith('META-INF/'):
        return False
    relative = upper[len('META-INF/'):]
    # JAR/v1 signature metadata only. ServiceLoader and dependency notices survive.
    return relative == 'MANIFEST.MF' or ('/' not in relative and (
        relative.endswith(('.SF', '.RSA', '.DSA', '.EC')) or relative.startswith('SIG-')))


def dex_index(path):
    if path.name == 'classes.dex':
        return 1
    match = re.fullmatch(r'classes([1-9][0-9]*)\.dex', path.name)
    if match and int(match.group(1)) >= 2:
        return int(match.group(1))
    raise RuntimeError('Unexpected D8 output filename: ' + path.name)


def package_variant(base_apk, dexdir, output_unsigned, abis):
    """Repack a resource-linked APK with final DEX and only the selected native ABIs.

    All shipped .so files use DEFLATED compression. The merged Android manifest
    must set extractNativeLibs=true, so Android installs them in nativeLibraryDir.
    Other source members retain their original storage method, including
    resources.arsc. The caller aligns and signs the result.
    """
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('APK packaging may only run on GitHub Actions')
    if isinstance(abis, str):
        raise TypeError('abis must be a collection of complete ABI names')
    selected = frozenset(abis)
    if not selected or not selected <= SUPPORTED_ABIS:
        raise ValueError('Unsupported or empty APK ABI selection')
    base_apk, dexdir, output_unsigned = map(Path, (base_apk, dexdir, output_unsigned))
    if base_apk.resolve() == output_unsigned.resolve():
        raise ValueError('Unsigned output must differ from its source APK')
    dex_files = sorted(dexdir.glob('*.dex'), key=dex_index)
    if not dex_files or [dex_index(path) for path in dex_files] != list(range(1, len(dex_files) + 1)):
        raise RuntimeError('D8 output must contain a contiguous classes.dex sequence')
    output_unsigned.parent.mkdir(parents=True, exist_ok=True)
    native_counts = {abi: 0 for abi in selected}
    written = set()
    try:
        with zipfile.ZipFile(base_apk) as source, zipfile.ZipFile(
                output_unsigned, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            for member in source.infolist():
                name = member.filename
                if member.is_dir() or re.fullmatch(r'classes[0-9]*\.dex', name) or signature_entry(name):
                    continue
                if name in written:
                    raise RuntimeError('Duplicate member in source APK: ' + name)
                info = copy.copy(member)
                if name.startswith('lib/'):
                    parts = name.split('/')
                    if len(parts) < 3 or parts[1] not in selected:
                        continue
                    if name.endswith('.so'):
                        info.compress_type = zipfile.ZIP_DEFLATED
                        native_counts[parts[1]] += 1
                # ZipInfo retains source timestamps, attributes and compression for resources.
                # The copied file_size lets zipfile avoid unnecessary ZIP64 local headers.
                with source.open(member) as incoming, output.open(info, 'w') as outgoing:
                    shutil.copyfileobj(incoming, outgoing, length=1024 * 1024)
                written.add(name)
            if any(count == 0 for count in native_counts.values()):
                raise RuntimeError('Selected ABI has no packaged native libraries')
            for abi in selected:
                if not all('lib/' + abi + '/' + name in written for name in ('libvlc.so', 'libvlcjni.so')):
                    raise RuntimeError('Selected ABI is missing VLC native libraries: ' + abi)
            for dex in dex_files:
                output.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    except Exception:
        output_unsigned.unlink(missing_ok=True)
        raise
    print('PACKAGED', output_unsigned.name, 'ABIs=' + ','.join(sorted(selected)),
          'bytes=' + str(output_unsigned.stat().st_size), 'native=' + str(native_counts))
    return output_unsigned
