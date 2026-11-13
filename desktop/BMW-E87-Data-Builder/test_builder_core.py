import gzip
import json
import tempfile
import unittest
from pathlib import Path

from builder_core import DataBuilder, OVERPASS_ENDPOINTS


class BuilderCoreTest(unittest.TestCase):
    def test_speed_parsing(self):
        self.assertEqual(90, DataBuilder._parse_limit("90"))
        self.assertEqual(80, DataBuilder._parse_limit("50 mph"))
        self.assertIsNone(DataBuilder._parse_limit("signals"))

    def test_road_rows_keep_direction_and_name(self):
        document = {"elements": [{"type": "way", "id": 7,
            "tags": {"highway": "primary", "maxspeed": "100",
                     "maxspeed:forward": "80", "ref": "N-332", "name": "Prueba"},
            "geometry": [{"lat": 38.0, "lon": -0.5}, {"lat": 38.1, "lon": -0.4}]}]}
        rows = DataBuilder._road_rows(document)
        self.assertEqual(1, len(rows))
        self.assertEqual(("7", "EXACT", 100, "primary", 80, 0, "N-332", "Prueba"), rows[0][:8])

    def test_deterministic_gzip(self):
        rows = [("7", "EXACT", 90, "primary", 0, 0, "N-332", "Prueba",
                 "38.000000,-0.500000;38.100000,-0.400000")]
        with tempfile.TemporaryDirectory() as folder:
            first, second = Path(folder)/"a.gz", Path(folder)/"b.gz"
            DataBuilder._write_roads(rows, first, "Test")
            DataBuilder._write_roads(rows, second, "Test")
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertIn("schema=e87-road-class-seed-v5", gzip.decompress(first.read_bytes()).decode())

    def test_failed_dense_tile_is_subdivided_without_losing_children(self):
        builder = DataBuilder.__new__(DataBuilder)
        calls = []
        def request(url, data=None, timeout=0):
            calls.append(data)
            # The parent is attempted against every endpoint. Every smaller child succeeds.
            if len(calls) <= len(OVERPASS_ENDPOINTS):
                raise TimeoutError("dense parent")
            value = len(calls)
            return json.dumps({"elements": [{"type": "node", "id": value}]}).encode()
        builder._request = request
        result = builder._fetch_overpass_tile((38.0, -1.0, 38.75, -.25), 0, 1)
        self.assertEqual(4, len(result["elements"]))
        self.assertIn("subdivided", result["generator"])

    def test_tiled_download_resumes_validated_cache(self):
        with tempfile.TemporaryDirectory() as folder:
            builder = DataBuilder.__new__(DataBuilder)
            builder.stage = Path(folder)
            builder.status = lambda *args: None
            builder._tiles = lambda bounds: iter([(0, 0, 1, 1), (1, 1, 2, 2)])
            cache = builder.stage / ".overpass-cache" / "test"
            cache.mkdir(parents=True)
            with gzip.open(cache / "tile-000.json.gz", "wt", encoding="utf-8") as output:
                json.dump({"elements": [{"type": "node", "id": 1}]}, output)
            calls = []
            builder._fetch_overpass_tile = lambda bounds, index, total: (
                calls.append(index) or {"elements": [{"type": "node", "id": 2}]})
            import builder_core
            previous = builder_core.PROVINCE_BOUNDS.get(999)
            builder_core.PROVINCE_BOUNDS[999] = (0, 0, 2, 2)
            try:
                result = builder._fetch_overpass_tiled(999, "Test")
            finally:
                if previous is None:
                    del builder_core.PROVINCE_BOUNDS[999]
                else:
                    builder_core.PROVINCE_BOUNDS[999] = previous
            self.assertEqual([1], calls)
            self.assertEqual({1, 2}, {item["id"] for item in result["elements"]})
            self.assertFalse(cache.exists())


if __name__ == "__main__":
    unittest.main()
