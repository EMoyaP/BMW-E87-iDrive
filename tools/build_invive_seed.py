#!/usr/bin/env python3
"""Create a compact, auditable INVIVE starter asset from the official DGT DATEX II feed."""

from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def text_of(element: ET.Element | None) -> str:
    return "" if element is None or element.text is None else element.text.strip()


def child_texts(root: ET.Element, name: str) -> list[str]:
    return [text_of(node) for node in root.iter() if local(node.tag) == name and text_of(node)]


def records(xml_path: Path):
    for _, node in ET.iterparse(xml_path, events=("end",)):
        if local(node.tag) != "predefinedLocation" or not node.get("id"):
            continue
        coordinates: list[tuple[str, str]] = []
        for point in node.iter():
            if local(point.tag) != "pointCoordinates":
                continue
            lat = next((text_of(value) for value in point if local(value.tag) == "latitude"), "")
            lon = next((text_of(value) for value in point if local(value.tag) == "longitude"), "")
            if lat and lon:
                coordinates.append((lat, lon))
        areas = child_texts(node, "administrativeArea")
        # administrativeArea wraps a value; ElementTree's text is whitespace, so read descendants.
        if not areas:
            areas = [text_of(value) for area in node.iter() if local(area.tag) == "administrativeArea"
                     for value in area.iter() if local(value.tag) == "value" and text_of(value)]
        roads = child_texts(node, "roadNumber")
        directions = child_texts(node, "directionRelative")
        distances = child_texts(node, "referencePointDistance")
        if len(coordinates) >= 2 and roads:
            yield (node.get("id", ""), areas[0] if areas else "", roads[0],
                   directions[0] if directions else "both",
                   coordinates[0][0], coordinates[0][1], coordinates[1][0], coordinates[1][1],
                   distances[0] if distances else "", distances[1] if len(distances) > 1 else "")
        node.clear()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--province", default="ALACANT/ALICANTE")
    args = parser.parse_args()
    selected = [row for row in records(args.xml) if not args.province or row[1] == args.province]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        output.write("# schema=e87-invive-v1 source=DGT-NAP licence=CC-BY\n")
        for row in selected:
            output.write("\t".join(row) + "\n")
    print(f"segments={len(selected)} bytes={args.output.stat().st_size}")


if __name__ == "__main__":
    main()
