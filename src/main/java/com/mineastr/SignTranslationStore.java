package com.mineastr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Persistent sign translation cache stored below the Minecraft world root.
 *
 * <p>The original sign block entity is never modified. Each cache entry is
 * invalidated by the source fingerprint, so editing a sign automatically
 * causes a fresh translation request.</p>
 */
public final class SignTranslationStore {
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "mineastr_sign_translations.dat";
    private final Map<String, Entry> entries = new HashMap<>();
    private Path file;

    public void load(Path worldRoot) {
        file = worldRoot.resolve("data").resolve(FILE_NAME);
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("entries");
            for (int index = 0; index < list.size(); index++) {
                CompoundTag tag = list.getCompoundOrEmpty(index);
                String id = tag.getStringOr("id", "");
                String fingerprint = tag.getStringOr("fingerprint", "");
                String source = tag.getStringOr("source", "");
                if (id.isBlank() || fingerprint.isBlank()) {
                    continue;
                }
                Map<String, String> translations = new HashMap<>();
                CompoundTag translatedTag = tag.getCompoundOrEmpty("translations");
                for (String language : translatedTag.keySet()) {
                    String text = translatedTag.getStringOr(language, "");
                    if (!language.isBlank() && !text.isBlank()) {
                        translations.put(language, text);
                    }
                }
                entries.put(id, new Entry(fingerprint, source, translations,
                        tag.getBooleanOr("show_original", false)));
            }
        } catch (Exception exc) {
            MineAstr.LOGGER.warn("MineAstr 无法读取告示牌翻译缓存：{}", file, exc);
        }
    }

    public Optional<Entry> find(String id, String fingerprint) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.fingerprint().equals(fingerprint)) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void remove(String id, String fingerprint) {
        Entry entry = entries.get(id);
        if (entry != null && entry.fingerprint().equals(fingerprint)) {
            entries.remove(id);
            save();
        }
    }

    public void put(
            String id,
            String fingerprint,
            String source,
            Map<String, String> translations,
            boolean showOriginal) {
        if (id == null || id.isBlank() || fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        entries.put(id, new Entry(
                fingerprint,
                source == null ? "" : source,
                Map.copyOf(translations == null ? Map.of() : translations),
                showOriginal));
        save();
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.putInt("version", FORMAT_VERSION);
            ListTag list = new ListTag();
            for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
                Entry entry = mapEntry.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putString("id", mapEntry.getKey());
                tag.putString("fingerprint", entry.fingerprint());
                tag.putString("source", entry.source());
                tag.putBoolean("show_original", entry.showOriginal());
                CompoundTag translations = new CompoundTag();
                entry.translations().forEach(translations::putString);
                tag.put("translations", translations);
                list.add(tag);
            }
            root.put("entries", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException exc) {
            MineAstr.LOGGER.warn("MineAstr 无法保存告示牌翻译缓存：{}", file, exc);
        }
    }

    public record Entry(
            String fingerprint,
            String source,
            Map<String, String> translations,
            boolean showOriginal) {
    }
}
