#!/usr/bin/env bash
set -euo pipefail
# Read-only Linux supply-chain gate. No extraction, download, build, or installation.
# Usage: archive archiveSHA sourceDir depsDir outputsDir trustedManifest manifestSHA
[[ $# == 7 ]] || { echo 'expected 7 arguments; see deploy/README.md' >&2; exit 64; }
exec python3 -I -B - "$@" <<'PY'
import hashlib, os, pathlib, re, stat, sys, tarfile

def require(ok):
    if not ok:
        raise ValueError('SUPPLY_CHAIN_UNVERIFIED')

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for data in iter(lambda: f.read(1048576), b''):
            h.update(data)
    return h.hexdigest()

def regular(path):
    p = pathlib.Path(path).absolute()
    require(all(not x.is_symlink() for x in (p, *p.parents)))
    require(stat.S_ISREG(p.stat().st_mode))
    return p

try:
    archive, archive_sha, source, deps, outputs, manifest, manifest_sha = sys.argv[1:]
    require(all(re.fullmatch('[0-9a-f]{64}', x) for x in (archive_sha, manifest_sha)))
    require(digest(regular(archive)) == archive_sha)
    require(digest(regular(manifest)) == manifest_sha)
    expected = {'source': {}, 'deps': {}, 'outputs': {}}
    for line in pathlib.Path(manifest).read_text(encoding='utf-8').splitlines():
        sha, rel = line.split('  ', 1)
        require(re.fullmatch('[0-9a-f]{64}', sha))
        parts = pathlib.PurePosixPath(rel).parts
        require(len(parts) >= 2 and parts[0] in expected)
        require(not rel.startswith('/') and all(x not in ('', '.', '..') for x in rel.split('/')))
        name = '/'.join(parts[1:])
        require(name not in expected[parts[0]])
        expected[parts[0]][name] = sha
    for group, root in zip(expected, (source, deps, outputs)):
        root = pathlib.Path(root).absolute()
        require(root.is_dir() and all(not p.is_symlink() for p in (root, *root.parents)))
        actual = {}
        for base, dirs, files in os.walk(root, followlinks=False):
            for name in dirs:
                require(not pathlib.Path(base, name).is_symlink())
            for name in files:
                p = regular(pathlib.Path(base, name))
                actual[p.relative_to(root).as_posix()] = digest(p)
        require(bool(expected[group]) and actual == expected[group])
    # Source tar is a flat reviewed tree: reject links, devices, traversal and duplicates.
    seen = {}
    with tarfile.open(archive, 'r:*') as tf:
        for member in tf:
            name = member.name.rstrip('/')
            require(name and not name.startswith('/') and all(x not in ('', '.', '..') for x in name.split('/')))
            require(member.isfile() or member.isdir())
            if member.isfile():
                require(name not in seen)
                h = hashlib.sha256()
                with tf.extractfile(member) as f:
                    for data in iter(lambda: f.read(1048576), b''):
                        h.update(data)
                seen[name] = h.hexdigest()
    require(seen == expected['source'])
    print('SUPPLY_CHAIN_HASHES_VERIFIED (not deployment acceptance)')
except Exception:
    print('SUPPLY_CHAIN_UNVERIFIED', file=sys.stderr)
    sys.exit(1)
PY
