"""Read-only application DB inspection plus debug-only GPS presentation on the emulator."""
import subprocess
import sqlite3
import time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
adb='C:/Android/platform-tools/adb.exe'
def call(*args): return subprocess.check_output([adb,*args])
folder=root/'build/release-qa'; folder.mkdir(parents=True,exist_ok=True)
for name in ['e87_dgt_radars.db','e87_speed_limits.db']:
    path=folder/name
    path.write_bytes(call('exec-out','run-as','com.ug.e87idrive','cat','databases/'+name))
    with sqlite3.connect(path) as db:
        print(name,db.execute('pragma integrity_check').fetchall())
        if 'radars' in name:
            print('sources',db.execute('select source,count(*) from radars group by source').fetchall())
            print('osm corridors',db.execute("select count(*) from radars where source='OSM' and osm_metadata like '%fromLat%'").fetchone())
        else: print('provinces',db.execute('select province,count(*) from speed_limits group by province').fetchall())
call('shell','appops','set','com.ug.e87idrive','android:mock_location','allow')
for latitude in [38.2134,38.2130,38.2126,38.2122]:
    call('shell','am','broadcast','-n','com.ug.e87idrive/.DebugPresentationReceiver','-a',
         'com.ug.e87idrive.DEBUG_PRESENTATION','--ez','active','true','--ed','latitude',str(latitude),
         '--ed','longitude','-0.5570','--ed','speed_kmh','74','--ed','bearing','190')
    time.sleep(2)
time.sleep(1)
screen=root/'docs/screenshots/driving-v1.26.1-radar.png'
screen.write_bytes(call('exec-out','screencap','-p'))
print(screen)
