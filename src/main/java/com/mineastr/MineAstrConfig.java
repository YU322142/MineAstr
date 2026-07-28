package com.mineastr;

import java.util.List;

public final class MineAstrConfig {
    public static final String DEFAULT_LOGIN_CODE_MESSAGE =
            "\n绑定验证码：{code}\n请在 Discord/聊天平台使用 /mc bind {code}";
    private static final MineAstrConfigStore STORE = new MineAstrConfigStore("mineastr-common.json");

    public static final MineAstrConfigStore.BooleanValue ENABLED = STORE.bool("enabled", true);
    public static final MineAstrConfigStore.StringValue WEBSOCKET_URL = STORE.string("websocketUrl", "ws://127.0.0.1:8765/ws", 2048);
    public static final MineAstrConfigStore.StringValue TOKEN = STORE.string("token", "change-me", 512);
    public static final MineAstrConfigStore.StringValue SERVER_ID = STORE.string("serverId", "minecraft", 64);
    public static final MineAstrConfigStore.StringValue SERVER_NAME = STORE.string("serverName", "Minecraft 服务器", 64);
    public static final MineAstrConfigStore.StringValue BOT_DISPLAY_NAME = STORE.string("botDisplayName", "AstrBot", 64);
    public static final MineAstrConfigStore.IntValue RECONNECT_SECONDS = STORE.integer("reconnectSeconds", 5, 1, 300);
    public static final MineAstrConfigStore.IntValue MAX_MESSAGE_LENGTH = STORE.integer("maxMessageLength", 1000, 1, 4096);

    public static final MineAstrConfigStore.BooleanValue ENABLE_PLAYER_STATE_TOOL = STORE.bool("enablePlayerStateTool", true);
    public static final MineAstrConfigStore.BooleanValue ENABLE_INVENTORY_TOOL = STORE.bool("enableInventoryTool", true);
    public static final MineAstrConfigStore.BooleanValue ENABLE_NEARBY_ENTITIES_TOOL = STORE.bool("enableNearbyEntitiesTool", true);
    public static final MineAstrConfigStore.BooleanValue ENABLE_REGION_TOOL = STORE.bool("enableRegionTool", true);
    public static final MineAstrConfigStore.IntValue REGION_MAX_BLOCKS = STORE.integer("regionMaxBlocks", 32768, 4096, 131072);

    public static final MineAstrConfigStore.BooleanValue ENABLE_COMMAND_TOOL = STORE.bool("enableCommandTool", false);
    public static final MineAstrConfigStore.BooleanValue SYNC_TRUSTED_COMMAND_USERS = STORE.bool("syncTrustedCommandUsers", false);
    public static final MineAstrConfigStore.StringListValue TRUSTED_COMMAND_USERS = STORE.stringList("trustedCommandUsers", List.of());
    public static final MineAstrConfigStore.StringListValue ALLOWED_COMMAND_RULES = STORE.stringList(
            "allowedCommandRules", List.of("list", "seed", "time query day", "time query daytime", "time query gametime"));
    public static final MineAstrConfigStore.IntValue COMMAND_PERMISSION_LEVEL = STORE.integer("commandPermissionLevel", 4, 0, 4);
    public static final MineAstrConfigStore.IntValue COMMAND_MAX_LENGTH = STORE.integer("commandMaxLength", 256, 1, 1024);

    public static final MineAstrConfigStore.BooleanValue ENABLE_PLAYER_NOTIFICATIONS = STORE.bool("enablePlayerNotifications", true);
    public static final MineAstrConfigStore.BooleanValue NOTIFY_ACTION_BAR = STORE.bool("notifyActionBar", true);
    public static final MineAstrConfigStore.BooleanValue NOTIFY_TITLE = STORE.bool("notifyTitle", false);
    public static final MineAstrConfigStore.BooleanValue NOTIFY_SOUND = STORE.bool("notifySound", true);
    public static final MineAstrConfigStore.IntValue NOTIFICATION_MAX_LENGTH = STORE.integer("notificationMaxLength", 512, 32, 2000);

    public static final MineAstrConfigStore.BooleanValue ENABLE_BINDING_SYNC = STORE.bool("enableBindingSync", false);
    public static final MineAstrConfigStore.BooleanValue BINDING_SYNC_WHITELIST = STORE.bool("bindingSyncWhitelist", false);
    public static final MineAstrConfigStore.BooleanValue LOGIN_BINDING_CHECK_ENABLED = STORE.bool("loginBindingCheckEnabled", false);
    public static final MineAstrConfigStore.IntValue LOGIN_CHECK_TIMEOUT_SECONDS = STORE.integer("loginCheckTimeoutSeconds", 5, 1, 30);
    public static final MineAstrConfigStore.BooleanValue LOGIN_CHECK_FAIL_OPEN = STORE.bool("loginCheckFailOpen", true);
    public static final MineAstrConfigStore.BooleanValue GENERATE_BINDING_CODE_ON_REJECT = STORE.bool("generateBindingCodeOnReject", true);
    public static final MineAstrConfigStore.IntValue VERIFY_CODE_LENGTH = STORE.integer("verifyCodeLength", 6, 4, 12);
    public static final MineAstrConfigStore.StringValue LOGIN_CODE_MESSAGE = STORE.string(
            "loginCodeMessage", DEFAULT_LOGIN_CODE_MESSAGE, 512);

    static final Spec SPEC = new Spec();

    public static void load() {
        STORE.load();
    }

    public static final class Spec {
        public void save() {
            STORE.save();
        }
    }

    private MineAstrConfig() {
    }
}
