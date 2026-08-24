"""External JSON glossary support for the MineAstr AstrBot plugin.

This module is intentionally independent of AstrBot and MineAstr protocol code.
It can be copied into the AstrBot plugin root and imported by ``main.py``.
The complete catalog stays on the bot; only query-relevant entries are added to
an individual model prompt.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import re
from typing import Any, Callable, Iterable


DEFAULT_MAX_BYTES = 32 * 1024 * 1024
DEFAULT_MAX_ENTRIES = 200_000
DEFAULT_MAX_TERM_LENGTH = 512
DEFAULT_MAX_PROMPT_CHARS = 12_000
_SPACE_RE = re.compile(r"\s+")


class GlossaryLoadError(ValueError):
    """Raised when a glossary exists but cannot be safely loaded."""


@dataclass(frozen=True)
class GlossaryEntry:
    key: str
    en_us: str
    zh_cn: str
    source: str = ""

    @property
    def identity(self) -> str:
        return self.key or self.en_us


def _text(value: Any, limit: int = DEFAULT_MAX_TERM_LENGTH) -> str:
    if not isinstance(value, str):
        return ""
    value = value.strip()
    if not value or len(value) > limit:
        return ""
    return value


def normalize_text(value: str) -> str:
    return _SPACE_RE.sub(" ", str(value or "").strip()).casefold()


def append_glossary_instructions(
    base: str,
    glossary_fragment: str,
    *,
    max_chars: int = DEFAULT_MAX_PROMPT_CHARS,
) -> str:
    """Append a bounded trusted glossary section to existing instructions."""
    prefix = str(base or "").strip()
    fragment = str(glossary_fragment or "").strip()
    if not fragment:
        return prefix[:max_chars]
    section = (
        "[Trusted Motiquies terminology]\n"
        "Use these exact English-to-Chinese mappings when the source contains "
        "the corresponding Minecraft term. Keep registry IDs unchanged.\n"
        + fragment
    )
    if not prefix:
        return section[:max_chars]
    separator = "\n\n"
    remaining = max(0, max_chars - len(prefix) - len(separator))
    if remaining <= 0:
        return prefix[:max_chars]
    return prefix + separator + section[:remaining]


def _entry_from_mapping(value: Any) -> GlossaryEntry | None:
    if not isinstance(value, dict):
        return None
    key = _text(value.get("key"))
    english = _text(value.get("en_us") or value.get("en") or value.get("english"))
    chinese = _text(value.get("zh_cn") or value.get("zh") or value.get("chinese"))
    if not english or not chinese:
        return None
    return GlossaryEntry(key, english, chinese, _text(value.get("source"), 2048))


def _entries_from_payload(payload: Any, max_entries: int) -> list[GlossaryEntry]:
    """Accept the generated paired glossary and the raw catalog format."""
    candidates: list[GlossaryEntry] = []
    if isinstance(payload, dict) and isinstance(payload.get("entries"), list):
        for value in payload["entries"]:
            entry = _entry_from_mapping(value)
            if entry is not None:
                candidates.append(entry)
                if len(candidates) > max_entries:
                    raise GlossaryLoadError("glossary entry limit exceeded")
        return candidates

    if not isinstance(payload, dict):
        raise GlossaryLoadError("glossary root must be a JSON object")
    languages = payload.get("languages")
    if not isinstance(languages, dict):
        raise GlossaryLoadError("glossary has neither entries nor languages")
    english = languages.get("en_us")
    chinese = languages.get("zh_cn")
    if not isinstance(english, dict) or not isinstance(chinese, dict):
        raise GlossaryLoadError("glossary requires languages.en_us and languages.zh_cn")
    provenance = payload.get("provenance")
    for key in sorted(set(english) & set(chinese)):
        en_value = _text(english.get(key))
        zh_value = _text(chinese.get(key))
        if not en_value or not zh_value:
            continue
        source = ""
        if isinstance(provenance, dict):
            item = provenance.get(f"en_us:{key}")
            if isinstance(item, dict):
                source = _text(item.get("source"), 2048)
        candidates.append(GlossaryEntry(_text(key), en_value, zh_value, source))
        if len(candidates) > max_entries:
            raise GlossaryLoadError("glossary entry limit exceeded")
    return candidates


class GlossaryIndex:
    def __init__(self, entries: Iterable[GlossaryEntry], source_path: str = ""):
        unique: dict[str, GlossaryEntry] = {}
        for entry in entries:
            identity = entry.identity
            if identity and identity not in unique:
                unique[identity] = entry
        self.entries = tuple(unique.values())
        self.source_path = source_path
        phrases: list[tuple[str, GlossaryEntry]] = []
        for entry in self.entries:
            for phrase in (entry.en_us, entry.zh_cn, entry.key):
                normalized = normalize_text(phrase)
                if normalized:
                    phrases.append((normalized, entry))
        self._phrases = tuple(sorted(phrases, key=lambda item: len(item[0]), reverse=True))

    @classmethod
    def from_path(
        cls,
        path: str | Path,
        *,
        max_bytes: int = DEFAULT_MAX_BYTES,
        max_entries: int = DEFAULT_MAX_ENTRIES,
    ) -> "GlossaryIndex":
        source = Path(path).expanduser()
        if not source.is_file():
            raise GlossaryLoadError(f"glossary file is missing: {source}")
        if source.stat().st_size > max_bytes:
            raise GlossaryLoadError("glossary file exceeds size limit")
        try:
            payload = json.loads(source.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise GlossaryLoadError(f"glossary JSON cannot be read: {source}") from exc
        return cls(_entries_from_payload(payload, max_entries), str(source))

    def lookup(self, text: str, *, limit: int = 32) -> list[GlossaryEntry]:
        normalized = normalize_text(text)
        if not normalized or limit <= 0:
            return []
        found: dict[str, GlossaryEntry] = {}
        for phrase, entry in self._phrases:
            if not phrase or phrase not in normalized:
                continue
            found.setdefault(entry.identity, entry)
            if len(found) >= limit:
                break
        return list(found.values())

    def render_matches(
        self,
        text: str,
        *,
        max_chars: int = DEFAULT_MAX_PROMPT_CHARS,
        limit: int = 32,
    ) -> str:
        if max_chars <= 0:
            return ""
        lines: list[str] = []
        used = 0
        for entry in self.lookup(text, limit=limit):
            line = f"- {entry.key or '(term)'}: {entry.en_us} = {entry.zh_cn}"
            extra = len(line) + (1 if lines else 0)
            if used + extra > max_chars:
                break
            lines.append(line)
            used += extra
        return "\n".join(lines)

    def render_seed(
        self,
        *,
        max_chars: int = DEFAULT_MAX_PROMPT_CHARS,
        prefixes: tuple[str, ...] = (
            "item.",
            "block.",
            "entity.",
            "fluid.",
            "effect.",
            "enchantment.",
        ),
    ) -> str:
        """Render a bounded static seed for image OCR, where text is unknown."""
        if max_chars <= 0:
            return ""
        lines: list[str] = []
        used = 0
        for entry in self.entries:
            if not entry.key.lower().startswith(prefixes):
                continue
            line = f"- {entry.en_us} = {entry.zh_cn}"
            extra = len(line) + (1 if lines else 0)
            if used + extra > max_chars:
                break
            lines.append(line)
            used += extra
        return "\n".join(lines)


class GlossaryProvider:
    """Reload-on-change provider with safe fallback to an empty glossary."""

    def __init__(
        self,
        path_getter: Callable[[], str] | str,
        *,
        max_bytes: int = DEFAULT_MAX_BYTES,
        max_entries: int = DEFAULT_MAX_ENTRIES,
        logger: Callable[[str], None] | None = None,
    ):
        self._path_getter = path_getter
        self._max_bytes = max_bytes
        self._max_entries = max_entries
        self._logger = logger or (lambda _message: None)
        self._signature: tuple[str, int, int] | None = None
        self._index = GlossaryIndex(())

    def _path(self) -> Path | None:
        value = self._path_getter() if callable(self._path_getter) else self._path_getter
        raw = str(value or "").strip()
        return Path(raw).expanduser() if raw else None

    def get(self) -> GlossaryIndex:
        path = self._path()
        if path is None:
            return GlossaryIndex(())
        try:
            stat = path.stat()
            signature = (str(path), stat.st_size, stat.st_mtime_ns)
        except OSError:
            signature = (str(path), -1, -1)
            if signature != self._signature:
                self._logger(f"MineAstr glossary disabled: glossary file is missing: {path}")
            self._signature = signature
            self._index = GlossaryIndex(())
            return self._index
        if signature == self._signature:
            return self._index
        try:
            self._index = GlossaryIndex.from_path(
                path, max_bytes=self._max_bytes, max_entries=self._max_entries
            )
            self._signature = signature
            return self._index
        except GlossaryLoadError as exc:
            self._logger(f"MineAstr glossary disabled: {exc}")
            self._signature = signature
            self._index = GlossaryIndex(())
            return self._index

    def prompt_fragment(
        self,
        text: str,
        *,
        max_chars: int = DEFAULT_MAX_PROMPT_CHARS,
        limit: int = 32,
    ) -> str:
        return self.get().render_matches(text, max_chars=max_chars, limit=limit)

    def image_seed(self, *, max_chars: int = DEFAULT_MAX_PROMPT_CHARS) -> str:
        return self.get().render_seed(max_chars=max_chars)
