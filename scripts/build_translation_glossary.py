#!/usr/bin/env python3
"""Build paired English/Chinese glossaries from an extracted language catalog.

The translation key remains the primary identity.  Human-readable English text
is not unique across mods, so it must not be used as the sole dictionary key.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


IMPORTANT_PREFIXES = (
    "item.",
    "block.",
    "entity.",
    "effect.",
    "mob_effect.",
    "enchantment.",
    "biome.",
    "fluid.",
    "attribute.",
    "painting.",
    "trim_material.",
    "trim_pattern.",
    "instrument.",
    "jukebox_song.",
    "entity_type.",
    "block_entity.",
)
NON_NAME_KEY_PARTS = {
    "tooltip",
    "description",
    "desc",
    "condition",
    "behaviour",
    "behavior",
    "summary",
    "message",
    "hint",
    "help",
    "usage",
    "failed",
    "failure",
    "applies_to",
    "ingredients",
    "author",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def source_for(provenance: dict[str, dict], language: str, key: str) -> dict:
    value = provenance.get(f"{language}:{key}", {})
    return {
        "source": str(value.get("source", "")),
        "path": str(value.get("path", "")),
        "order": int(value.get("order", -1)),
    }


def is_important(key: str, english: str, chinese: str) -> bool:
    lowered = key.lower()
    if not lowered.startswith(IMPORTANT_PREFIXES):
        return False
    if english.casefold() == chinese.casefold():
        return False
    parts = lowered.split(".")
    if lowered.startswith("enchantment.level.") or lowered.startswith(
        "attribute.modifier."
    ):
        return False
    for part in parts[2:]:
        normalized = part.replace("-", "_")
        if normalized in NON_NAME_KEY_PARTS:
            return False
        if any(marker in normalized for marker in ("tooltip", "description")):
            return False
        if normalized.startswith(("condition", "behaviour", "behavior", "line")):
            return False
        if normalized.endswith(("_message", "_hint", "_help")):
            return False
    # Display names are compact.  This removes sentence-like prose stored under
    # item/block prefixes while retaining dynamic names such as "%s Turtle".
    if max(len(english), len(chinese)) > 120:
        return False
    if len(english.split()) > 14:
        return False
    return True


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("catalog", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    catalog_path = args.catalog.resolve()
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    languages = catalog.get("languages", {})
    english = languages.get("en_us", {})
    chinese = languages.get("zh_cn", {})
    provenance = catalog.get("provenance", {})
    if not isinstance(english, dict) or not isinstance(chinese, dict):
        raise SystemExit("catalog does not contain en_us and zh_cn dictionaries")

    paired_keys = sorted(set(english) & set(chinese))
    entries = []
    important_entries = []
    reverse: dict[str, list[dict[str, str]]] = {}

    for key in paired_keys:
        en_value = str(english[key]).strip()
        zh_value = str(chinese[key]).strip()
        if not en_value or not zh_value:
            continue
        entry = {
            "key": key,
            "en_us": en_value,
            "zh_cn": zh_value,
            "en_source": source_for(provenance, "en_us", key),
            "zh_source": source_for(provenance, "zh_cn", key),
        }
        entries.append(entry)
        reverse.setdefault(en_value.casefold(), []).append(
            {"key": key, "en_us": en_value, "zh_cn": zh_value}
        )
        if is_important(key, en_value, zh_value):
            important_entries.append(entry)

    output_dir = args.output_dir.resolve()
    common = {
        "schema": 1,
        "source_catalog": str(catalog_path),
        "source_catalog_sha256": sha256(catalog_path),
        "instance": str(catalog.get("instance", "")),
    }
    complete_path = output_dir / "language-glossary-paired-complete.json"
    important_path = output_dir / "language-glossary-important-names.json"
    reverse_path = output_dir / "language-glossary-english-reverse-index.json"
    write_json(complete_path, {**common, "entry_count": len(entries), "entries": entries})
    write_json(
        important_path,
        {
            **common,
            "definition": "Paired gameplay names selected by translation-key and value heuristics",
            "prefixes": list(IMPORTANT_PREFIXES),
            "excluded_key_parts": sorted(NON_NAME_KEY_PARTS),
            "entry_count": len(important_entries),
            "entries": important_entries,
        },
    )
    write_json(
        reverse_path,
        {
            **common,
            "definition": "OCR lookup index; values remain lists because English names are not unique",
            "entry_count": len(reverse),
            "entries": dict(sorted(reverse.items())),
        },
    )
    checksums = output_dir / "SHA256SUMS.txt"
    lines = []
    for path in (complete_path, important_path, reverse_path):
        lines.append(f"{sha256(path)}  {path.name}")
    checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"paired={len(entries)} important={len(important_entries)} reverse={len(reverse)}")
    print(complete_path)
    print(important_path)
    print(reverse_path)


if __name__ == "__main__":
    main()

