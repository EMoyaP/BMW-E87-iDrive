"""Build and publish auditable data bundles for BMW E87 iDrive.

The desktop computer performs all expensive network and validation work. The radio only
downloads a completed release. No fuel-price data is included: MITECO remains live in the app.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


PROVINCES = {
    "alicante": (349012, "Alicante"),
    "murcia": (349047, "Murcia"),
    "valencia": (349000, "Valencia"),
    "albacete": (348989, "Albacete"),
    "almeria": (348997, "Almería"),
}
PROVINCE_BOUNDS = {
    349012: (37.80, -1.62, 38.90, 0.40),
    349047: (37.35, -2.40, 38.80, -0.62),
    349000: (38.55, -1.92, 40.92, 0.02),
    348989: (38.00, -2.98, 39.95, -0.68),
    348997: (36.62, -3.20, 37.95, -1.55),
}
OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)
DGT_SOURCES = {
    "e87_dgt_radars_spain.xml": (
        "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml",
        "predefinedLocation", "radar"),
    "e87_dgt_invive_spain.xml": (
        "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/tramos_invive/content.xml",
        "predefinedLocation", "surveillance"),
    "e87_dgt_speed_limits_spain.xml": (
        "https://nap.dgt.es/tnits/limitesVelocidad.xml",
        "GenericSafetyFeature", "speed_limit"),
}
ROAD_CLASSES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "service",
}
ADVISORY = {"motorway": 120, "trunk": 90, "primary": 90, "secondary": 90,
            "tertiary": 90, "unclassified": 50, "residential": 30,
            "living_street": 20, "service": 20}


@dataclass
class BuildSettings:
    project: Path
    repository: str
    token: str = ""
    lufop_zip: Path | None = None
    publish: bool = True


class BuildError(RuntimeError):
    pass


class DataBuilder:
    def __init__(self, settings: BuildSettings,
                 status: Callable[[str, str, str], None] | None = None):
        self.settings = settings
        self.status = status or (lambda phase, state, detail: None)
        self.version = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        self.stage = settings.project / "build" / "data-builder" / self.version
        self.assets = settings.project / "app" / "src" / "main" / "assets"
        self.files: list[dict] = []

    def run(self) -> Path:
        self._phase("Preparación", "RUNNING", "Creando zona de trabajo aislada")
        if not self.assets.is_dir():
            raise BuildError("No se encuentra app/src/main/assets en el proyecto seleccionado")
        self.stage.mkdir(parents=True, exist_ok=False)
        self._phase("Preparación", "OK", str(self.stage))
        self._download_dgt()
        self._build_osm()
        self._prepare_lufop()
        manifest = self._write_manifest()
        self._validate_bundle(manifest)
        if self.settings.publish:
            self._publish(manifest)
        else:
            self._phase("GitHub", "SKIPPED", "Publicación desactivada; paquete validado localmente")
        return manifest

    def _phase(self, phase: str, state: str, detail: str) -> None:
        self.status(phase, state, detail)

    @staticmethod
    def _request(url: str, data: bytes | None = None, timeout: int = 120) -> bytes:
        request = urllib.request.Request(url, data=data, headers={
            "User-Agent": "BMW-E87-Data-Builder/1.0 (personal offline data updater)",
            "Accept": "application/json, application/xml, text/xml, */*",
        })
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status < 200 or response.status >= 300:
                raise BuildError(f"HTTP {response.status}: {url}")
            return response.read()

    def _download_dgt(self) -> None:
        for filename, (url, marker, role) in DGT_SOURCES.items():
            phase = "DGT · " + role
            self._phase(phase, "RUNNING", "Descargando fuente oficial nacional")
            raw = self._request(url, timeout=150)
            if len(raw) < 1024 or marker.encode() not in raw:
                raise BuildError(f"Respuesta DGT incompleta: {filename}")
            try:
                root = ET.fromstring(raw)
            except ET.ParseError as error:
                raise BuildError(f"XML DGT inválido: {filename}: {error}") from error
            count = sum(1 for node in root.iter() if node.tag.rsplit("}", 1)[-1] == marker)
            if count == 0:
                raise BuildError(f"DGT no contiene {marker}: {filename}")
            target = self.stage / filename
            target.write_bytes(raw)
            self._record(target, [role], count, url)
            self._phase(phase, "OK", f"{count:,} registros · {len(raw)/1024/1024:.1f} MB")

    @staticmethod
    def _overpass_query(relation_id: int) -> str:
        area_id = 3_600_000_000 + relation_id
        classes = "|".join(sorted(ROAD_CLASSES))
        return f'''[out:json][timeout:600][maxsize:1073741824];
area({area_id})->.region;
(
  way(area.region)["highway"~"^({classes})$"];
  relation(area.region)["type"="restriction"];
  node(area.region)["highway"="speed_camera"];
  relation(area.region)["type"="enforcement"]->.enforcement;
  node(r.enforcement);
);
out body geom;'''

    @staticmethod
    def _overpass_bbox_query(bounds: tuple[float, float, float, float]) -> str:
        south, west, north, east = bounds
        bbox = f"{south:.5f},{west:.5f},{north:.5f},{east:.5f}"
        classes = "|".join(sorted(ROAD_CLASSES))
        return f'''[out:json][timeout:240][maxsize:268435456];
(
  way({bbox})["highway"~"^({classes})$"];
  relation({bbox})["type"="restriction"];
  node({bbox})["highway"="speed_camera"];
  relation({bbox})["type"="enforcement"]->.enforcement;
  node(r.enforcement);
);
out body geom;'''

    @staticmethod
    def _tiles(bounds: tuple[float, float, float, float], size: float = .75):
        south, west, north, east = bounds
        lat = south
        while lat < north:
            lon = west
            tile_north = min(north, lat + size)
            while lon < east:
                yield lat, lon, tile_north, min(east, lon + size)
                lon += size
            lat += size

    def _fetch_overpass_tile(self, bounds, index: int, total: int, depth: int = 0) -> dict:
        encoded = urllib.parse.urlencode({"data": self._overpass_bbox_query(bounds)}).encode()
        errors = []
        # Rotate the first endpoint so a tiled fallback does not hammer one public instance.
        endpoints = OVERPASS_ENDPOINTS[index % len(OVERPASS_ENDPOINTS):] + OVERPASS_ENDPOINTS[:index % len(OVERPASS_ENDPOINTS)]
        for endpoint in endpoints:
            try:
                raw = self._request(endpoint, encoded, timeout=120)
                document = json.loads(raw)
                if document.get("remark") or not isinstance(document.get("elements"), list):
                    raise BuildError(str(document.get("remark") or "Respuesta sin elements"))
                return document
            except Exception as error:
                errors.append(f"{endpoint}: {error}")
        # Public Overpass instances can reject a dense urban tile even though the data is
        # healthy. Split only that tile instead of discarding the complete province.
        if depth < 5:
            south, west, north, east = bounds
            middle_lat, middle_lon = (south + north) / 2, (west + east) / 2
            children = ((south, west, middle_lat, middle_lon),
                        (south, middle_lon, middle_lat, east),
                        (middle_lat, west, north, middle_lon),
                        (middle_lat, middle_lon, north, east))
            merged = {}
            for child_index, child in enumerate(children):
                document = self._fetch_overpass_tile(
                    child, index * 4 + child_index, total * 4, depth + 1)
                for element in document["elements"]:
                    key = (element.get("type"), element.get("id"))
                    if key[0] and key[1] is not None:
                        merged[key] = element
            return {"version": .6, "generator": "BMW-E87-Data-Builder subdivided Overpass",
                    "elements": list(merged.values()),
                    "_source_endpoint": "Overpass (tesela subdividida)"}
        raise BuildError(f"Tesela {index + 1}/{total}: " + " | ".join(errors))

    def _fetch_overpass_tiled(self, relation_id: int, label: str) -> dict:
        bounds = PROVINCE_BOUNDS.get(relation_id)
        if bounds is None:
            raise BuildError(f"Sin límites de teselado para {label}")
        tiles = list(self._tiles(bounds))
        self._phase(f"OSM · {label}", "RUNNING",
                    f"Consulta provincial rechazada; recuperando {len(tiles)} teselas")
        merged = {}
        # Persist each completed tile inside the staging directory. If Windows, the network or
        # Overpass interrupts a long province, pressing the button again resumes from the last
        # validated tile instead of repeating hours of public API traffic.
        cache_dir = self.stage / ".overpass-cache" / re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-")
        cache_dir.mkdir(parents=True, exist_ok=True)
        pending = []
        done = 0
        for index, tile in enumerate(tiles):
            cached = cache_dir / f"tile-{index:03d}.json.gz"
            if cached.is_file():
                try:
                    with gzip.open(cached, "rt", encoding="utf-8") as source:
                        document = json.load(source)
                    if not isinstance(document.get("elements"), list):
                        raise ValueError("cache sin elements")
                    for element in document["elements"]:
                        key = (element.get("type"), element.get("id"))
                        if key[0] and key[1] is not None:
                            merged[key] = element
                    done += 1
                    continue
                except Exception:
                    cached.unlink(missing_ok=True)
            pending.append((index, tile, cached))
        if done:
            self._phase(f"OSM · {label}", "RUNNING",
                        f"Reanudando {done}/{len(tiles)} teselas validadas")
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = {pool.submit(self._fetch_overpass_tile, tile, index, len(tiles)):
                       (index, cached) for index, tile, cached in pending}
            for future in as_completed(futures):
                document = future.result()
                _, cached = futures[future]
                with gzip.open(cached, "wt", encoding="utf-8") as output:
                    json.dump(document, output, ensure_ascii=False, separators=(",", ":"))
                for element in document["elements"]:
                    key = (element.get("type"), element.get("id"))
                    if key[0] and key[1] is not None:
                        merged[key] = element
                done += 1
                self._phase(f"OSM · {label}", "RUNNING",
                            f"Teselas {done}/{len(tiles)} · {len(merged):,} elementos únicos")
        result = {"version": .6, "generator": "BMW-E87-Data-Builder tiled Overpass",
                  "elements": list(merged.values()), "_source_endpoint": "Overpass (teselado)"}
        shutil.rmtree(cache_dir, ignore_errors=True)
        return result

    def _fetch_overpass(self, relation_id: int, label: str) -> dict:
        # Whole-province geometry responses regularly exceed public Overpass proxy limits.
        # Small bounded requests are slower in aggregate but deterministic and recoverable.
        return self._fetch_overpass_tiled(relation_id, label)

    def _fetch_overpass_whole_province(self, relation_id: int, label: str) -> dict:
        encoded = urllib.parse.urlencode({"data": self._overpass_query(relation_id)}).encode()
        errors = []
        for endpoint in OVERPASS_ENDPOINTS:
            for attempt in range(2):
                try:
                    raw = self._request(endpoint, encoded, timeout=720)
                    document = json.loads(raw)
                    if document.get("remark"):
                        raise BuildError(str(document["remark"]))
                    if not isinstance(document.get("elements"), list):
                        raise BuildError("Respuesta sin elements")
                    document["_source_endpoint"] = endpoint
                    return document
                except Exception as error:  # endpoint failover is intentional
                    errors.append(f"{endpoint} intento {attempt + 1}: {error}")
                    time.sleep(2 + attempt * 3)
        self._phase(f"OSM · {label}", "RETRY", "La consulta completa no cupo; activando teselado")
        try:
            return self._fetch_overpass_tiled(relation_id, label)
        except Exception as tiled_error:
            raise BuildError(f"Overpass no pudo preparar {label}: " + " | ".join(errors)
                             + f" | teselado: {tiled_error}") from tiled_error

    def _build_osm(self) -> None:
        for code, (relation_id, label) in PROVINCES.items():
            phase = f"OSM · {label}"
            self._phase(phase, "RUNNING", "Carreteras, restricciones y radares")
            document = self._fetch_overpass(relation_id, label)
            rows = self._road_rows(document)
            old = self.assets / f"e87_speed_limits_{code}.tsv.gz"
            old_count = self._gzip_records(old) if old.exists() else 0
            minimum = max(1_000, int(old_count * .75))
            if len(rows) < minimum:
                raise BuildError(f"Cobertura OSM insuficiente en {label}: {len(rows):,}; mínimo {minimum:,}")
            road_target = self.stage / f"e87_speed_limits_{code}.tsv.gz"
            self._write_roads(rows, road_target, label)
            self._record(road_target, ["dashboard", "map_matching", "routing"], len(rows),
                         document.get("_source_endpoint", "Overpass"))

            # Enforcement relations refer to from/to/device nodes by numeric id. Keep every
            # node returned by the dedicated relation expansion so the radio can recover the
            # verified corridor bearing instead of silently dropping the relation members.
            radar_elements = [element for element in document["elements"]
                              if element.get("type") == "node"
                              or (element.get("tags") or {}).get("type") == "enforcement"]
            radar_document = {"version": .6, "generator": "BMW-E87-Data-Builder",
                              "osm3s": document.get("osm3s", {}), "elements": radar_elements}
            radar_target = self.stage / f"e87_osm_radars_{code}.json.gz"
            self._write_gzip_json(radar_target, radar_document)
            self._record(radar_target, ["radar", "routing"], len(radar_elements), "OpenStreetMap")

            navigation_target = self.stage / f"e87_osm_navigation_{code}.json.gz"
            clean_document = {key: value for key, value in document.items()
                              if not key.startswith("_")}
            self._write_gzip_json(navigation_target, clean_document)
            self._record(navigation_target, ["routing", "map_matching"],
                         len(clean_document["elements"]), "OpenStreetMap")
            self._phase(phase, "OK", f"{len(rows):,} vías · {len(radar_elements):,} radares/relaciones")

    def _prepare_lufop(self) -> None:
        phase = "Lufop · radares fijos"
        self._phase(phase, "RUNNING", "Preparando semilla complementaria")
        target = self.stage / "e87_lufop_radars_es.dat"
        if self.settings.lufop_zip and self.settings.lufop_zip.is_file():
            from import_lufop_asc_seed import convert
            previous = self.assets / target.name
            if previous.exists():
                shutil.copy2(previous, target)
            convert(self.settings.lufop_zip, target)
            source = str(self.settings.lufop_zip)
        else:
            previous = self.assets / target.name
            if not previous.exists():
                raise BuildError("No hay ZIP Lufop seleccionado ni semilla existente")
            shutil.copy2(previous, target)
            source = "semilla local conservada; no se afirmó una actualización"
        count = self._gzip_records(target)
        if count < 500:
            raise BuildError(f"Semilla Lufop anormalmente pequeña: {count}")
        self._record(target, ["radar"], count, source)
        self._phase(phase, "OK", f"{count:,} radares fijos únicos")

    @staticmethod
    def _parse_limit(value) -> int | None:
        if not value:
            return None
        text = str(value).strip().lower().split(";", 1)[0]
        if any(word in text for word in ("signals", "variable", "none", "unknown")):
            return None
        mph = "mph" in text
        match = re.search(r"\d+(?:[.,]\d+)?", text)
        if not match:
            return None
        result = round(float(match.group().replace(",", ".")) * (1.609344 if mph else 1))
        return result if 5 <= result <= 250 else None

    @classmethod
    def _road_rows(cls, document: dict) -> list[tuple]:
        rows = {}
        for element in document.get("elements", []):
            if element.get("type") != "way":
                continue
            tags = element.get("tags") or {}
            road_class = str(tags.get("highway", "")).lower()
            if road_class not in ROAD_CLASSES:
                continue
            points = element.get("geometry") or []
            if len(points) < 2:
                continue
            geometry = ";".join(f"{float(p['lat']):.6f},{float(p['lon']):.6f}"
                                for p in points if "lat" in p and "lon" in p)
            if geometry.count(";") < 1:
                continue
            explicit = cls._parse_limit(tags.get("maxspeed"))
            limit = explicit or ADVISORY.get(road_class)
            if not limit:
                continue
            name = next((str(tags.get(key, "")).strip() for key in
                         ("name:es", "name", "official_name", "alt_name")
                         if str(tags.get(key, "")).strip()), "")
            clean = lambda value: " ".join(str(value or "").replace("\t", " ").split())
            osm_id = str(element.get("id", ""))
            rows[osm_id] = (osm_id, "EXACT" if explicit else "ADVISORY", limit,
                            road_class, cls._parse_limit(tags.get("maxspeed:forward")) or 0,
                            cls._parse_limit(tags.get("maxspeed:backward")) or 0,
                            clean(tags.get("ref")), clean(name), geometry)
        return [rows[key] for key in sorted(rows, key=lambda value: int(value))]

    @staticmethod
    def _write_roads(rows: list[tuple], target: Path, region: str) -> None:
        with target.open("wb") as raw:
            with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as output:
                output.write(("# schema=e87-road-class-seed-v5 source=OpenStreetMap "
                              f"roads+maxspeed+directional+ref+name region={region}\n").encode())
                for row in rows:
                    output.write(("\t".join(map(str, row)) + "\n").encode("utf-8"))

    @staticmethod
    def _write_gzip_json(target: Path, document: dict) -> None:
        with target.open("wb") as raw:
            with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as output:
                output.write(json.dumps(document, ensure_ascii=False,
                                        separators=(",", ":")).encode("utf-8"))

    @staticmethod
    def _gzip_records(path: Path) -> int:
        with gzip.open(path, "rt", encoding="utf-8") as source:
            return sum(1 for line in source if line.strip() and not line.startswith("#"))

    def _record(self, path: Path, roles: list[str], records: int, source: str) -> None:
        raw = path.read_bytes()
        self.files.append({"name": path.name, "bytes": len(raw),
                           "sha256": hashlib.sha256(raw).hexdigest(),
                           "records": records, "roles": roles, "source": source})

    def _write_manifest(self) -> Path:
        manifest = {
            "schema": "e87-data-bundle-v1",
            "bundle_version": self.version,
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "repository": self.settings.repository,
            "coverage": [label for _, label in PROVINCES.values()],
            "fuel_prices_included": False,
            "files": sorted(self.files, key=lambda item: item["name"]),
        }
        path = self.stage / "manifest.json"
        path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path

    def _validate_bundle(self, manifest_path: Path) -> None:
        phase = "Validación final"
        self._phase(phase, "RUNNING", "Verificando integridad, cobertura y reproducibilidad")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected = set(PROVINCES)
        roads = {item["name"].removeprefix("e87_speed_limits_").removesuffix(".tsv.gz")
                 for item in manifest["files"] if item["name"].startswith("e87_speed_limits_")}
        if roads != expected:
            raise BuildError(f"Cobertura provincial incompleta: {sorted(roads)}")
        for item in manifest["files"]:
            path = self.stage / item["name"]
            if not path.is_file() or path.stat().st_size != item["bytes"]:
                raise BuildError(f"Tamaño incorrecto: {item['name']}")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != item["sha256"]:
                raise BuildError(f"SHA-256 incorrecto: {item['name']}")
            if item["records"] <= 0:
                raise BuildError(f"Archivo vacío: {item['name']}")
        self._phase(phase, "OK", f"{len(manifest['files'])} archivos listos; gasolineras excluidas")

    def _publish(self, manifest: Path) -> None:
        phase = "GitHub"
        self._phase(phase, "RUNNING", "Creando release en borrador")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", self.settings.repository):
            raise BuildError("Repositorio GitHub inválido; use propietario/repositorio")
        if not self.settings.token:
            raise BuildError("Falta el token GitHub; el paquete local sí quedó validado")
        api = f"https://api.github.com/repos/{self.settings.repository}/releases"
        tag = "data-v" + self.version
        release = self._github_json("POST", api, {
            "tag_name": tag, "name": f"BMW E87 data {self.version}",
            "body": "Paquete de datos generado y validado por BMW E87 Data Builder.",
            "draft": True, "prerelease": False, "make_latest": "false",
        })
        upload_url = release["upload_url"].split("{", 1)[0]
        try:
            for path in sorted(self.stage.iterdir()):
                if not path.is_file():
                    continue
                self._phase(phase, "RUNNING", f"Subiendo {path.name}")
                url = upload_url + "?" + urllib.parse.urlencode({"name": path.name})
                self._github_bytes("POST", url, path.read_bytes(), "application/octet-stream")
            self._github_json("PATCH", release["url"], {"draft": False, "make_latest": "false"})
        except Exception:
            self._phase(phase, "FAILED", "La release permanece en borrador para no exponer un lote parcial")
            raise
        self._phase(phase, "OK", release.get("html_url", tag))

    def _github_headers(self, content_type: str) -> dict[str, str]:
        return {"Accept": "application/vnd.github+json", "Authorization": f"Bearer {self.settings.token}",
                "X-GitHub-Api-Version": "2022-11-28", "Content-Type": content_type,
                "User-Agent": "BMW-E87-Data-Builder/1.0"}

    def _github_json(self, method: str, url: str, body: dict) -> dict:
        raw = json.dumps(body).encode()
        request = urllib.request.Request(url, data=raw, method=method,
                                         headers=self._github_headers("application/json"))
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            raise BuildError(f"GitHub HTTP {error.code}: {error.read().decode(errors='replace')}") from error

    def _github_bytes(self, method: str, url: str, body: bytes, content_type: str) -> None:
        request = urllib.request.Request(url, data=body, method=method,
                                         headers=self._github_headers(content_type))
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                if response.status < 200 or response.status >= 300:
                    raise BuildError(f"GitHub HTTP {response.status}")
        except urllib.error.HTTPError as error:
            raise BuildError(f"GitHub HTTP {error.code}: {error.read().decode(errors='replace')}") from error
