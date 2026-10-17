"""AstrBot 兼容导入和诊断日志 / Compatibility imports and diagnostics.

此模块只建立运行时兼容入口，不启动领域后台任务。
This leaf module creates compatibility symbols without starting domain tasks.
"""

import asyncio
import base64
import copy
import hashlib
import json
import logging
import math
import re
import time
import urllib.parse
from collections import deque
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Callable

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, MessageChain, filter
from astrbot.api.message_components import Plain
from astrbot.core.utils.astrbot_path import get_astrbot_data_path

try:
    from astrbot.api.message_components import Image, Reply
except ImportError:

    class Image:  # type: ignore[no-redef]
        def __init__(self, file: str = "", **_: Any):
            self.file = file

        @staticmethod
        def fromURL(url: str, **_: Any):
            return Plain(f"[图片] {url}")

    class Reply:  # type: ignore[no-redef]
        pass


from astrbot.api.star import Context, Star, register

from .aqqbot_compat import (
    DEFAULT_BINDING_DATABASE,
    BindingError,
    BindingStore,
    CooldownTracker,
    PlayerAlreadyBoundError,
    apply_aqqbot_filters,
    format_template,
    normalize_owner_spec,
    parse_items,
    safe_user_regex_fullmatch,
    sanitize_minecraft_login_name,
    strip_minecraft_colors,
    trim_message,
)
from .mineastr_glossary import GlossaryProvider, append_glossary_instructions
from .mineastr_media import (
    QQImageSource,
    discord_media,
    qq_images,
    qq_market_faces,
    unique_media,
)

try:
    from mcp.types import CallToolResult, ImageContent, TextContent
except ImportError:
    CallToolResult = None
    ImageContent = None
    TextContent = None


_RELAY_DIAGNOSTICS = logging.getLogger("mineastr.relay_diagnostics")
_RELAY_DIAGNOSTICS.setLevel(logging.INFO)
_RELAY_DIAGNOSTICS.propagate = False
if not _RELAY_DIAGNOSTICS.handlers:
    diagnostics_path = Path(get_astrbot_data_path()) / "plugin_data" / "mineastr"
    diagnostics_path.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(
        diagnostics_path / "relay_diagnostics.log",
        maxBytes=1_000_000,
        backupCount=2,
        encoding="utf-8",
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    _RELAY_DIAGNOSTICS.addHandler(handler)
