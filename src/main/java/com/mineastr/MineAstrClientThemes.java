package com.mineastr;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.util.FormattedCharSequence;

/** Main-thread palette. Gradients are precomputed once, never in the glyph loop. */
public final class MineAstrClientThemes {
    private static final Map<UUID, MineAstrPayloads.PlayerTheme> PROFILES = new HashMap<>();
    private static final Map<String, Theme> THEMES = new HashMap<>();
    private MineAstrClientThemes() {}
    private record Theme(MineAstrPayloads.PlayerTheme profile, int[] palette) {}
    public static void clear() { PROFILES.clear(); THEMES.clear(); }

    public static FormattedCharSequence animate(FormattedCharSequence line, double now) {
        var decoration = MineAstrChatLayout.decorations(line);
        if (decoration.sender() == null || decoration.textLength() == 0) return line;
        Theme theme = THEMES.get(decoration.sender());
        if (theme == null) return line;
        if (theme.profile().count() == 1 && theme.profile().color() == MineAstrThemeColor.DEFAULT) return line;
        int phase = (int) Math.floor((now % theme.profile().period()) / theme.profile().period() * 1024);
        return consumer -> {
            int[] ordinal = {0};
            return line.accept((index, style, codepoint) -> {
                if (MineAstrChatLayout.isThemeTextFont(style.getFont())) {
                    int color = theme.profile().count() == 1 ? theme.profile().color()
                            : theme.palette()[(phase + ordinal[0] * 256 / Math.max(1, decoration.textLength())) & 1023];
                    ordinal[0]++;
                    return consumer.accept(index, style.withColor(color), codepoint);
                }
                return consumer.accept(index, style, codepoint);
            });
        };
    }

    public static void receive(MineAstrPayloads.ThemePalette payload) {
        if (payload.reset()) clear();
        var client = Minecraft.getInstance();
        for (var profile : payload.profiles()) {
            if (!profile.valid()) continue;
            var previous = PROFILES.put(profile.id(), profile);
            if (profile.equals(previous)) continue;
            if (previous != null) THEMES.remove(previous.name().toLowerCase(Locale.ROOT));
            int[] palette = new int[profile.count() == 1 ? 0 : 1024];
            for (int index = 0; index < palette.length; index++)
                palette[index] = MineAstrThemeColor.gradient(profile.color(), profile.second(), profile.third(), profile.count(), index / 1024.0);
            THEMES.put(profile.name().toLowerCase(Locale.ROOT), new Theme(profile, palette));
            if (client.player != null && client.player.getUUID().equals(profile.id()) && MineAstrClientConfig.isLoaded()) {
                MineAstrClientConfig.PLAYER_THEME_COLOR.set(profile.color());
                MineAstrClientConfig.PLAYER_THEME_SECOND.set(profile.second());
                MineAstrClientConfig.PLAYER_THEME_THIRD.set(profile.third());
                MineAstrClientConfig.PLAYER_THEME_STOPS.set(profile.count());
                MineAstrClientConfig.PLAYER_THEME_PERIOD.set(profile.period());
                MineAstrClientConfig.SPEC.save();
            }
        }
        // Colors do not alter shaping or line width; preserve ongoing queue animations.
    }
}
