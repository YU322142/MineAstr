"""插件默认值与配置分组 / Plugin defaults and configuration groups.

默认值由配置迁移和 WebUI 校验共用；不要在服务中复制常量。
Keep shared defaults here so migration and schema validation use the same definitions.
"""

import copy
import re
from pathlib import Path
from typing import Any
from .aqqbot_compat import DEFAULT_BINDING_DATABASE

MINEASTR_TOOL_HINT = (
    "如果用户询问 Minecraft 服务器状态、在线人数、在线玩家、版本或 MineAstr 连接情况，"
    "请先调用 mineastr_get_server_status 或 mineastr_get_online_players 获取实时数据，再根据工具结果回答。"
    "如果用户在 Minecraft 群聊中明确或隐含表达希望你看看、评价、判断、确认或展示当前画面、建筑、基地、房子、作品、"
    "视角或现场状态，请主动优先调用 mineastr_request_screenshot 请求低清晰度截图，再基于截图回答；"
    "例如“能看看我现在画面吗”、“我的建筑建好啦”、“帮我看看这个建筑”、“我这里好像不对”、“这边怎么样”。"
    "只要上下文带有明显的视觉意愿，就不要只用文字寒暄，优先申请截图。"
    "截图需要玩家客户端允许，不要假装已经看见画面。"
    "玩家询问自己生命、位置、状态、背包物品或附近生物时，优先调用对应的 MineAstr 实时工具。"
    "需要理解房屋、基地或红石装置的方块构成和粗略空间形状时，可调用 mineastr_analyze_region；它不等同于截图。"
    "mineastr_run_server_command 只负责提交用户明确要求的精确指令：公开白名单内会立即执行，"
    "其他指令只会生成待审批项；真实管理员可使用 /mc approve 或 "
    "mineastr_manage_command_approvals 查看并明确批准；"
    "只有当前对话用户确实是管理员且明确要求批准/拒绝具体列表项时才能调用审批操作，"
    "绝不能自行编造请求者身份、替非管理员批准或声称待审批指令已经执行。"
)
MINEASTR_EXTERNAL_HINT_KEYWORDS = (
    "minecraft",
    "mineastr",
    "mc",
    "mc服务器",
    "minecraft服务器",
    "我的世界",
)
SCREENSHOT_DIR = Path("data") / "mineastr" / "screenshots"
MAX_SCREENSHOT_SAVE_BYTES = 2 * 1024 * 1024
TRANSLATION_PROMPT_MAX_LENGTH = 40_000
GAME_TRANSLATION_CACHE_LIMIT = 256
IMAGE_TRANSLATION_CACHE_LIMIT = 128
IMAGE_TRANSLATION_PROTOCOL_BUDGET_SECONDS = 27.0
IMAGE_TRANSLATION_CORRECTION_RESERVE_SECONDS = 7.0
RELAY_MESSAGE_RECORD_LIMIT = 2048
DEFAULT_GAME_IMAGE_INLINE_MAX_BYTES = 1_048_576
MAX_GAME_IMAGE_INLINE_BYTES = 1_400_000
DEFAULT_GAME_IMAGE_MAX_ITEMS = 4
_ACTIVE_RELAY_SESSIONS: set[str] = set()
DEFAULT_PLAYER_NAME_REGEX = r"^\S{1,64}$"
LEGACY_PLAYER_NAME_REGEX = r"^[A-Za-z0-9_]{3,16}$"
SAFE_COMMAND_ADMIN_RE = re.compile(r"^[a-z0-9_.:@-]{1,128}$", re.IGNORECASE)

NOTIFICATION_PRESETS: dict[str, dict[str, str]] = {
    "zh_CN": {
        "notify_server_start": "[MC] {server} 已连接。",
        "notify_server_stop": "[MC] {server} 已断开。",
        "notify_player_join": "[MC] {player}{binding} 进入了服务器。",
        "notify_player_leave": "[MC] {player}{binding} 离开了服务器。",
        "notify_player_death": "[MC] {player}{binding} 因 {reason} 在游戏内死亡。",
        "login_reject_message": "[MC] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。",
    },
    "en_US": {
        "notify_server_start": "[MC] {server} connected.",
        "notify_server_stop": "[MC] {server} disconnected.",
        "notify_player_join": "[MC] {player}{binding} joined the server.",
        "notify_player_leave": "[MC] {player}{binding} left the server.",
        "notify_player_death": "[MC] {player}{binding} died in-game: {reason}.",
        "login_reject_message": "[MC] This game account is not bound. Use /mc bind <player name> on QQ/Discord first.",
    },
}
LEGACY_NOTIFICATION_DEFAULTS = {
    "notify_server_start": "[MineAstr] {server} 已连接。",
    "notify_server_stop": "[MineAstr] {server} 已断开。",
    "notify_player_join": "[MineAstr] {player}{binding} 进入了服务器。",
    "notify_player_leave": "[MineAstr] {player}{binding} 离开了服务器。",
    "notify_player_death": "[MineAstr] {player}{binding} 因 {reason} 死亡。",
    "login_reject_message": "[MineAstr] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。",
}
NOTIFICATION_EVENT_CONFIG = {
    "server_start": ("notify_server_start_enabled", "notify_server_start"),
    "server_stop": ("notify_server_stop_enabled", "notify_server_stop"),
    "player_join": ("notify_player_join_enabled", "notify_player_join"),
    "player_leave": ("notify_player_leave_enabled", "notify_player_leave"),
    "player_death": ("notify_player_death_enabled", "notify_player_death"),
}

