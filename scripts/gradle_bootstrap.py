#!/usr/bin/env python3
"""Small checksum-pinned Gradle launcher, not the official Gradle wrapper JAR.

Requires Python 3.10+, JDK 17+, an installed Android SDK, and internet access on
first use. Does not install an SDK, accept licenses, or change your Java setup.
"""
from __future__ import annotations
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

VERSION='8.13'
SHA256='20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
URL=f'https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip'


def verify_checksum(path: Path, expected: str=SHA256) -> None:
    h=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''): h.update(block)
    if h.hexdigest()!=expected: raise ValueError('Gradle checksum mismatch; refusing to execute download')


def extract_safe(archive: Path,destination: Path) -> None:
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            name=PurePosixPath(info.filename)
            mode=(info.external_attr>>16)&0xffff
            if name.is_absolute() or '..' in name.parts or '\\' in info.filename or ':' in info.filename or stat.S_ISLNK(mode):
                raise ValueError('Unsafe path in Gradle distribution')
        z.extractall(destination)


def main(argv: list[str]) -> int:
    root=Path(__file__).resolve().parents[1]
    cache=Path(os.environ.get('KAIRO_GRADLE_CACHE',Path.home()/'.cache'/'kairo-body-gradle'))
    binary=cache/f'gradle-{VERSION}'/'bin'/('gradle.bat' if os.name=='nt' else 'gradle')
    if not binary.is_file():
        cache.mkdir(parents=True,exist_ok=True)
        # Only extract after validating the checksum against the official release.
        with tempfile.TemporaryDirectory(prefix='install-',dir=cache) as tmp:
            folder=Path(tmp); archive=folder/'gradle.zip'
            print(f'Downloading checksum-pinned Gradle {VERSION}…',flush=True)
            with urllib.request.urlopen(URL,timeout=60) as source,archive.open('wb') as target:
                shutil.copyfileobj(source,target)
            verify_checksum(archive)
            extract_safe(archive,folder/'unpacked')
            source=folder/'unpacked'/f'gradle-{VERSION}'
            if not (source/'bin'/'gradle').is_file(): raise ValueError('Invalid Gradle distribution layout')
            if not (cache/f'gradle-{VERSION}').exists(): shutil.move(str(source),cache)
    if os.name!='nt': binary.chmod(binary.stat().st_mode|stat.S_IXUSR)
    return subprocess.call([str(binary),*argv],cwd=root)

if __name__=='__main__':
    try: raise SystemExit(main(sys.argv[1:]))
    except (OSError,ValueError,zipfile.BadZipFile) as exc:
        print(f'Build launcher failed: {exc}\nInstall JDK 17 and Android SDK platform 36 / build-tools 35.0.0; check network access.',file=sys.stderr)
        raise SystemExit(1)
