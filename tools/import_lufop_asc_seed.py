"""Convert a user-supplied Lufop ASC ZIP to the existing offline seed format.

Only ESFixeES<number>.asc is accepted. Other categories are not fixed cameras.
No network access or publication. The archive timestamp is not a data freshness claim.
"""
import argparse
import csv
import gzip
import hashlib
import io
import math
from pathlib import Path
import re
import zipfile


def convert(archive, target):
    old = {}
    if target.exists():
        for line in gzip.decompress(target.read_bytes()).decode('utf-8').splitlines():
            row = line.split('\t')
            if len(row) >= 6 and row[0].startswith('LUFOP-'):
                old[row[4]] = row
    records = {}
    categories = {}
    with zipfile.ZipFile(archive) as bundle:
        for name in sorted(bundle.namelist()):
            match = re.fullmatch(r'ESFixeES(\d+)\.asc', name)
            if not match:
                continue
            count = 0
            for row in csv.reader(io.StringIO(bundle.read(name).decode('utf-8-sig')),
                                  skipinitialspace=True):
                if not row:
                    continue
                if len(row) != 3:
                    raise ValueError(f'Invalid ASC row in {name}: {row!r}')
                lon, lat = map(float, row[:2])
                if not (math.isfinite(lat) and math.isfinite(lon)
                        and 27 <= lat <= 44.5 and -19 <= lon <= 5):
                    raise ValueError(f'Invalid Spanish coordinates in {name}')
                geometry = f'{lat:.6f},{lon:.6f}'
                # Preserve known names only at the exact same six-decimal coordinate.
                previous = old.get(geometry)
                identity = 'LUFOP-' + hashlib.sha256(geometry.encode()).hexdigest()[:20].upper()
                records[geometry] = previous or [identity, 'FIJO', '', '0', geometry, 'ESPANA']
                count += 1
            categories[name] = count
    if not records:
        raise ValueError('No Spanish fixed cameras found; original seed untouched')
    header = '# schema=e87-lufop-radar-v1 source=Lufop.net country=ES format=ASC\n'
    output = header + ''.join('\t'.join(records[key][:6]) + '\n' for key in sorted(records))
    packed = gzip.compress(output.encode('utf-8'), mtime=0)
    assert len([x for x in gzip.decompress(packed).splitlines() if x.startswith(b'LUFOP-')]) == len(records)
    temporary = target.with_suffix('.pending')
    temporary.write_bytes(packed)
    temporary.replace(target)
    new = set(records)
    print({'previous': len(old), 'raw_fixed': sum(categories.values()), 'unique_fixed': len(new),
           'same_coordinate': len(new & old.keys()), 'added_coordinates': len(new-old.keys()),
           'removed_coordinates': len(old.keys()-new), 'bytes': len(packed),
           'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest()})
    print(categories)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('target', type=Path)
    args = parser.parse_args()
    convert(args.archive, args.target)
