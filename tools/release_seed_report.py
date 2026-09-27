"""Validate bundled release inputs and record download vs source dates separately."""
import gzip
import hashlib
import json
import re
from pathlib import Path
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
records = []
paths = [*sorted(assets.glob('e87_speed_limits_*.tsv.gz')),
             assets/'e87_osm_radars_alicante.json',
             assets/'e87_fuel_stations_alicante_diesel.json', assets/'e87_lufop_radars_es.dat',
             *sorted((root/'app/build/generated').glob('dgt*Assets/*.xml'))]
for path in paths:
    assert path.exists(), f'Missing seed: {path}'
    raw = path.read_bytes()
    item = dict(file=path.name, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(),
                packaged_mtime_utc=datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat())
    if path.suffix == '.xml':
        tree = ET.fromstring(raw)
        item['source_dates'] = sorted({e.text.strip() for e in tree.iter()
            if e.text and any(key in e.tag.lower()
                              for key in ('publicationtime', 'timestamp', 'datasetcreationtime'))})[:12]
        tags = {e.tag.rsplit('}', 1)[-1] for e in tree.iter()}
        expected = 'GenericSafetyFeature' if 'speed_limits' in path.name else 'predefinedLocation'
        assert expected in tags, f'{path.name} does not contain {expected}'
    elif path.suffix == '.json':
        data = json.loads(raw)
        assert 'remark' not in data, 'Incomplete Overpass response'
        item['source_date'] = data.get('Fecha') or data.get('osm3s',{}).get('timestamp_osm_base')
        item['records'] = len(data.get('elements',data.get('ListaEESSPrecio',[])))
        if 'elements' in data:
            item['camera_nodes'] = sum(e.get('tags',{}).get('highway') == 'speed_camera' for e in data['elements'])
    else:
        lines = gzip.decompress(raw).decode('utf-8').splitlines()
        item['records'] = sum(bool(s) and not s.startswith('#') for s in lines)
        if 'lufop' in path.name: item['status'] = 'Static user-supplied archive from 2026-09-20; not downloaded today'
        else:
            assert lines and 'schema=e87-road-class-seed-v5' in lines[0], f'Invalid road seed: {path.name}'
            item['source_date'] = None
    assert item['bytes'] > 100
    if 'records' in item: assert item['records'] > 0, f'Empty seed: {path.name}'
    records.append(item)
gradle = (root/'app/build.gradle').read_text(encoding='utf-8')
version_match = re.search(r'versionName\s+["\']([^"\']+)', gradle)
assert version_match, 'versionName not found'
report = dict(prepared_at_utc=datetime.now(timezone.utc).isoformat(),
              version=version_match.group(1), seeds=records,
              coverage='Alicante, Murcia, Valencia and Albacete road snapshots; national DGT feeds when downloaded')
output = root/'build/reports/release-seeds.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
