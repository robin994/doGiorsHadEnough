#!/usr/bin/env python3
"""Regenerate Detective Conan's Italian episode titles with verified numbering.

Sources:
 - https://it.wikipedia.org/wiki/Episodi_di_Detective_Conan (Italian titles)
 - https://www.detectiveconanworld.com/wiki/Anime (JP to international numbers)

CloudStream/AnimeUnity uses *international* episode numbers. The difference
between Japanese and international numbers changes after split specials; a
constant offset is incorrect. This tool is for maintainers, not used at runtime.

Requires: requests, beautifulsoup4
"""

from __future__ import annotations

import argparse
import json
import re
import time
from collections import defaultdict
from pathlib import Path

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "AnimeUnity/src/main/kotlin/it/dogior/hadEnough/DetectiveConanEpisodeTitles.kt"
WIKI_ROOT = "https://it.wikipedia.org/wiki/Episodi_di_Detective_Conan"
WORLD_INDEX = "https://www.detectiveconanworld.com/wiki/Anime"
SEASONS = (
    "prima", "seconda", "terza", "quarta", "quinta", "sesta", "settima",
    "ottava", "nona", "decima", "undicesima", "dodicesima", "tredicesima",
    "quattordicesima", "quindicesima", "sedicesima", "diciassettesima",
    "diciottesima", "diciannovesima", "ventesima", "ventunesima",
    "ventiduesima", "ventitreesima", "ventiquattresima", "venticinquesima",
    "ventiseiesima", "ventisettesima", "ventottesima", "ventinovesima",
    "trentesima", "trentunesima",
)
SPACE = re.compile(r"\s+")
INTEGER = re.compile(r"^\d+$")
RANGE = re.compile(r"^(\d+)(?:\s*[-–]\s*(\d+))?$")

# The five Police Academy / Wild Police Story specials are listed separately
# from the regular episode tables in Italian Wikipedia.
# https://it.wikipedia.org/wiki/Detective_Conan_-_Wild_Police_Story
WILD_POLICE_TITLES = {
    1086: "Arco dell'accademia di polizia - Wild Police Story - CASE. Jinpei Matsuda",
    1095: "Arco dell'accademia di polizia - Wild Police Story - CASE. Wataru Date",
    1099: "Arco dell'accademia di polizia - Wild Police Story - CASE. Kenji Hagiwara",
    1118: "Arco dell'accademia di polizia - Wild Police Story - CASE. Hiromitsu Morofushi",
    1134: "Arco dell'accademia di polizia - Wild Police Story - CASE. Rei Furuya",
}


def get_page(session: requests.Session, url: str) -> BeautifulSoup:
    response = session.get(url, timeout=25)
    response.raise_for_status()
    if len(response.content) < 1000:
        raise ValueError(f"Unexpectedly short response from {url}")
    return BeautifulSoup(response.text, "html.parser")


def normalize_title(text: str) -> str:
    return SPACE.sub(" ", text.replace("\u00a0", " ")).strip(" \n\r\t")


def italian_titles(session: requests.Session) -> tuple[dict[int, str], dict[int, list[str]], dict[int, int]]:
    by_international: dict[int, str] = {}
    by_japanese: dict[int, list[str]] = defaultdict(list)
    international_japanese: dict[int, int] = {}
    for season, ordinal in enumerate(SEASONS, start=1):
        page = get_page(session, f"{WIKI_ROOT}_({ordinal}_stagione)")
        table = page.select_one("table.wikitable")
        if table is None:
            raise ValueError(f"Episode table missing in season {season}")
        count = 0
        for row in table.select("tr"):
            cells = row.find_all("td", recursive=False)
            if len(cells) < 2:
                continue
            original = cells[0].get_text(" ", strip=True)
            if not INTEGER.fullmatch(original):
                continue
            japanese_number = int(original)
            if season <= 19:
                if len(cells) < 3:
                    continue
                italian_number = cells[1].get_text(" ", strip=True)
                title_cell = cells[2]
            else:
                italian_number = ""
                title_cell = cells[1]
            bold = title_cell.find("b")
            if bold:
                title = normalize_title(bold.get_text(" ", strip=True))
            else:
                title = normalize_title(title_cell.get_text(" ", strip=True).split("「", 1)[0])
            if not title or title in {"-", "TBA", "T.B.A."}:
                continue
            # Older episodes sometimes show both the historic Italian dub title
            # and a modern streaming title, separated by " / ".
            title = title.split(" / ", 1)[0]
            if title not in by_japanese[japanese_number]:
                by_japanese[japanese_number].append(title)
            if INTEGER.fullmatch(italian_number):
                number = int(italian_number)
                if number in by_international and by_international[number] != title:
                    raise ValueError(f"Conflicting Italian titles for {number}")
                by_international[number] = title
                international_japanese[number] = japanese_number
            count += 1
        if count < 10:
            raise ValueError(f"Suspiciously few rows in season {season}: {count}")
        time.sleep(0.15)
    return by_international, dict(by_japanese), international_japanese


