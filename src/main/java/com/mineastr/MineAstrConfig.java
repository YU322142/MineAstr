package com.mineastr;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineAstrConfig {
    public static final String DEFAULT_LOGIN_CODE_MESSAGE =
            "\n绑定验证码：{code}\n请在 QQ/Discord 使用 /mc bind {code}";
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("是否启用 MineAstr 并连接到 AstrBot。")
            .define("enabled", true);
    public static final ModConfigSpec.ConfigValue<String> WEBSOCKET_URL = BUILDER
            .comment("AstrBot Minecraft 平台适配器的 WebSocket 地址。")
            .define("websocketUrl", "ws://127.0.0.1:8765/ws");
    public static final ModConfigSpec.ConfigValue<String> TOKEN = BUILDER
            .comment("必须与 AstrBot Minecraft 平台适配器中的 token 一致。")
            .define("token", "change-me");
    public static final ModConfigSpec.ConfigValue<String> SERVER_ID = BUILDER
            .comment("发送给 AstrBot 的稳定服务器 ID。")
            .define("serverId", "minecraft");
    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME = BUILDER
            .comment("发送给 AstrBot 的服务器显示名称。")
            .define("serverName", "Minecraft 服务器");
    public static final ModConfigSpec.ConfigValue<String> BOT_DISPLAY_NAME = BUILDER
            .comment("AstrBot 消息广播到 Minecraft 时显示的名称。")
            .define("botDisplayName", "AstrBot");
    public static final ModConfigSpec.IntValue RECONNECT_SECONDS = BUILDER
            .comment("WebSocket 断开后的重连间隔，单位为秒。")
            .defineInRange("reconnectSeconds", 5, 1, 300);
    public static final ModConfigSpec.IntValue MAX_MESSAGE_LENGTH = BUILDER
            .comment("转发到 AstrBot 的单条玩家聊天最大长度。")
            .defineInRange("maxMessageLength", 1000, 1, 4096);
    public static final ModConfigSpec.BooleanValue ENABLE_BOT_IMAGE_MESSAGES = BUILDER
            .comment("是否允许 AstrBot 向安装 ChatImage 且主动开启接收的客户端发送图片。")
            .define("enableBotImageMessages", true);

    public static final ModConfigSpec.BooleanValue ENABLE_PLAYER_STATE_TOOL = BUILDER
            .comment("是否允许 AstrBot 查询在线玩家状态。")
            .define("enablePlayerStateTool", true);
    public static final ModConfigSpec.BooleanValue ENABLE_INVENTORY_TOOL = BUILDER
            .comment("是否允许 AstrBot 查询在线玩家背包。")
            .define("enableInventoryTool", true);
    public static final ModConfigSpec.BooleanValue ENABLE_NEARBY_ENTITIES_TOOL = BUILDER
            .comment("是否允许 AstrBot 查询玩家附近实体。")
            .define("enableNearbyEntitiesTool", true);
    public static final ModConfigSpec.BooleanValue ENABLE_REGION_TOOL = BUILDER
            .comment("是否允许 AstrBot 分析已加载区域的方块与建筑特征。")
            .define("enableRegionTool", true);
    public static final ModConfigSpec.IntValue REGION_MAX_BLOCKS = BUILDER
            .comment("单次区域特征分析最多扫描的方块数。")
            .defineInRange("regionMaxBlocks", 32768, 4096, 131072);

    public static final ModConfigSpec.BooleanValue ENABLE_COMMAND_TOOL = BUILDER
            .comment("是否允许 AstrBot 请求执行服务器命令，默认关闭。")
            .define("enableCommandTool", false);
    public static final ModConfigSpec.BooleanValue SYNC_TRUSTED_COMMAND_USERS = BUILDER
            .comment("是否接受 AstrBot 实时同步的命令管理员名单。")
            .define("syncTrustedCommandUsers", true);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRUSTED_COMMAND_USERS = BUILDER
            .comment("静态可信用户 ID、Minecraft UUID 或玩家名。")
            .defineListAllowEmpty("trustedCommandUsers", List.of(), MineAstrConfig::isNonBlankString);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_COMMAND_RULES = BUILDER
            .comment("可直接执行的命令规则；以 ' *' 结尾允许该前缀及参数。")
            .defineListAllowEmpty(
                    "allowedCommandRules",
                    List.of("list", "seed", "time query day", "time query daytime", "time query gametime"),
                    MineAstrConfig::isNonBlankString);
    public static final ModConfigSpec.IntValue COMMAND_PERMISSION_LEVEL = BUILDER
            .comment("命令工具执行命令时使用的权限等级。")
            .defineInRange("commandPermissionLevel", 4, 0, 4);
    public static final ModConfigSpec.IntValue COMMAND_MAX_LENGTH = BUILDER
            .comment("命令工具允许的最大命令长度。")
            .defineInRange("commandMaxLength", 256, 1, 1024);
    public static final ModConfigSpec.IntValue COMMAND_APPROVAL_TIMEOUT_SECONDS = BUILDER
            .comment("待审批命令的有效时间，单位为秒。")
            .defineInRange("commandApprovalTimeoutSeconds", 300, 30, 3600);
    public static final ModConfigSpec.IntValue COMMAND_MAX_PENDING_APPROVALS = BUILDER
            .comment("服务器最多保留的待审批命令数量。")
            .defineInRange("commandMaxPendingApprovals", 128, 1, 512);

    public static final ModConfigSpec.BooleanValue ENABLE_PLAYER_NOTIFICATIONS = BUILDER
            .comment("是否允许 AstrBot 向指定 Minecraft 玩家发送提醒。")
            .define("enablePlayerNotifications", true);
    public static final ModConfigSpec.BooleanValue NOTIFY_ACTION_BAR = BUILDER
            .comment("是否用 actionbar 显示玩家提醒。")
            .define("notifyActionBar", true);
    public static final ModConfigSpec.BooleanValue NOTIFY_TITLE = BUILDER
            .comment("是否用标题显示玩家提醒。")
            .define("notifyTitle", false);
    public static final ModConfigSpec.BooleanValue NOTIFY_SOUND = BUILDER
            .comment("玩家收到提醒时是否播放提示音。")
            .define("notifySound", true);
    public static final ModConfigSpec.IntValue NOTIFICATION_MAX_LENGTH = BUILDER
            .comment("单条玩家提醒的最大文本长度。")
            .defineInRange("notificationMaxLength", 512, 32, 2000);

    public static final ModConfigSpec.BooleanValue ENABLE_BINDING_SYNC = BUILDER
            .comment("是否从 AstrBot 同步账号绑定到服务器。")
            .define("enableBindingSync", false);
    public static final ModConfigSpec.BooleanValue BINDING_SYNC_WHITELIST = BUILDER
            .comment("绑定同步时是否同步原版白名单。")
            .define("bindingSyncWhitelist", false);
    public static final ModConfigSpec.BooleanValue LOGIN_BINDING_CHECK_ENABLED = BUILDER
            .comment("玩家登录前是否向 AstrBot 检查账号绑定。")
            .define("loginBindingCheckEnabled", false);
    public static final ModConfigSpec.IntValue LOGIN_CHECK_TIMEOUT_SECONDS = BUILDER
            .comment("登录绑定检查超时，单位为秒。")
            .defineInRange("loginCheckTimeoutSeconds", 5, 1, 30);
    public static final ModConfigSpec.BooleanValue LOGIN_CHECK_FAIL_OPEN = BUILDER
            .comment("AstrBot 不可用时是否允许玩家登录。")
            .define("loginCheckFailOpen", true);
    public static final ModConfigSpec.BooleanValue GENERATE_BINDING_CODE_ON_REJECT = BUILDER
            .comment("未绑定玩家被拒绝时是否生成验证码。")
            .define("generateBindingCodeOnReject", true);
    public static final ModConfigSpec.IntValue VERIFY_CODE_LENGTH = BUILDER
            .comment("登录绑定验证码长度。")
            .defineInRange("verifyCodeLength", 6, 4, 12);
    public static final ModConfigSpec.ConfigValue<String> LOGIN_CODE_MESSAGE = BUILDER
            .comment("登录拒绝时附加的绑定提示，{code} 会替换为验证码。")
            .define("loginCodeMessage", DEFAULT_LOGIN_CODE_MESSAGE);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrConfig() {
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= 256;
    }
}
