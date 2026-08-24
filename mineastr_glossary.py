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
import threading
from typing import Any, Callable, Iterable


DEFAULT_MAX_BYTES = 32 * 1024 * 1024
DEFAULT_MAX_ENTRIES = 200_000
DEFAULT_MAX_TERM_LENGTH = 512
DEFAULT_MAX_PROMPT_CHARS = 12_000
DEFAULT_LOOKUP_CACHE_LIMIT = 512
_SPACE_RE = re.compile(r"\s+")
_HAN_RE = re.compile(r"[\u3400-\u9fff]")


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
        "Each listed pair is an exact en_us <=> zh_cn Minecraft terminology "
        "mapping. Apply it in the direction required by the source and target "
        "locale. Ambiguous source terms are intentionally omitted; do not guess "
        "between conflicting mappings. Keep registry IDs unchanged.\n"
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
        english_targets: dict[str, set[str]] = {}
        chinese_targets: dict[str, set[str]] = {}
        canonical_pairs: dict[tuple[str, str], GlossaryEntry] = {}
        for entry in self.entries:
            english = normalize_text(entry.en_us)
            chinese = normalize_text(entry.zh_cn)
            if not english or not chinese or english == chinese:
                continue
            english_targets.setdefault(english, set()).add(chinese)
            chinese_targets.setdefault(chinese, set()).add(english)
            canonical_pairs.setdefault((english, chinese), entry)

        phrases: list[tuple[str, GlossaryEntry]] = []
        for entry in self.entries:
            english = normalize_text(entry.en_us)
            chinese = normalize_text(entry.zh_cn)
            if not english or not chinese or english == chinese:
                continue
            canonical = canonical_pairs[(english, chinese)]
            # OCR and ordinary chat normally contain display names, not registry
            # keys.  A display name is safe to force only when it has one exact
            # counterpart.  Conflicting names (for example two unrelated mods
            # both calling an item "Speed") are intentionally left to the model
            # unless the source also carries the unique registry key.
            if entry is canonical and len(english_targets[english]) == 1:
                if self._usable_phrase(english):
                    phrases.append((english, canonical))
            if entry is canonical and len(chinese_targets[chinese]) == 1:
                if self._usable_phrase(chinese):
                    phrases.append((chinese, canonical))
            key = normalize_text(entry.key)
            if self._usable_phrase(key):
                phrases.append((key, entry))
        self._phrases = tuple(sorted(phrases, key=lambda item: len(item[0]), reverse=True))
        self._lookup_cache: dict[tuple[str, int], tuple[GlossaryEntry, ...]] = {}
        self._lookup_lock = threading.RLock()

    @staticmethod
    def _usable_phrase(phrase: str) -> bool:
        if not phrase:
            return False
        if _HAN_RE.search(phrase):
            return len(phrase) >= 2
        return len(phrase) >= 3

    @staticmethod
    def _span(
        text: str,
        phrase: str,
        covered: list[tuple[int, int, str]],
    ) -> tuple[int, int] | None:
        start = 0
        has_han = bool(_HAN_RE.search(phrase))
        while True:
            position = text.find(phrase, start)
            if position < 0:
                return None
            end = position + len(phrase)
            start = position + 1
            if not has_han:
                before = text[position - 1] if position else ""
                after = text[end] if end < len(text) else ""
                if before and (before.isalnum() or before == "_"):
                    continue
                if after and (after.isalnum() or after == "_"):
                    continue
            nested = any(
                other != phrase
                and len(other) > len(phrase)
                and left <= position
                and end <= right
                for left, right, other in covered
            )
            if not nested:
                return position, end

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
        cache_key = (normalized, limit)
        with self._lookup_lock:
            cached = self._lookup_cache.get(cache_key)
            if cached is not None:
                return list(cached)
        found: dict[str, GlossaryEntry] = {}
        covered: list[tuple[int, int, str]] = []
        for phrase, entry in self._phrases:
            span = self._span(normalized, phrase, covered)
            if span is None:
                continue
            found.setdefault(entry.identity, entry)
            covered.append((span[0], span[1], phrase))
            if len(found) >= limit:
                break
        result = tuple(found.values())
        with self._lookup_lock:
            self._lookup_cache[cache_key] = result
            while len(self._lookup_cache) > DEFAULT_LOOKUP_CACHE_LIMIT:
                self._lookup_cache.pop(next(iter(self._lookup_cache)))
        return list(result)

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
            line = (
                f'- {entry.key or "(term)"}: en_us "{entry.en_us}" '
                f'<=> zh_cn "{entry.zh_cn}"'
            )
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
        self._lock = threading.RLock()

    def _path(self) -> Path | None:
        value = self._path_getter() if callable(self._path_getter) else self._path_getter
        raw = str(value or "").strip()
        return Path(raw).expanduser() if raw else None

    def get(self) -> GlossaryIndex:
        with self._lock:
            return self._get_locked()

    def _get_locked(self) -> GlossaryIndex:
        path = self._path()
        if path is None:
            self._signature = None
            self._index = GlossaryIndex(())
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

    def cache_token(self) -> tuple[str, int, int] | None:
        """Return the current file revision after applying reload/fallback logic."""
        with self._lock:
            self._get_locked()
            return self._signature

    def prompt_fragment(
        self,
        text: str,
        *,
        max_chars: int = DEFAULT_MAX_PROMPT_CHARS,
        limit: int = 32,
    ) -> str:
        return self.get().render_matches(text, max_chars=max_chars, limit=limit)
