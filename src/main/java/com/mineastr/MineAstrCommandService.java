package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import static com.mineastr.MineAstrProtocolValues.getLong;
import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.startsWith;
import static com.mineastr.MineAstrProtocolValues.trimFlatContent;

/**
 * 受控命令、管理员可信名单和审批记录；仅在服务器线程修改。
 * Controlled commands, administrator trust and approvals; mutations stay on the server thread.
 */
final class MineAstrCommandService {
    private final MineAstrBridgeContext context;

    private final ConcurrentMap<String, PendingCommandApproval> pendingCommandApprovals = new ConcurrentHashMap<>();

    private final Set<String> syncedTrustedCommandUsers = ConcurrentHashMap.newKeySet();

    private final AtomicLong syncedTrustedCommandUsersRevision = new AtomicLong(-1L);

    MineAstrCommandService(MineAstrBridgeContext context) {
        this.context = context;
    }

    void handleCommandQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_COMMAND_TOOL.getAsBoolean()) {
            context.sendQueryError(socket, messageId, "command", "服务端命令工具默认关闭；请由服务器管理员在配置中显式启用。");
            return;
        }
        String bridgeToken = MineAstrConfig.TOKEN.get().strip();
        if (bridgeToken.isEmpty() || "change-me".equalsIgnoreCase(bridgeToken)) {
            context.sendQueryError(socket, messageId, "command", "命令工具要求先把默认 token 改为安全随机字符串。");
            return;
        }

        String action = trimFlatContent(getString(payload, "action", "request"), 16).toLowerCase(Locale.ROOT);
        Requester requester = Requester.from(payload);
        cleanupExpiredCommandApprovals();
        if ("approve".equals(action) || "reject".equals(action) || "list".equals(action)) {
            handleCommandApprovalAction(socket, messageId, action, payload, requester, currentServer);
            return;
        }
        if (!"request".equals(action)) {
            context.sendQueryError(socket, messageId, "command", "不支持的命令操作：" + action);
            return;
        }

        String command = normalizeCommand(getString(payload, "command", ""));
        if (command.isEmpty()) {
            context.sendQueryError(socket, messageId, "command", "命令不能为空。");
            return;
        }
        if (command.length() > MineAstrConfig.COMMAND_MAX_LENGTH.getAsInt()) {
            context.sendQueryError(socket, messageId, "command", "命令长度超过服务端限制。");
            return;
        }

        if (isAllowedCommand(command)) {
            executeCommand(socket, messageId, currentServer, command, requester, "", "");
            return;
        }

        for (var entry : pendingCommandApprovals.entrySet()) {
            PendingCommandApproval pending = entry.getValue();
            if (pending.command().equals(command)
                    && pending.requester().auditName().equals(requester.auditName())) {
                sendPendingCommandResult(socket, messageId, entry.getKey(), pending);
                return;
            }
        }
        if (pendingCommandApprovals.size() >= MineAstrConfig.COMMAND_MAX_PENDING_APPROVALS.getAsInt()) {
            context.sendQueryError(socket, messageId, "command", "待审批命令数量已达服务端上限，请先由管理员处理现有申请。");
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
        sendPendingCommandResult(socket, messageId, approvalId, pending);
    }

    void handleCommandApprovalAction(
            WebSocket socket,
            String messageId,
            String action,
            JsonObject payload,
            Requester approver,
            MinecraftServer currentServer) {
        if (!isTrustedRequester(approver)) {
            MineAstr.LOGGER.warn("MineAstr 已拒绝非管理员命令审批：requester={} action={}", approver.auditName(), action);
            context.sendQueryError(socket, messageId, "command", "当前审批者不在 trustedCommandUsers 管理员名单中。");
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
            context.sendQueryResult(socket, messageId, "command", data);
            return;
        }

        String approvalId = trimFlatContent(getString(payload, "approval_id", ""), 64);
        if (approvalId.isEmpty()) {
            context.sendQueryError(socket, messageId, "command", "approval_id 不能为空。");
            return;
        }
        PendingCommandApproval pending = pendingCommandApprovals.get(approvalId);
        if (pending == null) {
            context.sendQueryError(socket, messageId, "command", "待审批命令不存在或已经过期。");
            return;
        }
        if (!pendingCommandApprovals.remove(approvalId, pending)) {
            context.sendQueryError(socket, messageId, "command", "该命令审批已被其他管理员处理。");
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
            context.sendQueryResult(socket, messageId, "command", data);
            return;
        }
        executeCommand(
                socket,
                messageId,
                currentServer,
                pending.command(),
                pending.requester(),
                approver.auditName(),
                approvalId);
    }

    void sendPendingCommandResult(
            WebSocket socket, String messageId, String approvalId, PendingCommandApproval pending) {
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
        context.sendQueryResult(socket, messageId, "command", data);
    }

    void cleanupExpiredCommandApprovals() {
        long now = System.currentTimeMillis();
        pendingCommandApprovals.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    void executeCommand(
            WebSocket socket,
            String messageId,
            MinecraftServer currentServer,
            String command,
            Requester requester,
            String approvedBy,
            String approvalId) {
        CommandCapture capture = new CommandCapture();
        CommandSourceStack source = currentServer.createCommandSourceStack()
                .withSource(capture)
                .withPermission(MineAstrConfig.COMMAND_PERMISSION_LEVEL.getAsInt())
                .withCallback(capture::onResult);
        MineAstr.LOGGER.warn(
                "MineAstr 正在执行受控命令：requester={} approved_by={} approval_id={} command={}",
                requester.auditName(), approvedBy.isEmpty() ? "public_allowlist" : approvedBy,
                approvalId, command);
        currentServer.getCommands().performPrefixedCommand(source, command);

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
        context.sendQueryResult(socket, messageId, "command", data);
    }

    void handleTrustedUsersQuery(
            WebSocket socket, String messageId, JsonObject payload) {
        if (!MineAstrConfig.SYNC_TRUSTED_COMMAND_USERS.getAsBoolean()) {
            context.sendQueryError(
                    socket,
                    messageId,
                    "trusted_users",
                    "trusted_user_sync_disabled");
            return;
        }
        String action = trimFlatContent(
                getString(payload, "action", ""), 16).toLowerCase(Locale.ROOT);
        if (!"replace".equals(action)
                || !payload.has("users")
                || !payload.get("users").isJsonArray()) {
            context.sendQueryError(
                    socket, messageId, "trusted_users", "invalid_trusted_users_request");
            return;
        }
        JsonArray users = payload.getAsJsonArray("users");
        if (users.size() > 256) {
            context.sendQueryError(
                    socket, messageId, "trusted_users", "too_many_trusted_users");
            return;
        }
        Set<String> replacement = new java.util.HashSet<>();
        for (JsonElement element : users) {
            if (!element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                context.sendQueryError(
                        socket, messageId, "trusted_users", "invalid_trusted_user");
                return;
            }
            String identity = element.getAsString().strip().toLowerCase(Locale.ROOT);
            if (!isSafeSyncedTrustedIdentity(identity)) {
                context.sendQueryError(
                        socket, messageId, "trusted_users", "invalid_trusted_user");
                return;
            }
            replacement.add(identity);
        }
        long revision = Math.max(0L, getLong(payload, "revision", 0L));
        long currentRevision = syncedTrustedCommandUsersRevision.get();
        if (revision < currentRevision) {
            context.sendQueryError(socket, messageId, "trusted_users", "stale_trusted_users_revision");
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
        data.addProperty(
                "static_count", MineAstrConfig.TRUSTED_COMMAND_USERS.get().size());
        context.sendQueryResult(socket, messageId, "trusted_users", data);
    }

    boolean isTrustedRequester(Requester requester) {
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

    static boolean isSafeSyncedTrustedIdentity(String identity) {
        return !identity.isBlank()
                && identity.length() <= 128
                && identity.matches("[a-z0-9_.:@-]+");
    }

    static boolean isAllowedCommand(String command) {
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

    static String normalizeCommand(String rawCommand) {
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

    private record Requester(List<String> identities, String auditName) {
        private static Requester from(JsonObject payload) {
            List<String> identities = new ArrayList<>();
            String requesterId = trimFlatContent(getString(payload, "requester_id", ""), 128);
            addIdentity(identities, getString(payload, "requester_uuid", ""));
            addIdentity(identities, requesterId);
            addIdentity(identities, getString(payload, "requester_name", ""));
            String platform = trimFlatContent(getString(payload, "requester_platform", "unknown"), 64);
            if (!requesterId.isEmpty() && !platform.isEmpty() && !"unknown".equalsIgnoreCase(platform)) {
                addIdentity(identities, platform + ":" + requesterId);
            }
            String best = identities.isEmpty() ? "unknown@" + platform : identities.getFirst() + "@" + platform;
            return new Requester(List.copyOf(identities), best);
        }

        private static void addIdentity(List<String> identities, String value) {
            String normalized = trimFlatContent(value, 128).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !identities.contains(normalized)) {
                identities.add(normalized);
            }
        }
    }

    private record PendingCommandApproval(
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
                messages.add(trimFlatContent(component.getString(), 512));
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

    void clear() {
        pendingCommandApprovals.clear();
        syncedTrustedCommandUsers.clear();
        syncedTrustedCommandUsersRevision.set(-1L);
    }

}
