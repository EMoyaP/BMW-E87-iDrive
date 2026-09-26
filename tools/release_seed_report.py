"""Validate bundled release inputs and record download vs source dates separately."""
import gzip
import hashlib
import json
from pathlib import Path
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
records = []
for path in [assets/'e87_speed_limits_alicante.tsv.gz', assets/'e87_osm_radars_alicante.json',
             assets/'e87_fuel_stations_alicante_diesel.json', assets/'e87_lufop_radars_es.dat',
             *sorted((root/'app/build/generated').glob('dgt*Assets/*.xml'))]:
    raw = path.read_bytes()
    item = dict(file=path.name, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
    if path.suffix == '.xml':
        tree = ET.fromstring(raw)
        item['source_dates'] = sorted({e.text.strip() for e in tree.iter()
            if e.text and ('publicationTime' in e.tag or 'timeStamp' in e.tag or 'timestamp' in e.tag)})[:12]
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
        else: item['source_date'] = json.loads((root/'build/release-input/alicante-roads.json').read_text(encoding='utf-8'))['osm3s']['timestamp_osm_base']
    assert item['bytes'] > 100
    records.append(item)
report = dict(prepared_at_utc=datetime.now(timezone.utc).isoformat(), version='1.26.1', seeds=records,
              other_provinces='Murcia, Valencia and Albacete retain existing bundled snapshots; not refreshed in this release')
output = root/'docs/release-1.26.1-seeds.json'
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