LOCALIZED_NOTIFICATION_TEMPLATE_DEFAULTS: dict[str, dict[str, str]] = {
    language: {
        template_key: "" for _, template_key in NOTIFICATION_EVENT_CONFIG.values()
    }
    for language in NOTIFICATION_PRESETS
}

QQ_NOTIFICATION_DEFAULTS: dict[str, Any] = {
    "platform_ids": "default",
    "language": "zh_CN",
    "chat_translation_enabled": False,
    "chat_translation_languages": "zh_cn",
    "chat_translation_show_original": True,
    "chat_translation_custom_instructions": "",
    "notifications_enabled": True,
    "notify_server_start_enabled": True,
    "notify_server_stop_enabled": True,
    "notify_player_join_enabled": True,
    "notify_player_leave_enabled": True,
    "notify_player_death_enabled": True,
    "notify_server_start": "",
    "notify_server_stop": "",
    "notify_player_join": "",
    "notify_player_leave": "",
    "notify_player_death": "",
    "localized_templates": copy.deepcopy(LOCALIZED_NOTIFICATION_TEMPLATE_DEFAULTS),
}
DISCORD_NOTIFICATION_DEFAULTS: dict[str, Any] = copy.deepcopy(QQ_NOTIFICATION_DEFAULTS)
DISCORD_NOTIFICATION_DEFAULTS["platform_ids"] = "discord"
DISCORD_NOTIFICATION_DEFAULTS["chat_translation_languages"] = "en_us"

