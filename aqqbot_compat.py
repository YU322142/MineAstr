"""AQQBot-compatible data and message helpers.

This module deliberately has no AstrBot imports so its persistence and filtering
behaviour can be tested without starting AstrBot.
"""

from __future__ import annotations

import asyncio
import re
import sqlite3
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:
    import regex as _timeout_regex
except ImportError:  # Tests and minimal source checkouts may not install extras yet.
    _timeout_regex = None

_USER_REGEX_EXCEPTIONS: tuple[type[BaseException], ...] = (re.error, TimeoutError)
if _timeout_regex is not None:
    _USER_REGEX_EXCEPTIONS += (_timeout_regex.error,)

DEFAULT_BINDING_DATABASE = "data/mineastr/bindings.sqlite3"
LEGACY_LOGIN_ENDPOINT_RE = re.compile(
    r"^(?P<player>.+?) \(/(?:\[[^\]\s]+\]|(?:\d{1,3}\.){3}\d{1,3}):\d{1,5}\)$"
)


def sanitize_minecraft_login_name(value: str) -> str:
    """Remove the address suffix added by the legacy Fabric login display API."""

    player_name = str(value or "").strip()
    match = LEGACY_LOGIN_ENDPOINT_RE.fullmatch(player_name)
    if not match:
        return player_name
    sanitized = match.group("player").strip()
    return sanitized or player_name


class BindingError(RuntimeError):
    """Base class for account binding failures."""


class PlayerAlreadyBoundError(BindingError):
    def __init__(self, player_name: str, owner_key: str):
        super().__init__(f"玩家 {player_name} 已被 {owner_key} 绑定。")
        self.player_name = player_name
        self.owner_key = owner_key


class BindingLimitError(BindingError):
    def __init__(self, maximum: int):
        super().__init__(f"该账号最多只能绑定 {maximum} 个 Minecraft 账号。")
        self.maximum = maximum


@dataclass(frozen=True)
class BindingRecord:
    owner_key: str
    platform_id: str
    user_id: str
    owner_display: str
    player_name: str
    created_at: int


@dataclass(frozen=True)
class DiscordNicknameState:
    """Original guild nickname saved before MineAstr changes it."""

    owner_key: str
    guild_id: str
    original_nickname: str | None
    updated_at: int


