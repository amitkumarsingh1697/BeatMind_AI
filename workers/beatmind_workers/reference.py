"""Load known recordings into the similarity index (US-029, TD-03).

    python -m beatmind_workers.reference add song.mp3 --title "Song" --artist "Artist" --source "catalogue X"
    python -m beatmind_workers.reference list

Only load recordings BeatMind may use for screening (licensed catalogue, public-domain sets, or
rights holders' own submissions). Uses DATABASE_URL like the worker.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import psycopg

from beatmind_workers import similarity


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="beatmind_workers.reference", description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    add = sub.add_parser("add", help="fingerprint and index one or more files")
    add.add_argument("files", nargs="+", type=Path)
    add.add_argument("--title", help="title (default: file name)")
    add.add_argument("--artist")
    add.add_argument("--source", required=True, help="where the recording comes from, for the audit trail")
    sub.add_parser("list", help="show indexed recordings")
    args = parser.parse_args(argv)

    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        if args.command == "add":
            for path in args.files:
                ref = similarity.add_reference(
                    conn, path.read_bytes(), args.title or path.stem, args.artist, args.source
                )
                print(f"{ref}\t{path}")
        else:
            for row in conn.execute(
                "SELECT id, title, coalesce(artist, ''), source, round(seconds), hashes"
                " FROM similarity_reference ORDER BY id"
            ):
                print("\t".join(str(v) for v in row))


if __name__ == "__main__":
    main()