DAMAGE_REASON_PRESETS: dict[str, dict[str, str]] = {
    "zh_CN": {
        "inFire": "身处火焰中",
        "lightningBolt": "被闪电击中",
        "onFire": "被烧死",
        "lava": "试图在熔岩里游泳",
        "hotFloor": "踩到了危险的岩浆块",
        "inWall": "在墙里窒息",
        "cramming": "因实体挤压窒息",
        "drown": "溺水",
        "starve": "饥饿",
        "cactus": "被仙人掌刺伤",
        "fall": "从高处坠落",
        "flyIntoWall": "飞行时撞上墙壁",
        "outOfWorld": "掉出了世界",
        "generic": "未知伤害",
        "genericKill": "被命令杀死",
        "magic": "魔法伤害",
        "wither": "凋零效果",
        "dragonBreath": "末影龙吐息",
        "dryout": "脱水",
        "sweetBerryBush": "被甜浆果丛刺伤",
        "freeze": "冻伤",
        "stalagmite": "掉在石笋上",
        "fallingBlock": "被坠落的方块砸中",
        "anvil": "被坠落的铁砧砸中",
        "fallingStalactite": "被坠落的钟乳石砸中",
        "sting": "被 {attacker} 蜇伤",
        "mob": "被 {attacker} 杀死",
        "player": "被 {attacker} 杀死",
        "spear": "被 {attacker} 用长矛杀死",
        "arrow": "被 {attacker} 射杀",
        "trident": "被 {attacker} 用三叉戟杀死",
        "fireworks": "被烟花火箭炸死",
        "fireball": "被 {attacker} 的火球烧死",
        "witherSkull": "被 {attacker} 的凋灵之首杀死",
        "thrown": "被 {attacker} 投掷的物体击中",
        "indirectMagic": "被 {attacker} 的魔法杀死",
        "thorns": "试图伤害 {attacker} 时被反伤",
        "explosion": "爆炸",
        "explosion.player": "被 {attacker} 引发的爆炸炸死",
        "sonic_boom": "被 {attacker} 的音波尖啸杀死",
        "badRespawnPoint": "遭遇了故意的游戏设计",
        "outsideBorder": "越过了世界边界",
        "mace_smash": "被 {attacker} 用重锤击杀",
    },
    "en_US": {
        "inFire": "went up in flames",
        "lightningBolt": "was struck by lightning",
        "onFire": "burned to death",
        "lava": "tried to swim in lava",
        "hotFloor": "discovered the floor was lava",
        "inWall": "suffocated in a wall",
        "cramming": "was squashed too much",
        "drown": "drowned",
        "starve": "starved to death",
        "cactus": "was pricked to death",
        "fall": "fell from a high place",
        "flyIntoWall": "experienced kinetic energy",
        "outOfWorld": "fell out of the world",
        "generic": "died from unknown damage",
        "genericKill": "was killed by a command",
        "magic": "was killed by magic",
        "wither": "withered away",
        "dragonBreath": "was roasted in dragon breath",
        "dryout": "died from dehydration",
        "sweetBerryBush": "was poked to death by a sweet berry bush",
        "freeze": "froze to death",
        "stalagmite": "was impaled on a stalagmite",
        "fallingBlock": "was squashed by a falling block",
        "anvil": "was squashed by a falling anvil",
        "fallingStalactite": "was skewered by a falling stalactite",
        "sting": "was stung to death by {attacker}",
        "mob": "was slain by {attacker}",
        "player": "was slain by {attacker}",
        "spear": "was speared by {attacker}",
        "arrow": "was shot by {attacker}",
        "trident": "was impaled by {attacker}",
        "fireworks": "went off with a bang",
        "fireball": "was fireballed by {attacker}",
        "witherSkull": "was shot by a skull from {attacker}",
        "thrown": "was pummeled by {attacker}",
        "indirectMagic": "was killed by {attacker} using magic",
        "thorns": "was killed trying to hurt {attacker}",
        "explosion": "blew up",
        "explosion.player": "was blown up by {attacker}",
        "sonic_boom": "was obliterated by a sonic shriek from {attacker}",
        "badRespawnPoint": "was killed by intentional game design",
        "outsideBorder": "left the confines of the world",
        "mace_smash": "was smashed by {attacker}",
    },
}

AQQBOT_DEFAULT_CONFIG: dict[str, Any] = {
    "bridge_enabled": True,
    "relay_sessions": "",
    "relay_routes": "",
    "relay_prefix": "",
    "relay_wake_messages": False,
    "relay_bot_conversations_to_game": True,
    "relay_images_to_game": True,
    "game_image_inline_max_bytes": DEFAULT_GAME_IMAGE_INLINE_MAX_BYTES,
    "game_image_max_items": DEFAULT_GAME_IMAGE_MAX_ITEMS,
    "relay_commands": False,
    "chat_to_game_template": "{message}",
    "game_to_chat_template": "[MC/{server}] {player}: {message}",
    "chat_to_game_filters": "",
    "game_to_chat_filters": "",
    "max_relay_length": 500,
    "game_translation_enabled": False,
    "game_translation_provider_id": "",
    "game_translation_fallback_provider_ids": [],
    "game_translation_languages": "zh_cn\nen_us",
    "game_translation_show_original": True,
    "game_translation_timeout_seconds": 20,
    "translation_context_messages": 0,
    "translation_custom_instructions": "",
    "image_translation_prompt": "",
    "translation_glossary_path": "",
    "translation_glossary_max_chars": 12_000,
    "image_translation_glossary_max_chars": 12_000,
    "cache_cleanup_enabled": True,
    "cache_cleanup_interval_seconds": 300,
    "memory_cache_retention_seconds": 86400,
    "relay_record_retention_seconds": 86400,
    "screenshot_retention_hours": 168,
    "screenshot_cache_max_mib": 256,
    "binding_enabled": True,
    "binding_database": DEFAULT_BINDING_DATABASE,
    "verify_method": "GROUP_NAME",
    "verify_code_expire_seconds": 300,
    "need_bind_to_login": False,
    "max_bind_count": 1,
    "player_name_regex": DEFAULT_PLAYER_NAME_REGEX,
    "bind_cooldown_seconds": 60,
    "unbind_cooldown_seconds": 86400,
    "sync_binding_to_server": False,
    "binding_sync_required": False,
    "binding_sync_interval_seconds": 60,
    "game_image_public_base_url": "",
    "qq_auto_unbind_on_leave": True,
    "qq_auto_group_card": True,
    "qq_group_ids": "",
    "qq_group_card_template": "{players}",
    "qq_notification_settings": copy.deepcopy(QQ_NOTIFICATION_DEFAULTS),
    "discord_auto_unbind_on_leave": True,
    "discord_auto_nickname": True,
    "discord_restore_nickname_on_unbind": True,
    "discord_guild_ids": "",
    "discord_nickname_template": "{players}",
    "discord_nickname_reason": "MineAstr Minecraft 账号绑定同步",
    "discord_notification_settings": copy.deepcopy(DISCORD_NOTIFICATION_DEFAULTS),
    "discord_channel_settings": [],
    "remote_command_enabled": False,
    "remote_command_admin_only": True,
    "bridge_admin_users": "",
    "sync_command_admins_to_server": True,
    "player_mention_enabled": True,
    "notifications_enabled": True,
    "notification_language": "zh_CN",
    "notify_server_start_enabled": True,
    "notify_server_stop_enabled": True,
    "notify_player_join_enabled": True,
    "notify_player_leave_enabled": True,
    "notify_player_death_enabled": True,
    "notify_server_start": NOTIFICATION_PRESETS["zh_CN"]["notify_server_start"],
    "notify_server_stop": NOTIFICATION_PRESETS["zh_CN"]["notify_server_stop"],
    "notify_player_join": NOTIFICATION_PRESETS["zh_CN"]["notify_player_join"],
    "notify_player_leave": NOTIFICATION_PRESETS["zh_CN"]["notify_player_leave"],
    "notify_player_death": NOTIFICATION_PRESETS["zh_CN"]["notify_player_death"],
    "login_reject_message": NOTIFICATION_PRESETS["zh_CN"]["login_reject_message"],
}

