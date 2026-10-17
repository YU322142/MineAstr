package com.mineastr;

import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

/**
 * 一次同步只建立一次名称索引；未变化的白名单绝不写盘。
 * Index names once per reconciliation; unchanged grants never write to disk.
 * All calls belong on the server thread. Vanilla add/remove still persist changes.
 */
final class MineAstrWhitelist {
    record Result(boolean ok, boolean changed, boolean verified, String error) {}

    private static final Method ENTRY_USER = entryUserMethod();
    private static final Field ENTRIES = entriesField();
    // 保存失败后的内存状态仍可能已变化；下次无变化同步也必须重试落盘。
    // A failed save can leave mutated memory; retry persistence even on a later no-op.
    private static final Set<UserWhiteList> UNSAVED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final UserWhiteList whitelist;
    private final Map<String, List<GameProfile>> byName = new HashMap<>();

    MineAstrWhitelist(UserWhiteList whitelist) {
        this.whitelist = whitelist;
        for (UserWhiteListEntry entry : whitelist.getEntries()) {
            GameProfile profile = profile(entry);
            if (profile != null && profile.getName() != null) {
                byName.computeIfAbsent(normalize(profile.getName()), ignored -> new ArrayList<>()).add(profile);
            }
        }
    }

    GameProfile find(String name) {
        List<GameProfile> matches = byName.get(normalize(name));
        return matches == null || matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * 对完整快照只保存一次；落盘成功前不修改线上授权。
     * Persist a complete reconciliation once, before publishing its live grants.
     * Re-read the live map at commit so manual whitelist edits during queueing survive.
     */
    Result reconcile(List<GameProfile> grants, List<GameProfile> revocations) {
        Map<String, UserWhiteListEntry> live = liveEntries();
        Map<String, UserWhiteListEntry> target = new HashMap<>(live);
        Map<String, List<GameProfile>> names = new HashMap<>();
        for (UserWhiteListEntry entry : live.values()) {
            GameProfile profile = profile(entry);
            if (profile != null && profile.getName() != null) {
                names.computeIfAbsent(normalize(profile.getName()), ignored -> new ArrayList<>()).add(profile);
            }
        }
        for (GameProfile revoked : revocations) target.remove(revoked.getId().toString());
        for (GameProfile granted : grants) {
            for (GameProfile conflict : names.getOrDefault(normalize(granted.getName()), List.of())) {
                if (!conflict.getId().equals(granted.getId())) target.remove(conflict.getId().toString());
            }
            String key = granted.getId().toString();
            UserWhiteListEntry existing = target.get(key);
            if (existing == null || !granted.getName().equals(profile(existing).getName())) {
                target.put(key, new UserWhiteListEntry(granted));
            }
        }
        boolean changed = !target.equals(live);
        if (!changed && !UNSAVED.contains(whitelist)) return new Result(true, false, true, "");

        Path file = whitelist.getFile().toPath().toAbsolutePath();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.getParent(), "mineastr-whitelist-", ".tmp");
            var staged = new StagedWhitelist(temporary);
            for (UserWhiteListEntry entry : target.values()) staged.add(entry);
            staged.flush();
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            live.clear();
            live.putAll(target);
            UNSAVED.remove(whitelist);
            return new Result(true, changed, true, "");
        } catch (IOException error) {
            return new Result(false, false, false, "whitelist_save_failed");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    MineAstr.LOGGER.debug("MineAstr whitelist temporary cleanup failed", error);
                }
            }
        }
    }

    /** 原版序列化格式，临时表构造时不自动保存；defer vanilla autosaves until flush. */
    private static final class StagedWhitelist extends UserWhiteList {
        StagedWhitelist(Path file) { super(file.toFile()); }
        @Override public void save() {}
        void flush() throws IOException { super.save(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, UserWhiteListEntry> liveEntries() {
        try {
            return (Map<String, UserWhiteListEntry>) ENTRIES.get(whitelist);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot access whitelist entries", error);
        }
    }

    private static Field entriesField() {
        try {
            // 1.21.1 无批量白名单 API；集中封装此版本边界，不散布到桥接逻辑。
            // 1.21.1 has no bulk whitelist API; isolate this version-specific access here.
            Field field = StoredUserList.class.getDeclaredField("map");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot access whitelist storage", error);
        }
    }

    Result update(GameProfile profile, boolean present, boolean removeConflicts) {
        boolean changed = false;
        String key = normalize(profile.getName());
        List<GameProfile> matches = byName.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (present && removeConflicts) {
            for (GameProfile other : List.copyOf(matches)) {
                if (!other.getId().equals(profile.getId())) {
                    UNSAVED.add(whitelist);
                    whitelist.remove(other);
                    matches.remove(other);
                    changed = true;
                }
            }
        }
        boolean wasPresent = whitelist.isWhiteListed(profile);
        if (present && !wasPresent) {
            UNSAVED.add(whitelist);
            whitelist.add(new UserWhiteListEntry(profile));
            matches.add(profile);
            changed = true;
        } else if (!present && wasPresent) {
            UNSAVED.add(whitelist);
            whitelist.remove(profile);
            matches.removeIf(other -> other.getId().equals(profile.getId()));
            changed = true;
        }
        if (whitelist.isWhiteListed(profile) != present) {
            return new Result(false, changed, false, "whitelist_verification_failed");
        }
        if (changed || UNSAVED.contains(whitelist)) {
            try {
                // 原版吞掉 add/remove 的 I/O 异常；显式保存仅用于验证实际变化。
                // Vanilla swallows add/remove I/O errors; verify changed lists explicitly.
                whitelist.save();
                UNSAVED.remove(whitelist);
            } catch (IOException error) {
                return new Result(false, true, true, "whitelist_save_failed");
            }
        }
        return new Result(true, changed, true, "");
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Method entryUserMethod() {
        try {
            // 1.21.1 的 getUser 为包级可见；缓存反射句柄，避免逐条重复查找。
            // getUser is package-private in 1.21.1; resolve its handle once.
            Method method = StoredUserEntry.class.getDeclaredMethod("getUser");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot access whitelist identities", error);
        }
    }

    private static GameProfile profile(UserWhiteListEntry entry) {
        try {
            return (GameProfile) ENTRY_USER.invoke(entry);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read whitelist identity", error);
        }
    }
}