class BindingStore:
    """Small SQLite binding store with atomic uniqueness and quota checks."""

    def __init__(self, database_path: str | Path = DEFAULT_BINDING_DATABASE):
        self.path = Path(database_path).expanduser()
        self._lock = asyncio.Lock()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(str(self.path), timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    async def initialize(self) -> None:
        await asyncio.to_thread(self._initialize_sync)

    async def migrate_player_names(
        self, normalizer: Callable[[str], str]
    ) -> tuple[int, int]:
        """Normalize stored player names without exposing their old values."""

        async with self._lock:
            return await asyncio.to_thread(
                self._migrate_player_names_sync, normalizer
            )

    def _migrate_player_names_sync(
        self, normalizer: Callable[[str], str]
    ) -> tuple[int, int]:
        connection = self._connect()
        migrated = 0
        conflicts = 0
        try:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                "SELECT rowid AS binding_rowid, player_name FROM bindings"
            ).fetchall()
            for row in rows:
                old_name = str(row["player_name"])
                new_name = str(normalizer(old_name) or "").strip()
                if not new_name or new_name == old_name:
                    continue
                new_key = new_name.casefold()
                existing = connection.execute(
                    "SELECT rowid FROM bindings WHERE player_key = ?",
                    (new_key,),
                ).fetchone()
                if existing is not None and int(existing["rowid"]) != int(
                    row["binding_rowid"]
                ):
                    conflicts += 1
                    continue
                connection.execute(
                    """
                    UPDATE bindings
                    SET player_key = ?, player_name = ?
                    WHERE rowid = ?
                    """,
                    (new_key, new_name, int(row["binding_rowid"])),
                )
                migrated += 1
            connection.commit()
            return migrated, conflicts
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize_sync(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = self._connect()
        try:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS bindings (
                    owner_key TEXT NOT NULL,
                    platform_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    owner_display TEXT NOT NULL,
                    player_key TEXT NOT NULL UNIQUE,
                    player_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (owner_key, player_key)
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_bindings_owner ON bindings(owner_key)"
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS discord_nickname_state (
                    owner_key TEXT NOT NULL,
                    guild_id TEXT NOT NULL,
                    original_nickname TEXT,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (owner_key, guild_id)
                )
                """
            )
            connection.commit()
        finally:
            connection.close()

    async def bind(
        self,
        *,
        owner_key: str,
        platform_id: str,
        user_id: str,
        owner_display: str,
        player_name: str,
        max_bind_count: int,
    ) -> BindingRecord:
        async with self._lock:
            return await asyncio.to_thread(
                self._bind_sync,
                owner_key,
                platform_id,
                user_id,
                owner_display,
                player_name,
                max_bind_count,
            )

    def _bind_sync(
        self,
        owner_key: str,
        platform_id: str,
        user_id: str,
        owner_display: str,
        player_name: str,
        max_bind_count: int,
    ) -> BindingRecord:
        normalized_owner = owner_key.strip()
        normalized_player = player_name.strip()
        if not normalized_owner or not normalized_player:
            raise BindingError("绑定账号和玩家名不能为空。")
        player_key = normalized_player.casefold()
        created_at = int(time.time())

        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT owner_key, player_name FROM bindings WHERE player_key = ?",
                (player_key,),
            ).fetchone()
            if existing:
                raise PlayerAlreadyBoundError(
                    str(existing["player_name"]), str(existing["owner_key"])
                )

            count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM bindings WHERE owner_key = ?",
                    (normalized_owner,),
                ).fetchone()[0]
            )
            if max_bind_count > 0 and count >= max_bind_count:
                raise BindingLimitError(max_bind_count)

            connection.execute(
                """
                INSERT INTO bindings (
                    owner_key, platform_id, user_id, owner_display,
                    player_key, player_name, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    normalized_owner,
                    platform_id.strip(),
                    user_id.strip(),
                    owner_display.strip(),
                    player_key,
                    normalized_player,
                    created_at,
                ),
            )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

        return BindingRecord(
            owner_key=normalized_owner,
            platform_id=platform_id.strip(),
            user_id=user_id.strip(),
            owner_display=owner_display.strip(),
            player_name=normalized_player,
            created_at=created_at,
        )

    async def unbind(
        self, owner_key: str, player_name: str | None = None
    ) -> list[BindingRecord]:
        async with self._lock:
            return await asyncio.to_thread(self._unbind_sync, owner_key, player_name)

    def _unbind_sync(
        self, owner_key: str, player_name: str | None
    ) -> list[BindingRecord]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            if player_name:
                rows = connection.execute(
                    "SELECT * FROM bindings WHERE owner_key = ? AND player_key = ?",
                    (owner_key.strip(), player_name.strip().casefold()),
                ).fetchall()
                connection.execute(
                    "DELETE FROM bindings WHERE owner_key = ? AND player_key = ?",
                    (owner_key.strip(), player_name.strip().casefold()),
                )
            else:
                rows = connection.execute(
                    "SELECT * FROM bindings WHERE owner_key = ? ORDER BY created_at, player_name",
                    (owner_key.strip(),),
                ).fetchall()
                connection.execute(
                    "DELETE FROM bindings WHERE owner_key = ?", (owner_key.strip(),)
                )
            connection.commit()
            return [_row_to_record(row) for row in rows]
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    async def get_by_owner(self, owner_key: str) -> list[BindingRecord]:
        return await asyncio.to_thread(self._get_by_owner_sync, owner_key)

    def _get_by_owner_sync(self, owner_key: str) -> list[BindingRecord]:
        connection = self._connect()
        try:
            rows = connection.execute(
                "SELECT * FROM bindings WHERE owner_key = ? ORDER BY created_at, rowid",
                (owner_key.strip(),),
            ).fetchall()
        finally:
            connection.close()
        return [_row_to_record(row) for row in rows]

    async def get_by_player(self, player_name: str) -> BindingRecord | None:
        return await asyncio.to_thread(self._get_by_player_sync, player_name)

    def _get_by_player_sync(self, player_name: str) -> BindingRecord | None:
        connection = self._connect()
        try:
            row = connection.execute(
                "SELECT * FROM bindings WHERE player_key = ?",
                (player_name.strip().casefold(),),
            ).fetchone()
        finally:
            connection.close()
        return _row_to_record(row) if row else None

    async def all(self) -> list[BindingRecord]:
        return await asyncio.to_thread(self._all_sync)

    def _all_sync(self) -> list[BindingRecord]:
        connection = self._connect()
        try:
            rows = connection.execute(
                "SELECT * FROM bindings ORDER BY owner_key, created_at, player_name"
            ).fetchall()
        finally:
            connection.close()
        return [_row_to_record(row) for row in rows]

    async def remember_discord_nickname(
        self, owner_key: str, guild_id: str, original_nickname: str | None
    ) -> DiscordNicknameState:
        """Remember the first nickname seen for one owner in one guild.

        Repeated binds must not overwrite the original value, otherwise a later
        unbind would restore a MineAstr-generated nickname instead of the user's
        actual nickname.
        """

        async with self._lock:
            return await asyncio.to_thread(
                self._remember_discord_nickname_sync,
                owner_key,
                guild_id,
                original_nickname,
            )

    def _remember_discord_nickname_sync(
        self, owner_key: str, guild_id: str, original_nickname: str | None
    ) -> DiscordNicknameState:
        normalized_owner = owner_key.strip()
        normalized_guild = guild_id.strip()
        if not normalized_owner or not normalized_guild:
            raise BindingError("Discord 账号和服务器 ID 不能为空。")
        updated_at = int(time.time())
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                INSERT OR IGNORE INTO discord_nickname_state (
                    owner_key, guild_id, original_nickname, updated_at
                ) VALUES (?, ?, ?, ?)
                """,
                (normalized_owner, normalized_guild, original_nickname, updated_at),
            )
            row = connection.execute(
                """
                SELECT * FROM discord_nickname_state
                WHERE owner_key = ? AND guild_id = ?
                """,
                (normalized_owner, normalized_guild),
            ).fetchone()
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
        if row is None:
            raise BindingError("保存 Discord 原昵称失败。")
        return _row_to_nickname_state(row)

    async def get_discord_nickname(
        self, owner_key: str, guild_id: str
    ) -> DiscordNicknameState | None:
        return await asyncio.to_thread(
            self._get_discord_nickname_sync, owner_key, guild_id
        )

    def _get_discord_nickname_sync(
        self, owner_key: str, guild_id: str
    ) -> DiscordNicknameState | None:
        connection = self._connect()
        try:
            row = connection.execute(
                """
                SELECT * FROM discord_nickname_state
                WHERE owner_key = ? AND guild_id = ?
                """,
                (owner_key.strip(), guild_id.strip()),
            ).fetchone()
        finally:
            connection.close()
        return _row_to_nickname_state(row) if row else None

    async def pop_discord_nickname(
        self, owner_key: str, guild_id: str
    ) -> DiscordNicknameState | None:
        async with self._lock:
            return await asyncio.to_thread(
                self._pop_discord_nickname_sync, owner_key, guild_id
            )

    def _pop_discord_nickname_sync(
        self, owner_key: str, guild_id: str
    ) -> DiscordNicknameState | None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT * FROM discord_nickname_state
                WHERE owner_key = ? AND guild_id = ?
                """,
                (owner_key.strip(), guild_id.strip()),
            ).fetchone()
            connection.execute(
                """
                DELETE FROM discord_nickname_state
                WHERE owner_key = ? AND guild_id = ?
                """,
                (owner_key.strip(), guild_id.strip()),
            )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
        return _row_to_nickname_state(row) if row else None


