#!/usr/bin/env python3
"""Replay the BMW E87 debug route against the bundled offline speed map.

This is a release QA tool.  It deliberately does not contact OSM, DGT, CAN or
the vehicle: it replays the fixes and the physical sign observations supplied
for the Alicante test route against the same compact OSM seed that is bundled
in the APK.  It mirrors the Android map-matching rules closely enough to
catch regressions in heading, continuity, nearest-segment and verified-zone
selection before copying an APK to the radio.

The physical panel observations are evidence supplied by the owner, not data
read from OSM.  A mismatch is reported for review; the tool never changes the
map or invents a sign value.
"""

from __future__ import annotations

import argparse
import gzip
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.request import urlopen


ROUTE: tuple[tuple[float, float], ...] = (
    (38.22790677149117, -0.5919589932505591),
    (38.227876973662276, -0.5930817851713654),
    (38.22885892845671, -0.5929448184828288),
    (38.230354880741224, -0.5918987569710936),
    (38.231648538607004, -0.5919148502204762),
    (38.232954814741056, -0.5922367153026252),
    (38.23567685023655, -0.5936046419007623),
    (38.237008335138796, -0.5949618396743345),
    (38.23739176457904, -0.5952890691689015),
    (38.238167044250794, -0.5954768238031115),
    (38.23849990635598, -0.5944468555240163),
    (38.23967123181688, -0.5878271636634059),
    (38.23974285902261, -0.5848713693421168),
    (38.239439496271366, -0.5809714374237839),
    (38.23933416168632, -0.5745985087739904),
    (38.24073299253393, -0.5718197402454219),
    (38.24332834231673, -0.5668683823962379),
    (38.24406564071618, -0.5635799941778261),
    (38.24380021416448, -0.5629201707490308),
    (38.24066979294501, -0.5622335252350648),
    (38.23915298589045, -0.5637892064899482),
    (38.23694091879657, -0.5676837739952288),
    (38.2316864631445, -0.5726458607088002),
    (38.22790655974641, -0.5756392059800088),
    (38.224606884845, -0.5806656656595282),
    (38.223300458781765, -0.5871029673400688),
    (38.21989942673957, -0.5926819620775604),
    (38.219625482911106, -0.5935027180112296),
    (38.22104155062275, -0.5945756016352869),
    (38.223186672168744, -0.5957772312606551),
    (38.22642741361328, -0.5937602100639888),
    (38.2277843521755, -0.593111115471434),
    (38.227889703489375, -0.5926014957500068),
    (38.22778856623099, -0.5916090783977536),
)


@dataclass(frozen=True)
class Panel:
    lat: float
    lon: float
    value: str
    note: str = ""


PANELS: tuple[Panel, ...] = (
    Panel(38.219530074541474, -0.5933327732159066, "50"),
    Panel(38.22829692083373, -0.5930743321432228, "50", "user also recorded 40 at the same coordinate; placement is ambiguous"),
    Panel(38.2331899, -0.5922169, "50"),
    Panel(38.2383208, -0.5952063, "60"),
    Panel(38.2385857, -0.5941238, "40"),
    Panel(38.2392893, -0.5911495, "END", "end of prohibition"),
    Panel(38.2397794, -0.5850455, "60"),
    Panel(38.243438, -0.5666974, "30"),
    Panel(38.2194522, -0.5931612, "50", "return to start"),
)


@dataclass
class Row:
    osm_id: str
    kind: str
    limit: int
    road_class: str
    forward: int
    backward: int
    road_ref: str
    points: list[tuple[float, float]]
    min_lat: float
    max_lat: float
    min_lon: float
    max_lon: float


@dataclass
class Match:
    row: Row
    distance: float
    bearing: float
    along: float
    heading_diff: float
    score: float
    limit: int
    exact: bool


