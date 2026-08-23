#!/usr/bin/env python3
"""Build offline OSM road seeds shipped with iDrive.

This is a release-preparation tool, not code executed by the radio. It reads a
regional Geofabrik OSM PBF once, clips ways to the Alicante administrative
boundary and writes a gzip TSV asset. It can also transform a saved Overpass
response containing every drivable road class. The latter produces schema v2:
explicit OSM ``maxspeed`` values and clearly marked DGT-reference advisory
rows for roads where OSM has no numeric maxspeed. The application imports that
asset into its private SQLite cache on first start.
"""

from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path

ROAD_CLASSES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "service",
}


def parse_limit(value: str | None) -> int | None:
    if not value:
        return None
    normalized = value.strip().lower()
    if not normalized or any(token in normalized for token in ("signals", "variable", "none", "unknown")):
        return None
    first = normalized.split(";", 1)[0].strip()
    mph = "mph" in first
    number = first.replace("km/h", "").replace("kmh", "").replace("mph", "").strip()
    try:
        result = round(float(number) * (1.609344 if mph else 1.0))
    except ValueError:
        return None
    return int(result) if 5 <= result <= 250 else None


def advisory_limit(road_class: str) -> int | None:
    """Keep this table aligned with SpeedLimitRepository.advisoryForRoadClass."""
    return {
        "motorway": 120,
        "trunk": 90,
        "primary": 90,
        "secondary": 90,
        "tertiary": 90,
        "unclassified": 50,
        "residential": 30,
        "living_street": 20,
        "service": 20,
    }.get(road_class)


def load_boundary(path: Path):
    from shapely.geometry import shape
    from shapely.prepared import prep

    document = json.loads(path.read_text(encoding="utf-8"))
    feature = document["features"][0]
    boundary = shape(feature["geometry"])
    return boundary, prep(boundary), boundary.bounds


def build_pbf_rows(pbf: Path, boundary_path: Path):
    import osmium
    from shapely.geometry import LineString

    boundary, prepared_boundary, bounds = load_boundary(boundary_path)

    class AlicanteWayHandler(osmium.SimpleHandler):
        def __init__(self, prepared_boundary, bounds):
            super().__init__()
            self.prepared_boundary = prepared_boundary
            self.min_lon, self.min_lat, self.max_lon, self.max_lat = bounds
            self.rows: list[tuple[str, int, str]] = []

        def way(self, way):  # noqa: N802 - pyosmium callback name
            tags = way.tags
            limit = parse_limit(tags.get("maxspeed"))
            if limit is None or not tags.get("highway"):
                return

            points = []
            for node in way.nodes:
                location = node.location
                if not location.valid():
                    return
                points.append((float(location.lon), float(location.lat)))
            if len(points) < 2:
                return

            line_min_lon = min(point[0] for point in points)
            line_max_lon = max(point[0] for point in points)
            line_min_lat = min(point[1] for point in points)
            line_max_lat = max(point[1] for point in points)
            if (line_max_lon < self.min_lon or line_min_lon > self.max_lon
                    or line_max_lat < self.min_lat or line_min_lat > self.max_lat):
                return
            if not self.prepared_boundary.intersects(LineString(points)):
                return

            geometry = ";".join(f"{lat:.6f},{lon:.6f}" for lon, lat in points)
            self.rows.append((str(way.id), limit, geometry))

    handler = AlicanteWayHandler(prepared_boundary, bounds)
    handler.apply_file(str(pbf), locations=True)
    return sorted(set(handler.rows), key=lambda row: int(row[0])), boundary.bounds


def build_overpass_rows(path: Path):
    document = json.loads(path.read_text(encoding="utf-8"))
    rows: dict[str, tuple[str, str, int, str, str]] = {}
    for element in document.get("elements", []):
        tags = element.get("tags") or {}
        road_class = tags.get("highway", "").strip().lower()
        if road_class not in ROAD_CLASSES:
            continue
        geometry_points = element.get("geometry") or []
        geometry = ";".join(
            f"{float(point['lat']):.6f},{float(point['lon']):.6f}"
            for point in geometry_points if "lat" in point and "lon" in point
        )
        if geometry.count(";") < 1:
            continue
        explicit = parse_limit(tags.get("maxspeed"))
        limit = explicit if explicit is not None else advisory_limit(road_class)
        if limit is None:
            continue
        osm_id = str(element.get("id", ""))
        if not osm_id:
            continue
        rows[osm_id] = (osm_id, "EXACT" if explicit is not None else "ADVISORY",
                        limit, road_class, geometry)
    return [rows[key] for key in sorted(rows, key=int)]