def _row_to_record(row: sqlite3.Row) -> BindingRecord:
    return BindingRecord(
        owner_key=str(row["owner_key"]),
        platform_id=str(row["platform_id"]),
        user_id=str(row["user_id"]),
        owner_display=str(row["owner_display"]),
        player_name=str(row["player_name"]),
        created_at=int(row["created_at"]),
    )


def _row_to_nickname_state(row: sqlite3.Row) -> DiscordNicknameState:
    return DiscordNicknameState(
        owner_key=str(row["owner_key"]),
        guild_id=str(row["guild_id"]),
        original_nickname=(
            str(row["original_nickname"])
            if row["original_nickname"] is not None
            else None
        ),
        updated_at=int(row["updated_at"]),
    )


class CooldownTracker:
    def __init__(self) -> None:
        self._last_used: dict[tuple[str, str], float] = {}

    def check_and_mark(self, category: str, key: str, cooldown_seconds: float) -> float:
        if cooldown_seconds <= 0:
            return 0.0
        now = time.monotonic()
        cooldown_key = (category, key)
        last_used = self._last_used.get(cooldown_key)
        if last_used is not None:
            remaining = cooldown_seconds - (now - last_used)
            if remaining > 0:
                return remaining
        self._last_used[cooldown_key] = now

        stale_before = now - max(cooldown_seconds * 3, 300.0)
        for candidate, used_at in list(self._last_used.items()):
            if used_at < stale_before:
                self._last_used.pop(candidate, None)
        return 0.0