def international_numbers(session: requests.Session) -> dict[int, int]:
    page = get_page(session, WORLD_INDEX)
    mapping: dict[int, int] = {}
    for table in page.select("table.wikitable"):
        for row in table.select("tr"):
            cells = row.find_all("td", recursive=False)
            if len(cells) < 3:
                continue
            jap = cells[0].get_text(" ", strip=True)
            if not INTEGER.fullmatch(jap):
                # Skip recaps, remasters and numbered specials (Rxx).
                continue
            number = int(jap)
            matched = RANGE.fullmatch(cells[1].get_text(" ", strip=True))
            if matched is None:
                continue
            first = int(matched.group(1))
            last = int(matched.group(2) or first)
            if last < first or last - first > 4:
                raise ValueError(f"Invalid episode range {first}-{last} for JP {number}")
            for international in range(first, last + 1):
                other = mapping.get(international)
                if other is not None and other != number:
                    raise ValueError(f"International episode {international} maps to {other} and {number}")
                mapping[international] = number
    if len(mapping) < 1158:
        raise ValueError(f"International numbering incomplete: {len(mapping)} entries")
    return mapping


def build_titles(
    italian: dict[int, str],
    by_japanese: dict[int, list[str]],
    italian_japanese: dict[int, int],
    international_japanese: dict[int, int],
) -> dict[int, str]:
    titles: dict[int, str] = {}
    mismatches: list[tuple[int, int, int]] = []
    japanese_international: dict[int, list[int]] = defaultdict(list)
    for international, japanese in sorted(international_japanese.items()):
        japanese_international[japanese].append(international)
    for number, jp in sorted(international_japanese.items()):
        if number in italian and italian_japanese[number] == jp:
            titles[number] = italian[number]
            continue
        if number in italian:
            mismatches.append((number, italian_japanese[number], jp))
        variants = by_japanese.get(jp, [])
        if not variants:
            continue
        numbers_for_jp = japanese_international[jp]
        # For later undubbed specials, preserve distinct parts when international
        # distribution splits one Japanese episode into multiple regular episodes.
        part = numbers_for_jp.index(number)
        title = variants[min(part, len(variants) - 1)]
        if len(numbers_for_jp) > 1 and len(variants) == 1:
            title = f"{title} (parte {part + 1})"
        titles[number] = title
    print(f"Detected {len(mismatches)} numbering discrepancies between Italian "
          f"broadcast and international episode indices; resolved by JP ID. "
          f"Examples: {mismatches[:6]}")
    titles.update(WILD_POLICE_TITLES)
    if any(n not in titles for n in range(145, 1159)):
        missing = [n for n in range(145, 1159) if n not in titles]
        raise ValueError(f"Missing verified titles for AnimeUnity episodes: "
                         f"{[(n, international_japanese.get(n)) for n in missing[:25]]}")
    expected = {145: "arma del delitto", 500: "Gli indizi", 776: "Blush Mermaid",
                777: "Blush Mermaid", 1158: "milioni"}
    for number, fragment in expected.items():
        if fragment.lower() not in titles[number].lower():
            raise ValueError(f"Sanity check failed at episode {number}: {titles[number]}")
    return titles


def as_kotlin(titles: dict[int, str]) -> str:
    pairs = []
    for number, title in sorted(titles.items()):
        literal = json.dumps(title, ensure_ascii=False).replace("$", "\\$")
        pairs.append(f"        {number} to {literal},")
    return "\n".join([
        "package it.dogior.hadEnough",
        "",
        "/**",
        " * Italian episode titles indexed by international (AnimeUnity) episode number.",
        " * Generated using tools/generate_conan_episode_titles.py. Do not edit by hand.",
        " * Source titles: it.wikipedia.org/wiki/Episodi_di_Detective_Conan",
        " * Numbering: www.detectiveconanworld.com/wiki/Anime",
        " */",
        "internal object DetectiveConanEpisodeTitles {",
        "    fun get(number: String): String? = number.toIntOrNull()?.let(titles::get)",
        "",
        "    private val titles: Map<Int, String> = mapOf(",
        *pairs,
        "    )",
        "}",
        "",
    ])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check generated source matches the working tree")
    args = parser.parse_args()
    session = requests.Session()
    session.headers["User-Agent"] = "doGiorsHadEnough metadata generator (https://github.com/robin994/doGiorsHadEnough)"
    italian, japanese, italian_japanese = italian_titles(session)
    international_japanese = international_numbers(session)
    titles = build_titles(italian, japanese, italian_japanese, international_japanese)
    content = as_kotlin(titles)
    print(f"Parsed {len(titles)} translated titles. "
          f"Samples: 145={titles[145]!r}; 500={titles[500]!r}; "
          f"777={titles[777]!r}; 1158={titles[1158]!r}")
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != content:
            raise SystemExit("Generated episode titles are out of date")
        print("Generated source up to date")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(content, encoding="utf-8")
        print(f"Updated {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
