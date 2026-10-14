"""Omit Kover's non-executable line metadata from a separate Codecov report."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def normalize_report(source: Path, output: Path) -> int:
    if source.resolve() == output.resolve() or (output.exists() and source.samefile(output)):
        raise ValueError("The upload report must not overwrite the original Kover report")

    tree = ET.parse(source)
    if tree.getroot().tag != "report":
        raise ValueError("Expected a Kover/JaCoCo XML report")

    removed = 0
    for sourcefile in tree.iter("sourcefile"):
        for line in sourcefile.findall("line"):
            # With no instructions or branches this is metadata, not executable
            # code. Keep real misses, partials, branch-only lines and counters.
            if all(line.get(counter) == "0" for counter in ("mi", "ci", "mb", "cb")):
                sourcefile.remove(line)
                removed += 1

    tree.write(output, encoding="utf-8", xml_declaration=True)
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        removed = normalize_report(args.source, args.output)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Codecov report preparation failed: {error}", file=sys.stderr)
        return 1
    print(f"Prepared {args.output}: omitted {removed} non-executable line entries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
