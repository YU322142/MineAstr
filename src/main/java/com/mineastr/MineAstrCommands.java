package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

public final class MineAstrCommands {
    private static final ConcurrentMap<String, PendingCommandApproval> pendingCommandApprovals = new ConcurrentHashMap<>();
    private static final Set<String> syncedTrustedCommandUsers = ConcurrentHashMap.newKeySet();
    private static final AtomicLong syncedTrustedCommandUsersRevision = new AtomicLong(-1L);

    private MineAstrCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MineAstrBridge bridge) {
        dispatcher.register(Commands.literal("mineastr")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource(), bridge)))
                .then(Commands.literal("reconnect")
                        .executes(context -> reconnect(context.getSource(), bridge))));
    }

    public static void handleCommandQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_COMMAND_TOOL.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "服务端命令工具默认关闭；请由服务器管理员在配置中显式启用。");
            return;
        }
        String bridgeToken = MineAstrConfig.TOKEN.get().strip();
        if (bridgeToken.isEmpty() || "change-me".equalsIgnoreCase(bridgeToken)) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "命令工具要求先把默认 token 改为安全随机字符串。");
            return;
        }

        String action = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "action", "request"), 16).toLowerCase(Locale.ROOT);
        Requester requester = Requester.from(payload);
        cleanupExpired();

        if ("approve".equals(action) || "reject".equals(action) || "list".equals(action)) {
            handleApprovalAction(socket, messageId, action, payload, requester, server);
            return;
        }
        if (!"request".equals(action)) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "不支持的命令操作：" + action);
            return;
        }

        String command = normalizeCommand(MineAstrProtocol.getString(payload, "command", ""));
        if (command.isEmpty()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "命令不能为空。");
            return;
        }
        if (command.length() > MineAstrConfig.COMMAND_MAX_LENGTH.getAsInt()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "命令长度超过服务端限制。");
            return;
        }

        if (isAllowedCommand(command)) {
            execute(socket, messageId, server, command, requester, "", "");
            return;
        }

        for (var entry : pendingCommandApprovals.entrySet()) {
            PendingCommandApproval pending = entry.getValue();
            if (pending.command().equals(command)
                    && pending.requester().auditName().equals(requester.auditName())) {
                sendPendingResult(socket, messageId, entry.getKey(), pending);
                return;
            }
        }

        if (pendingCommandApprovals.size() >= MineAstrConfig.COMMAND_MAX_PENDING_APPROVALS.getAsInt()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "待审批命令数量已达服务端上限，请先由管理员处理现有申请。");
            return;
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + MineAstrConfig.COMMAND_APPROVAL_TIMEOUT_SECONDS.getAsInt() * 1000L;
        String approvalId = UUID.randomUUID().toString();
        PendingCommandApproval pending = new PendingCommandApproval(command, requester, now, expiresAt);
        pendingCommandApprovals.put(approvalId, pending);
        MineAstr.LOGGER.warn(
                "MineAstr 已创建白名单外命令审批：approval_id={} requester={} command={}",
                approvalId, requester.auditName(), command);
        sendPendingResult(socket, messageId, approvalId, pending);
    }

    public static void handleTrustedUsersQuery(WebSocket socket, String messageId, JsonObject payload) {
        if (!MineAstrConfig.SYNC_TRUSTED_COMMAND_USERS.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "trusted_users", "trusted_user_sync_disabled");
            return;
        }
        String action = MineAstrProtocol.trimFlatContent(
                MineAstrProtocol.getString(payload, "action", ""), 16).toLowerCase(Locale.ROOT);
        if (!"replace".equals(action) || !payload.has("users") || !payload.get("users").isJsonArray()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "trusted_users", "invalid_trusted_users_request");
            return;
        }

        JsonArray users = payload.getAsJsonArray("users");
        if (users.size() > 256) {
            MineAstrProtocol.sendQueryError(socket, messageId, "trusted_users", "too_many_trusted_users");
            return;
        }

        Set<String> replacement = new java.util.HashSet<>();
        for (var element : users) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                MineAstrProtocol.sendQueryError(socket, messageId, "trusted_users", "invalid_trusted_user");
                return;
            }
            String identity = element.getAsString().strip().toLowerCase(Locale.ROOT);
            if (!isSafeSyncedTrustedIdentity(identity)) {
                MineAstrProtocol.sendQueryError(socket, messageId, "trusted_users", "invalid_trusted_user");
                return;
            }
            replacement.add(identity);
        }

        long revision = Math.max(0L, MineAstrProtocol.getLong(payload, "revision", 0L));
        long currentRevision = syncedTrustedCommandUsersRevision.get();
        if (revision < currentRevision) {
            MineAstrProtocol.sendQueryError(socket, messageId, "trusted_users", "stale_trusted_users_revision");
            return;
        }

        syncedTrustedCommandUsers.clear();
        syncedTrustedCommandUsers.addAll(replacement);
        syncedTrustedCommandUsersRevision.set(revision);
        MineAstr.LOGGER.warn(
                "MineAstr 已更新 AstrBot 同步命令可信名单：synced_count={} static_count={}",
                replacement.size(),
                MineAstrConfig.TRUSTED_COMMAND_USERS.get().size());

        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        data.addProperty("revision", revision);
        data.addProperty("synced_count", replacement.size());
        data.addProperty("static_count", MineAstrConfig.TRUSTED_COMMAND_USERS.get().size());
        MineAstrProtocol.sendQueryResult(socket, messageId, "trusted_users", data);
    }

    public static void clearAll() {
        pendingCommandApprovals.clear();
        syncedTrustedCommandUsers.clear();
        syncedTrustedCommandUsersRevision.set(-1L);
    }

    private static void handleApprovalAction(
            WebSocket socket,
            String messageId,
            String action,
            JsonObject payload,
            Requester approver,
            MinecraftServer server) {
        if (!isTrustedRequester(approver)) {
            MineAstr.LOGGER.warn("MineAstr 已拒绝非管理员命令审批：requester={} action={}", approver.auditName(), action);
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "当前审批者不在 trustedCommandUsers 管理员名单中。");
            return;
        }

        if ("list".equals(action)) {
            JsonObject data = new JsonObject();
            data.addProperty("status", "pending_list");
            JsonArray approvals = new JsonArray();
            pendingCommandApprovals.forEach((approvalId, pending) -> {
                JsonObject item = new JsonObject();
                item.addProperty("approval_id", approvalId);
                item.addProperty("command", pending.command());
                item.addProperty("requester", pending.requester().auditName());
                item.addProperty("created_at_ms", pending.createdAtMs());
                item.addProperty("expires_at_ms", pending.expiresAtMs());
                approvals.add(item);
            });
            data.add("approvals", approvals);
            MineAstrProtocol.sendQueryResult(socket, messageId, "command", data);
            return;
        }

        String approvalId = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "approval_id", ""), 64);
        if (approvalId.isEmpty()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "approval_id 不能为空。");
            return;
        }

        PendingCommandApproval pending = pendingCommandApprovals.get(approvalId);
        if (pending == null) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "待审批命令不存在或已经过期。");
            return;
        }

        if (!pendingCommandApprovals.remove(approvalId, pending)) {
            MineAstrProtocol.sendQueryError(socket, messageId, "command", "该命令审批已被其他管理员处理。");
            return;
        }

        if ("reject".equals(action)) {
            MineAstr.LOGGER.warn(
                    "MineAstr 管理员已拒绝命令：approval_id={} approver={} requester={} command={}",
                    approvalId, approver.auditName(), pending.requester().auditName(), pending.command());
            JsonObject data = new JsonObject();
            data.addProperty("status", "rejected");
            data.addProperty("approval_id", approvalId);
            data.addProperty("command", pending.command());
            data.addProperty("requester", pending.requester().auditName());
            data.addProperty("approved_by", approver.auditName());
            MineAstrProtocol.sendQueryResult(socket, messageId, "command", data);
            return;
        }

        execute(socket, messageId, server, pending.command(), pending.requester(), approver.auditName(), approvalId);
    }

    private static void execute(
            WebSocket socket,
            String messageId,
            MinecraftServer server,
            String command,
            Requester requester,
            String approvedBy,
            String approvalId) {
        CommandCapture capture = new CommandCapture();
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(capture)
                .withPermission(LevelBasedPermissionSet.forLevel(
                        PermissionLevel.byId(MineAstrConfig.COMMAND_PERMISSION_LEVEL.getAsInt())))
                .withCallback(capture::onResult);
        MineAstr.LOGGER.warn(
                "MineAstr 正在执行受控命令：requester={} approved_by={} approval_id={} command={}",
                requester.auditName(), approvedBy.isEmpty() ? "public_allowlist" : approvedBy,
                approvalId, command);
        server.getCommands().performPrefixedCommand(source, command);

        JsonObject data = new JsonObject();
        data.addProperty("status", "executed");
        data.addProperty("command", command);
        data.addProperty("requester", requester.auditName());
        data.addProperty("approved_by", approvedBy);
        data.addProperty("approval_id", approvalId);
        data.addProperty("policy", approvedBy.isEmpty() ? "public_allowlist" : "administrator_approval");
        data.addProperty("success", capture.success);
        data.addProperty("result", capture.result);
        JsonArray output = new JsonArray();
        capture.messages.forEach(output::add);
        data.add("output", output);
        MineAstrProtocol.sendQueryResult(socket, messageId, "command", data);
    }

    private static void sendPendingResult(WebSocket socket, String messageId, String approvalId, PendingCommandApproval pending) {
        JsonObject data = new JsonObject();
        data.addProperty("status", "approval_required");
        data.addProperty("approval_id", approvalId);
        data.addProperty("command", pending.command());
        data.addProperty("requester", pending.requester().auditName());
        data.addProperty("created_at_ms", pending.createdAtMs());
        data.addProperty("expires_at_ms", pending.expiresAtMs());
        data.addProperty(
                "expires_in_seconds",
                Math.max(0L, (pending.expiresAtMs() - System.currentTimeMillis() + 999L) / 1000L));
        MineAstrProtocol.sendQueryResult(socket, messageId, "command", data);
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        pendingCommandApprovals.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    private static boolean isTrustedRequester(Requester requester) {
        if (requester.identities().isEmpty()) {
            return false;
        }
        for (String configured : MineAstrConfig.TRUSTED_COMMAND_USERS.get()) {
            String trusted = configured.strip().toLowerCase(Locale.ROOT);
            if (!trusted.isEmpty() && requester.identities().contains(trusted)) {
                return true;
            }
        }
        for (String identity : requester.identities()) {
            if (syncedTrustedCommandUsers.contains(identity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeSyncedTrustedIdentity(String identity) {
        return !identity.isBlank()
                && identity.length() <= 128
                && identity.matches("[a-z0-9_.:@-]+");
    }

    private static boolean isAllowedCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        for (String configured : MineAstrConfig.ALLOWED_COMMAND_RULES.get()) {
            String rule = normalizeCommand(configured).toLowerCase(Locale.ROOT);
            if (rule.equals("*")) {
                return true;
            }
            if (rule.endsWith(" *")) {
                String prefix = rule.substring(0, rule.length() - 2).strip();
                if (!prefix.isEmpty() && (normalized.equals(prefix) || normalized.startsWith(prefix + " "))) {
                    return true;
                }
            } else if (normalized.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCommand(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }
        String command = rawCommand.strip();
        while (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        for (int index = 0; index < command.length(); index++) {
            if (Character.isISOControl(command.charAt(index))) {
                return "";
            }
        }
        return command;
    }

    private static int status(CommandSourceStack source, MineAstrBridge bridge) {
        String stateKey;
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            stateKey = "commands.mineastr.status.disabled";
        } else if (!bridge.isStarted()) {
            stateKey = "commands.mineastr.status.inactive";
        } else if (bridge.isConnected()) {
            stateKey = "commands.mineastr.status.connected";
        } else if (bridge.isConnecting()) {
            stateKey = "commands.mineastr.status.connecting";
        } else {
            stateKey = "commands.mineastr.status.disconnected";
        }
        source.sendSuccess(() -> Component.translatable("commands.mineastr.status", Component.translatable(stateKey)), false);
        return 1;
    }

    private static int reconnect(CommandSourceStack source, MineAstrBridge bridge) {
        if (bridge.reconnect()) {
            source.sendSuccess(() -> Component.translatable("commands.mineastr.reconnect"), false);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.mineastr.reconnect.unavailable"));
        return 0;
    }

    public record Requester(List<String> identities, String auditName) {
        public static Requester from(JsonObject payload) {
            List<String> identities = new ArrayList<>();
            String requesterId = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "requester_id", ""), 128);
            addIdentity(identities, MineAstrProtocol.getString(payload, "requester_uuid", ""));
            addIdentity(identities, requesterId);
            addIdentity(identities, MineAstrProtocol.getString(payload, "requester_name", ""));
            String platform = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "requester_platform", "unknown"), 64);
            if (!requesterId.isEmpty() && !platform.isEmpty() && !"unknown".equalsIgnoreCase(platform)) {
                addIdentity(identities, platform + ":" + requesterId);
            }
            String best = identities.isEmpty() ? "unknown@" + platform : identities.getFirst() + "@" + platform;
            return new Requester(List.copyOf(identities), best);
        }

        private static void addIdentity(List<String> identities, String value) {
            String normalized = MineAstrProtocol.trimFlatContent(value, 128).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !identities.contains(normalized)) {
                identities.add(normalized);
            }
        }
    }

    public record PendingCommandApproval(
            String command, Requester requester, long createdAtMs, long expiresAtMs) {
    }

    private static final class CommandCapture implements CommandSource {
        private static final int MAX_MESSAGES = 20;
        private final List<String> messages = new ArrayList<>();
        private boolean success;
        private int result;

        @Override
        public void sendSystemMessage(Component component) {
            if (messages.size() < MAX_MESSAGES) {
                messages.add(MineAstrProtocol.trimFlatContent(component.getString(), 512));
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private void onResult(boolean success, int result) {
            this.success = success;
            this.result = result;
        }
    }
}