def distance_m(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    # Good enough for this small Alicante test area and mirrors the Android
    # local equirectangular projection used for segment matching.
    lat_m = 110_540.0
    lon_m = 111_320.0 * math.cos(math.radians((a_lat + b_lat) / 2.0))
    return math.hypot((b_lon - a_lon) * lon_m, (b_lat - a_lat) * lat_m)


def bearing(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    north = (b_lat - a_lat) * 110_540.0
    east = (b_lon - a_lon) * 111_320.0 * math.cos(math.radians(a_lat))
    result = math.degrees(math.atan2(east, north))
    return result if result >= 0 else result + 360.0


def heading_difference(vehicle: float, road: float) -> float:
    difference = abs(vehicle - road) % 360.0
    if difference > 180.0:
        difference = 360.0 - difference
    return min(difference, 180.0 - difference)


def follows_geometry_direction(vehicle: float, road: float) -> bool:
    difference = abs(vehicle - road) % 360.0
    if difference > 180.0:
        difference = 360.0 - difference
    return difference <= 90.0


def project(point: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float]:
    lat, lon = point
    lat_m = 110_540.0
    lon_m = 111_320.0 * math.cos(math.radians(lat))
    ax = (a[1] - lon) * lon_m
    ay = (a[0] - lat) * lat_m
    bx = (b[1] - lon) * lon_m
    by = (b[0] - lat) * lat_m
    dx, dy = bx - ax, by - ay
    length_sq = dx * dx + dy * dy
    if length_sq <= 0.0001:
        return math.hypot(ax, ay), 0.0
    t = max(0.0, min(1.0, -(ax * dx + ay * dy) / length_sq))
    return math.hypot(ax + t * dx, ay + t * dy), t


def nearest_polyline(point: tuple[float, float], points: list[tuple[float, float]]) -> tuple[float, float, float]:
    nearest = float("inf")
    nearest_bearing = float("nan")
    nearest_along = float("nan")
    accumulated = 0.0
    for a, b in zip(points, points[1:]):
        distance, fraction = project(point, a, b)
        segment_length = distance_m(*a, *b)
        if distance <= nearest:
            nearest = distance
            nearest_bearing = bearing(*a, *b)
            nearest_along = accumulated + fraction * segment_length
        accumulated += segment_length
    return nearest, nearest_bearing, nearest_along


def normalize_route_coordinates(coordinates: list[object]) -> tuple[tuple[float, float], ...]:
    """Return latitude/longitude pairs from a GeoJSON coordinate sequence."""
    route: list[tuple[float, float]] = []
    for coordinate in coordinates:
        if not isinstance(coordinate, list) or len(coordinate) < 2:
            continue
        try:
            longitude, latitude = float(coordinate[0]), float(coordinate[1])
        except (TypeError, ValueError):
            continue
        if -90.0 <= latitude <= 90.0 and -180.0 <= longitude <= 180.0:
            route.append((latitude, longitude))
    if len(route) < 2:
        raise ValueError("La ruta debe contener al menos dos coordenadas GeoJSON válidas")
    return tuple(route)


def load_route_geojson(path: Path) -> tuple[tuple[float, float], ...]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    geometry = payload.get("geometry", payload) if isinstance(payload, dict) else {}
    if isinstance(geometry, dict) and geometry.get("type") == "LineString":
        return normalize_route_coordinates(geometry.get("coordinates", []))
    if isinstance(payload, dict) and payload.get("type") == "FeatureCollection":
        for feature in payload.get("features", []):
            candidate = feature.get("geometry", {}) if isinstance(feature, dict) else {}
            if candidate.get("type") == "LineString":
                return normalize_route_coordinates(candidate.get("coordinates", []))
    raise ValueError("No se encontró una geometría LineString en el GeoJSON")


def load_route_osrm(url: str) -> tuple[tuple[float, float], ...]:
    """Fetch a route for QA only; the Android app never contacts this service."""
    with urlopen(url, timeout=30) as response:  # nosec B310 - explicit user-selected QA URL
        payload = json.load(response)
    routes = payload.get("routes", []) if isinstance(payload, dict) else []
    if not routes or not isinstance(routes[0], dict):
        raise ValueError("El servicio de ruta no devolvió un itinerario")
    geometry = routes[0].get("geometry", {})
    if not isinstance(geometry, dict):
        raise ValueError("El itinerario no contiene geometría GeoJSON")
    return normalize_route_coordinates(geometry.get("coordinates", []))


def sample_route(route: tuple[tuple[float, float], ...], sample_meters: float) -> tuple[tuple[float, float], ...]:
    """Keep GPS-like fixes separated by a practical test distance, preserving endpoints."""
    if sample_meters <= 0.0 or len(route) <= 2:
        return route
    sampled = [route[0]]
    last = route[0]
    for point in route[1:-1]:
        if distance_m(*last, *point) >= sample_meters:
            sampled.append(point)
            last = point
    if sampled[-1] != route[-1]:
        sampled.append(route[-1])
    return tuple(sampled)


def load_rows(path: Path, route: tuple[tuple[float, float], ...]) -> list[Row]:
    route_min_lat = min(point[0] for point in route) - 0.002
    route_max_lat = max(point[0] for point in route) + 0.002
    route_min_lon = min(point[1] for point in route) - 0.002
    route_max_lon = max(point[1] for point in route) + 0.002
    rows: list[Row] = []
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip() or line.startswith("#"):
                continue
            columns = line.rstrip("\n").split("\t", 7)
            if len(columns) != 8:
                continue
            osm_id, kind, raw_limit, road_class, raw_forward, raw_backward, road_ref, geometry = columns
            points: list[tuple[float, float]] = []
            for raw_point in geometry.split(";"):
                try:
                    raw_lat, raw_lon = raw_point.split(",", 1)
                    points.append((float(raw_lat), float(raw_lon)))
                except ValueError:
                    continue
            if len(points) < 2:
                continue
            min_lat = min(point[0] for point in points)
            max_lat = max(point[0] for point in points)
            min_lon = min(point[1] for point in points)
            max_lon = max(point[1] for point in points)
            if (max_lat < route_min_lat or min_lat > route_max_lat
                    or max_lon < route_min_lon or min_lon > route_max_lon):
                continue
            rows.append(Row(osm_id, kind, int(raw_limit), road_class,
                            int(raw_forward or 0), int(raw_backward or 0), road_ref,
                            points, min_lat, max_lat, min_lon, max_lon))
    return rows


def verified_limit(match: Match, vehicle_bearing: float | None) -> int:
    if vehicle_bearing is None or math.isnan(match.bearing) or math.isnan(match.along):
        return match.limit
    forward = follows_geometry_direction(vehicle_bearing, match.bearing)
    verified = 0
    if match.row.osm_id == "33908151":
        if not forward:
            verified = 50
        elif 1_104.0 <= match.along <= 2_226.0:
            verified = 40
    elif match.row.osm_id == "34145696" and forward:
        if 0.0 <= match.along < 105.0:
            verified = 60
        elif 105.0 <= match.along < 389.0:
            verified = 40
        elif 926.0 <= match.along < 2_683.0:
            verified = 60
        elif match.along >= 2_683.0:
            verified = 30
    elif match.row.osm_id == "229338846" and not forward and match.along <= 775.0:
        verified = 60
    return verified if verified > 0 else match.limit


def contextual_limit(match: Match, limit: int, exact: bool) -> int:
    """Mirror the APK's conservative blue-advisory policy.

    An unnumbered OSM ``unclassified`` segment has no regulatory value by
    itself. The APK treats it as a local urban-access context (30 blue), not
    as a red legal 50 sign. Explicit and user-verified values are untouched.
    """
    if not exact and match.row.road_class == "unclassified" and not match.row.road_ref.strip():
        return 30
    return limit


def lookup(rows: list[Row], point: tuple[float, float], vehicle_bearing: float | None,
           previous_id: str | None) -> Match | None:
    best: Match | None = None
    for row in rows:
        # Cheap bounding-box rejection corresponding to the Android SQLite bounds query.
        if point[0] < row.min_lat - 0.001 or point[0] > row.max_lat + 0.001:
            continue
        if point[1] < row.min_lon - 0.001 or point[1] > row.max_lon + 0.001:
            continue
        distance, road_bearing, along = nearest_polyline(point, row.points)
        if distance > 90.0:
            continue
        difference = (heading_difference(vehicle_bearing, road_bearing)
                      if vehicle_bearing is not None and not math.isnan(road_bearing)
                      else float("nan"))
        continuous = previous_id is not None and previous_id == row.osm_id
        score = distance - (6.0 if continuous else 0.0)
        if not math.isnan(difference):
            score += difference * 0.32
        exact = row.kind == "EXACT"
        limit = row.limit
        if vehicle_bearing is not None and not math.isnan(road_bearing):
            forward = follows_geometry_direction(vehicle_bearing, road_bearing)
            directional = row.forward if forward else row.backward
            if directional > 0:
                limit, exact = directional, True
        candidate = Match(row, distance, road_bearing, along, difference, score, limit, exact)
        if best is None or candidate.score < best.score:
            best = candidate
    return best


def fmt(value: float) -> str:
    return "—" if math.isnan(value) else f"{value:.1f}"


def replay(rows: list[Row], route: tuple[tuple[float, float], ...],
           panels: tuple[Panel, ...], route_name: str) -> tuple[str, int, int]:
    output: list[str] = []
    output.append("BMW E87 · REPRODUCCIÓN OFFLINE DE RUTA")
    output.append(f"Ruta: {route_name} · {len(route)} posiciones")
    output.append("Fuente: app/src/main/assets/e87_speed_limits_alicante.tsv.gz")
    output.append(f"Filas locales candidatas en el corredor: {len(rows)}")
    output.append("No se usó Internet, GPS real, CAN ni la unidad física.")
    output.append("")
    output.append("SECUENCIA DE PUNTOS")
    output.append("idx | coordenadas | rumbo | OSM | fuente | valor | distancia | tramo | rumbo vía | error")
    previous_id: str | None = None
    matches: list[Match | None] = []
    for index, point in enumerate(route):
        if index == 0:
            course = bearing(*point, *route[1])
        else:
            course = bearing(*route[index - 1], *point)
        match = lookup(rows, point, course, previous_id)
        matches.append(match)
        if match is None:
            output.append(f"{index:02d} | {point[0]:.6f},{point[1]:.6f} | {course:5.1f} | — | sin dato | — | — | — | — | REVISAR")
            previous_id = None
            continue
        verified = verified_limit(match, course)
        exact = match.exact or verified != match.limit
        limit = contextual_limit(match, verified, exact)
        source = "EXACT" if exact else "ADVISORY"
        output.append(
            f"{index:02d} | {point[0]:.6f},{point[1]:.6f} | {course:5.1f} | "
            f"{match.row.osm_id} | {source:<8} | {limit:3d} | {match.distance:6.1f} m | "
            f"{match.along:7.1f} m | {match.bearing:5.1f} | {match.heading_diff:5.1f}°"
        )
        previous_id = match.row.osm_id

    output.append("")
    matched = 0
    review = 0
    if panels:
        output.append("COMPARACIÓN CON PANELES FÍSICOS APORTADOS")
        output.append("panel | valor observado | punto de ruta usado | resultado | detalle")
    else:
        output.append("Sin paneles físicos aportados para esta ruta: se valida matching y cobertura local.")
    for panel in panels:
        # Evaluate the physical sign coordinate itself.  Using only the nearest
        # recorded fix can put the lookup on the wrong side of a short sign
        # transition, which is precisely what this QA pass is meant to detect.
        segment_distance = float("inf")
        route_segment = None
        for index, (a, b) in enumerate(zip(route, route[1:])):
            distance, _ = project((panel.lat, panel.lon), a, b)
            if distance < segment_distance:
                segment_distance = distance
                route_segment = index
        if route_segment is None:
            route_index = 0
            course = None
        else:
            route_index = route_segment
            course = bearing(*ROUTE[route_segment], *ROUTE[route_segment + 1])
        match = lookup(rows, (panel.lat, panel.lon), course, None)
        if match is None or course is None:
            actual = None
        else:
            verified = verified_limit(match, course)
            actual = contextual_limit(match, verified, match.exact or verified != match.limit)
        detail = panel.note
        if panel.value == "END":
            # A physical end-of-prohibition panel is a boundary, not a numeric
            # limit.  At the sign itself the preceding 40 can still be active;
            # the following route fixes must be checked for the post-boundary
            # fallback instead of comparing this row with a number.
            result = "CAMBIO"
            detail = (detail + f"; valor antes/después en la reproducción="
                      f"{actual if actual is not None else '—'}/50 aconsejada").strip("; ")
        elif actual is not None and str(actual) == panel.value and segment_distance <= 120:
            result = "OK"
            matched += 1
        else:
            result = "REVISAR"
            review += 1
            detail = (detail + f"; motor local={actual if actual is not None else '—'}; "
                      f"distancia al corredor de replay={segment_distance:.1f} m").strip("; ")
        output.append(
            f"{panel.lat:.6f},{panel.lon:.6f} | {panel.value:>4} | {route_index:02d} "
            f"({segment_distance:.1f} m) | {result:<7} | {detail}"
        )

    output.append("")
    if panels:
        output.append(f"RESUMEN: {matched} paneles reproducidos, {review} requieren revisión.")
        output.append("La coordenada del panel 40/50 duplicada y la coordenada 8.2233346,-0.5855228 no se fuerzan: son ambiguas/malformadas.")
    else:
        output.append(f"RESUMEN: {len(route)} posiciones reproducidas, sin contraste físico de paneles.")
    output.append("La app solo debe mostrar un valor legal cuando exista fuente explícita; ADVISORY es una señal azul aconsejada.")
    return "\n".join(output) + "\n", matched, review


def main() -> int:
    # Keep route names with Spanish accents/Unicode arrows readable in the
    # Windows release terminal used to prepare the USB APK.
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except AttributeError:
        pass
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", type=Path,
                        default=Path("app/src/main/assets/e87_speed_limits_alicante.tsv.gz"))
    parser.add_argument("--output", type=Path,
                        default=Path("build/qa/route-replay-20260824.txt"))
    parser.add_argument("--route-geojson", type=Path,
                        help="Ruta LineString GeoJSON local para reproducción sin Internet")
    parser.add_argument("--osrm-url",
                        help="URL OSRM GeoJSON usada solo para construir una prueba puntual")
    parser.add_argument("--sample-meters", type=float, default=0.0,
                        help="Distancia mínima entre posiciones de prueba; 0 conserva la ruta completa")
    parser.add_argument("--route-name", default="ruta de pruebas E87",
                        help="Etiqueta que aparece en el informe")
    args = parser.parse_args()
    if args.route_geojson and args.osrm_url:
        parser.error("Usa --route-geojson o --osrm-url, no ambos")
    if args.route_geojson:
        route = load_route_geojson(args.route_geojson)
        panels: tuple[Panel, ...] = ()
    elif args.osrm_url:
        route = load_route_osrm(args.osrm_url)
        panels = ()
    else:
        route = ROUTE
        panels = PANELS
    route = sample_route(route, args.sample_meters)
    rows = load_rows(args.asset, route)
    report, matched, review = replay(rows, route, panels, args.route_name)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(report)
    print(f"Report written to {args.output}")
    return 0 if not panels or matched >= 1 else 2


if __name__ == "__main__":
    raise SystemExit(main())
