package com.mineastr;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Comparator;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class MineAstrCommands {
    private MineAstrCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MineAstrBridge bridge) {
        dispatcher.register(Commands.literal("mineastr")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource(), bridge)))
                .then(Commands.literal("reconnect")
                        .executes(context -> reconnect(context.getSource(), bridge)))
                .then(Commands.literal("sign-translation")
                        .executes(context -> signStatus(context.getSource(), bridge))
                        .then(Commands.literal("status")
                                .executes(context -> signStatus(context.getSource(), bridge)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("locale", StringArgumentType.word())
                                        .then(Commands.argument("translation", StringArgumentType.greedyString())
                                                .executes(context -> signSet(
                                                        context.getSource(),
                                                        bridge,
                                                        StringArgumentType.getString(context, "locale"),
                                                        StringArgumentType.getString(context, "translation"))))))
                        .then(Commands.literal("clear")
                                .executes(context -> signClear(context.getSource(), bridge, null))
                                .then(Commands.argument("locale", StringArgumentType.word())
                                        .executes(context -> signClear(
                                                context.getSource(),
                                                bridge,
                                                StringArgumentType.getString(context, "locale")))))
                        .then(Commands.literal("clear-all")
                                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                                .executes(context -> signClearAll(context.getSource(), bridge)))));
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

    private static int signStatus(CommandSourceStack source, MineAstrBridge bridge)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MineAstrBridge.SignTranslationAdminView view = bridge.inspectTargetedSignTranslation(player);
        if (view == null) {
            source.sendFailure(Component.translatable("commands.mineastr.sign.target_required"));
            return 0;
        }
        SignTranslationStore.Entry entry = view.cacheEntry();
        String stateKey = cacheStateKey(entry);
        String languages = entry == null || entry.translations().isEmpty()
                ? "-"
                : entry.translations().keySet().stream()
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(", "));
        String manualLanguages = entry == null || entry.manualLanguages().isEmpty()
                ? "-"
                : entry.manualLanguages().stream()
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(", "));
        String sourceText = summarize(view.source());
        source.sendSuccess(() -> Component.translatable(
                "commands.mineastr.sign.status",
                view.pos().toShortString(),
                Component.translatable(view.front()
                        ? "commands.mineastr.sign.side.front"
                        : "commands.mineastr.sign.side.back"),
                Component.translatable(stateKey),
                languages,
                manualLanguages,
                bridge.signTranslationCacheSize(),
                sourceText), false);
        return 1;
    }

    private static int signSet(
            CommandSourceStack source,
            MineAstrBridge bridge,
            String language,
            String translation) throws CommandSyntaxException {
        String normalizedLanguage = SignTranslationStore.normalizeLanguage(language);
        String normalizedTranslation = translation == null ? "" : translation.strip();
        if (normalizedLanguage.isBlank()) {
            source.sendFailure(Component.translatable("commands.mineastr.sign.locale_invalid", language));
            return 0;
        }
        if (normalizedTranslation.isBlank()) {
            source.sendFailure(Component.translatable("commands.mineastr.sign.translation_empty"));
            return 0;
        }
        if (normalizedTranslation.length() > MineAstrPayloads.MAX_SIGN_TRANSLATION_TEXT_LENGTH) {
            source.sendFailure(Component.translatable(
                    "commands.mineastr.sign.translation_too_long",
                    MineAstrPayloads.MAX_SIGN_TRANSLATION_TEXT_LENGTH));
            return 0;
        }
        if (!bridge.setTargetedSignTranslation(
                source.getPlayerOrException(), normalizedLanguage, normalizedTranslation)) {
            source.sendFailure(Component.translatable("commands.mineastr.sign.target_required"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "commands.mineastr.sign.set",
                normalizedLanguage), true);
        return 1;
    }

    private static int signClear(
            CommandSourceStack source,
            MineAstrBridge bridge,
            String language) throws CommandSyntaxException {
        String normalizedLanguage = null;
        if (language != null) {
            normalizedLanguage = SignTranslationStore.normalizeLanguage(language);
            if (normalizedLanguage.isBlank()) {
                source.sendFailure(Component.translatable("commands.mineastr.sign.locale_invalid", language));
                return 0;
            }
        }
        int result = bridge.clearTargetedSignTranslation(
                source.getPlayerOrException(), normalizedLanguage);
        if (result < 0) {
            source.sendFailure(Component.translatable("commands.mineastr.sign.target_required"));
            return 0;
        }
        if (result == 0) {
            source.sendFailure(Component.translatable(
                    normalizedLanguage == null
                            ? "commands.mineastr.sign.cache_missing"
                            : "commands.mineastr.sign.locale_missing",
                    normalizedLanguage == null ? "" : normalizedLanguage));
            return 0;
        }
        String finalLanguage = normalizedLanguage;
        source.sendSuccess(() -> Component.translatable(
                finalLanguage == null
                        ? "commands.mineastr.sign.clear"
                        : "commands.mineastr.sign.clear_locale",
                finalLanguage == null ? "" : finalLanguage), true);
        return 1;
    }

    private static int signClearAll(CommandSourceStack source, MineAstrBridge bridge) {
        int removed = bridge.clearAllSignTranslations();
        source.sendSuccess(() -> Component.translatable(
                "commands.mineastr.sign.clear_all",
                removed), true);
        return removed == 0 ? 1 : removed;
    }

    static String cacheStateKey(SignTranslationStore.Entry entry) {
        if (entry == null) {
            return "commands.mineastr.sign.state.miss";
        }
        if (entry.skipTranslation()) {
            return "commands.mineastr.sign.state.bilingual";
        }
        if (entry.hasManualOverrides()) {
            return "commands.mineastr.sign.state.manual";
        }
        return "commands.mineastr.sign.state.automatic";
    }

    static String summarize(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }
}