CONFIG_LAYOUT_VERSION = 2
CONFIG_GROUP_KEYS: dict[str, tuple[str, ...]] = {
    "bridge_settings": (
        "bridge_enabled",
        "relay_sessions",
        "relay_routes",
        "relay_prefix",
        "relay_wake_messages",
        "relay_bot_conversations_to_game",
        "relay_images_to_game",
        "game_image_inline_max_bytes",
        "game_image_max_items",
        "game_image_public_base_url",
        "relay_commands",
        "chat_to_game_template",
        "game_to_chat_template",
        "chat_to_game_filters",
        "game_to_chat_filters",
        "max_relay_length",
        "game_translation_enabled",
        "game_translation_provider_id",
        "game_translation_fallback_provider_ids",
        "game_translation_languages",
        "game_translation_show_original",
        "game_translation_timeout_seconds",
        "translation_context_messages",
        "translation_custom_instructions",
        "image_translation_prompt",
        "translation_glossary_path",
        "translation_glossary_max_chars",
        "image_translation_glossary_max_chars",
        "discord_channel_settings",
    ),
    "binding_settings": (
        "binding_enabled",
        "binding_database",
        "verify_method",
        "verify_code_expire_seconds",
        "need_bind_to_login",
        "max_bind_count",
        "player_name_regex",
        "bind_cooldown_seconds",
        "unbind_cooldown_seconds",
        "sync_binding_to_server",
        "binding_sync_required",
        "binding_sync_interval_seconds",
        "login_reject_message",
    ),
    "qq_settings": (
        "qq_auto_unbind_on_leave",
        "qq_auto_group_card",
        "qq_group_ids",
        "qq_group_card_template",
        "qq_notification_settings",
    ),
    "discord_settings": (
        "discord_auto_unbind_on_leave",
        "discord_auto_nickname",
        "discord_restore_nickname_on_unbind",
        "discord_guild_ids",
        "discord_nickname_template",
        "discord_nickname_reason",
        "discord_notification_settings",
    ),
    "admin_command_settings": (
        "bridge_admin_users",
        "sync_command_admins_to_server",
        "remote_command_enabled",
        "remote_command_admin_only",
    ),
    "notification_settings": (
        "player_mention_enabled",
        "notifications_enabled",
        "notification_language",
        "notify_server_start_enabled",
        "notify_server_stop_enabled",
        "notify_player_join_enabled",
        "notify_player_leave_enabled",
        "notify_player_death_enabled",
        "notify_server_start",
        "notify_server_stop",
        "notify_player_join",
        "notify_player_leave",
        "notify_player_death",
    ),
    "maintenance_settings": (
        "cache_cleanup_enabled",
        "cache_cleanup_interval_seconds",
        "memory_cache_retention_seconds",
        "relay_record_retention_seconds",
        "screenshot_retention_hours",
        "screenshot_cache_max_mib",
    ),
}
CONFIG_KEY_GROUP = {
    key: group for group, keys in CONFIG_GROUP_KEYS.items() for key in keys
}