def write_v1(rows, output: Path, region: str, boundary):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as compressed:
        with gzip.GzipFile(fileobj=compressed, mode="wb", mtime=0) as gzip_output:
            gzip_output.write(
                f"# schema=e87-speed-limit-seed-v1 source=OpenStreetMap maxspeed region={region}\n".encode("utf-8")
            )
            for osm_id, limit, geometry in rows:
                gzip_output.write(f"{osm_id}\t{limit}\t{geometry}\n".encode("utf-8"))
    raw_bytes = sum(len(f"{osm_id}\t{limit}\t{geometry}\n".encode("utf-8")) for osm_id, limit, geometry in rows)
    print(f"ways={len(rows)} raw_bytes={raw_bytes} gzip_bytes={output.stat().st_size}")
    print(f"boundary={boundary}")


def write_v2(rows, output: Path, region: str):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as compressed:
        with gzip.GzipFile(fileobj=compressed, mode="wb", mtime=0) as gzip_output:
            gzip_output.write(
                f"# schema=e87-road-class-seed-v2 source=OpenStreetMap roads+maxspeed region={region}\n".encode("utf-8")
            )
            for osm_id, kind, limit, road_class, geometry in rows:
                gzip_output.write(
                    f"{osm_id}\t{kind}\t{limit}\t{road_class}\t{geometry}\n".encode("utf-8")
                )
    exact = sum(1 for row in rows if row[1] == "EXACT")
    raw_bytes = sum(len(f"{osm_id}\t{kind}\t{limit}\t{road_class}\t{geometry}\n".encode("utf-8"))
                    for osm_id, kind, limit, road_class, geometry in rows)
    print(f"ways={len(rows)} exact={exact} advisory={len(rows) - exact} raw_bytes={raw_bytes} gzip_bytes={output.stat().st_size}")
    def __init__(self, prepared_boundary, bounds):
        super().__init__()
        self.prepared_boundary = prepared_boundary
        self.min_lon, self.min_lat, self.max_lon, self.max_lat = bounds
        self.rows: list[tuple[str, int, str]] = []

    def way(self, way):  # noqa: N802 - pyosmium callback name
        tags = way.tags
        limit = parse_limit(tags.get("maxspeed"))
        if limit is None or not tags.get("highway"):
            return

        points = []
        for node in way.nodes:
            location = node.location
            if not location.valid():
                return
            points.append((float(location.lon), float(location.lat)))
        if len(points) < 2:
            return

        # Avoid an expensive geometry test for almost all roads outside the
        # province before checking the exact administrative multipolygon.
        line_min_lon = min(point[0] for point in points)
        line_max_lon = max(point[0] for point in points)
        line_min_lat = min(point[1] for point in points)
        line_max_lat = max(point[1] for point in points)
        if (line_max_lon < self.min_lon or line_min_lon > self.max_lon
                or line_max_lat < self.min_lat or line_min_lat > self.max_lat):
            return
        if not self.prepared_boundary.intersects(LineString(points)):
            return

        geometry = ";".join(f"{lat:.6f},{lon:.6f}" for lon, lat in points)
        self.rows.append((str(way.id), limit, geometry))


def main() -> None:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--pbf", type=Path)
    source.add_argument("--overpass-json", type=Path)
    parser.add_argument("--boundary", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--region", default="Alicante")
    args = parser.parse_args()

    if args.overpass_json:
        rows = build_overpass_rows(args.overpass_json)
        write_v2(rows, args.output, args.region)
    else:
        if not args.boundary:
            parser.error("--boundary es obligatorio al usar --pbf")
        rows, boundary = build_pbf_rows(args.pbf, args.boundary)
        write_v1(rows, args.output, args.region, boundary)


if __name__ == "__main__":
    main()