def parse_items(value: Any, *, split_commas: bool = True) -> list[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        raw_items: Iterable[Any] = value
    else:
        text = str(value).replace("\r", "")
        raw_items = re.split(r"[\n,]" if split_commas else r"\n", text)
    return [str(item).strip() for item in raw_items if str(item).strip()]


def normalize_owner_spec(
    owner_spec: str, default_platform_id: str
) -> tuple[str, str, str]:
    """Return (owner_key, platform_id, user_id), accepting Discord mentions."""

    value = owner_spec.strip()
    mention = re.fullmatch(r"<@!?(\d+)>", value)
    if mention:
        value = mention.group(1)
    if ":" in value:
        platform_id, user_id = value.split(":", 1)
    else:
        platform_id, user_id = default_platform_id, value
    platform_id = platform_id.strip()
    user_id = user_id.strip()
    if not platform_id or not user_id:
        raise ValueError("账号格式应为 user_id 或 platform_id:user_id。")
    return f"{platform_id}:{user_id}", platform_id, user_id


MINECRAFT_COLOR_RE = re.compile(r"(?i)§x(?:§[0-9a-f]){6}|§[0-9a-fk-or]|&[0-9a-fk-or]")
MAX_USER_REGEX_LENGTH = 512
USER_REGEX_TIMEOUT_SECONDS = 0.05
UNSAFE_NESTED_QUANTIFIER_RE = re.compile(
    r"\((?:[^()\\]|\\.)*(?:[+*]|\{\d*,?\d*\})(?:[^()\\]|\\.)*\)"
    r"(?:[+*]|\{\d*,?\d*\})"
)


def strip_minecraft_colors(text: str) -> str:
    return MINECRAFT_COLOR_RE.sub("", text)


def _user_regex_allowed(pattern: str) -> bool:
    return (
        bool(pattern)
        and len(pattern) <= MAX_USER_REGEX_LENGTH
        and UNSAFE_NESTED_QUANTIFIER_RE.search(pattern) is None
    )


def safe_user_regex_fullmatch(pattern: str, text: str) -> bool:
    """Match an administrator-supplied regex with a hard execution timeout."""

    if not _user_regex_allowed(pattern):
        return False
    try:
        if _timeout_regex is not None:
            return (
                _timeout_regex.fullmatch(
                    pattern, text, timeout=USER_REGEX_TIMEOUT_SECONDS
                )
                is not None
            )
        return re.fullmatch(pattern, text) is not None
    except _USER_REGEX_EXCEPTIONS:
        return False


def apply_aqqbot_filters(text: str, rules: Any) -> str | None:
    """Apply AQQBot's local ``$filter``/``$regex`` rule syntax.

    Remote ``$url`` rules are intentionally not fetched. Loading a mutable remote
    word list from message-processing code would introduce an SSRF and availability
    dependency into the AstrBot process.
    """

    result = text
    for raw_rule in parse_items(rules, split_commas=False):
        parsed = _parse_filter_rule(raw_rule)
        if parsed is None:
            continue
        kind, pattern, replacement = parsed
        if kind == "filter":
            matched = pattern in result
            if matched and replacement == "!CANCEL":
                return None
            result = result.replace(pattern, replacement)
            continue
        if not _user_regex_allowed(pattern):
            continue
        try:
            if _timeout_regex is not None:
                matched = (
                    _timeout_regex.search(
                        pattern, result, timeout=USER_REGEX_TIMEOUT_SECONDS
                    )
                    is not None
                )
            else:
                matched = re.search(pattern, result) is not None
            if matched and replacement == "!CANCEL":
                return None
            if _timeout_regex is not None:
                result = _timeout_regex.sub(
                    pattern,
                    replacement,
                    result,
                    timeout=USER_REGEX_TIMEOUT_SECONDS,
                )
            else:
                result = re.sub(pattern, replacement, result)
        except _USER_REGEX_EXCEPTIONS:
            continue
    return result


def _parse_filter_rule(rule: str) -> tuple[str, str, str] | None:
    value = rule.strip()
    if value.startswith("$filter:{"):
        kind = "filter"
        body = value[len("$filter:{") :]
    elif value.startswith("$regex:{"):
        kind = "regex"
        body = value[len("$regex:{") :]
    else:
        return None

    marker = re.search(r"}\s+\$replaceTo:\{", body)
    if marker:
        pattern = body[: marker.start()]
        replacement = body[marker.end() :]
        if not replacement.endswith("}"):
            return None
        replacement = replacement[:-1]
    else:
        if not body.endswith("}"):
            return None
        pattern = body[:-1]
        replacement = ""
    return (
        kind,
        pattern.replace("[[space]]", " "),
        replacement.replace("[[space]]", " "),
    )


class _SafeFormatDict(dict[str, Any]):
    def __missing__(self, key: str) -> str:
        return "{" + key + "}"


def format_template(template: str, values: dict[str, Any]) -> str:
    return str(template).format_map(_SafeFormatDict(values))


def trim_message(text: str, maximum: int) -> str:
    normalized = str(text or "").replace("\r", "").strip()
    if maximum > 0 and len(normalized) > maximum:
        return normalized[: max(0, maximum - 1)] + "…"
    return normalized
