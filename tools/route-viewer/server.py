"""Local-only web server for the BMW E87 route viewer."""

from __future__ import annotations

import json
import mimetypes
import threading
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parent
ROAD_DATA = ROOT / "data"


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        path = unquote(urlparse(self.path).path)
        if path == "/api/roads":
            names = sorted(p.name for p in ROAD_DATA.glob("e87_speed_limits_*.tsv.gz"))
            body = json.dumps(names).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/api/roads/"):
            name = path.rsplit("/", 1)[-1]
            target = ROAD_DATA / name
            if target.name != name or not target.is_file() or not name.startswith("e87_speed_limits_"):
                self.send_error(404)
                return
            size = target.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/gzip")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with target.open("rb") as source:
                while chunk := source.read(1024 * 256):
                    self.wfile.write(chunk)
            return
        if path == "/":
            self.path = "/index.html"
        super().do_GET()

    def log_message(self, _format, *_args):
        pass


def main():
    if not any(ROAD_DATA.glob("e87_speed_limits_*.tsv.gz")):
        raise SystemExit(f"Falta la cartografía OSM en {ROAD_DATA}")
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    url = f"http://127.0.0.1:{server.server_address[1]}/"
    threading.Timer(0.5, lambda: webbrowser.open_new_tab(url)).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
