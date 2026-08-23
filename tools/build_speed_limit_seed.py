#!/usr/bin/env python3
"""Build the compact offline maxspeed seed shipped with iDrive.

This is a release-preparation tool, not code executed by the radio. It reads a
regional Geofabrik OSM PBF once, clips ways to the Alicante administrative
boundary, keeps only numeric maxspeed tags and writes a gzip TSV asset. The
application imports that asset into its private SQLite cache on first start.
"""

from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path

import osmium
from shapely.geometry import LineString, shape
from shapely.prepared import prep


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


def load_boundary(path: Path):
    document = json.loads(path.read_text(encoding="utf-8"))
    feature = document["features"][0]
    boundary = shape(feature["geometry"])
    return boundary, prep(boundary), boundary.bounds


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
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--boundary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--region", default="Alicante")
    args = parser.parse_args()

    boundary, prepared_boundary, bounds = load_boundary(args.boundary)
    handler = AlicanteWayHandler(prepared_boundary, bounds)
    handler.apply_file(str(args.pbf), locations=True)

    # A way id is unique in the regional extract; sorting keeps the asset
    # stable between runs and makes review hashes reproducible.
    rows = sorted(set(handler.rows), key=lambda row: int(row[0]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as compressed:
        with gzip.GzipFile(fileobj=compressed, mode="wb", mtime=0) as gzip_output:
            text = gzip_output  # keep the UTF-8 encoding explicit below
            header = f"# schema=e87-speed-limit-seed-v1 source=OpenStreetMap maxspeed region={args.region}\n"
            text.write(header.encode("utf-8"))
            for osm_id, limit, geometry in rows:
                text.write(f"{osm_id}\t{limit}\t{geometry}\n".encode("utf-8"))

    raw_bytes = sum(len(f"{osm_id}\t{limit}\t{geometry}\n".encode("utf-8")) for osm_id, limit, geometry in rows)
    print(f"ways={len(rows)} raw_bytes={raw_bytes} gzip_bytes={args.output.stat().st_size}")
    print(f"boundary={boundary.bounds}")


if __name__ == "__main__":
    main()
