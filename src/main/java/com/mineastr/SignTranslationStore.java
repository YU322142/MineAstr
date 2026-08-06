package com.mineastr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Persistent sign translation cache stored below the Minecraft world root.
 *
 * <p>The original sign block entity is never modified. Each cache entry is
 * invalidated by the source fingerprint, so editing a sign automatically
 * causes a fresh translation request. Automatic entries are also scoped to a
 * translation policy version, while explicit administrator overrides survive
 * policy changes.</p>
 */
public final class SignTranslationStore {
    private static final int FORMAT_VERSION = 2;
    static final int CURRENT_POLICY_VERSION = 2;
    private static final String FILE_NAME = "mineastr_sign_translations.dat";
    private final Map<String, Entry> entries = new HashMap<>();
    private Path file;

    public synchronized void load(Path worldRoot) {
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

                Map<String, String> translations = readTranslations(tag.getCompoundOrEmpty("translations"));
                Set<String> manualLanguages = readManualLanguages(tag.getCompoundOrEmpty("manual_languages"));
                manualLanguages.retainAll(translations.keySet());
                int policyVersion = tag.getIntOr("policy_version", 1);
                boolean skipTranslation = tag.getBooleanOr("skip_translation", false);

                Entry entry = new Entry(
                        fingerprint,
                        source,
                        translations,
                        tag.getBooleanOr("show_original", false),
                        manualLanguages,
                        skipTranslation,
                        policyVersion);
                if (policyVersion != CURRENT_POLICY_VERSION && !manualLanguages.isEmpty()) {
                    entry = retainManualTranslations(entry);
                }
                entries.put(id, entry);
            }
        } catch (Exception exc) {
            entries.clear();
            MineAstr.LOGGER.warn("MineAstr could not read the sign translation cache: {}", file, exc);
        }
    }

    /**
     * Returns a cache entry only when its source and automatic-translation
     * policy are current. Manual entries remain valid across policy changes.
     */
    public synchronized Optional<Entry> find(String id, String fingerprint) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.fingerprint().equals(fingerprint)) {
            return Optional.empty();
        }
        if (entry.policyVersion() != CURRENT_POLICY_VERSION && entry.manualLanguages().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /** Removes the current source revision of one sign side. */
    public synchronized void remove(String id, String fingerprint) {
        Entry entry = entries.get(id);
        if (entry != null && entry.fingerprint().equals(fingerprint)) {
            entries.remove(id);
            save();
        }
    }

    /** Removes one sign side regardless of its source revision. */
    public synchronized boolean remove(String id) {
        if (id == null || entries.remove(id) == null) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Backwards-compatible alias for an automatic cache update.
     */
    public void put(
            String id,
            String fingerprint,
            String source,
            Map<String, String> translations,
            boolean showOriginal) {
        putAutomatic(id, fingerprint, source, translations, showOriginal);
    }

    /**
     * Replaces automatic translations while retaining manual language values
     * for the same sign source.
     */
    public synchronized void putAutomatic(
            String id,
            String fingerprint,
            String source,
            Map<String, String> translations,
            boolean showOriginal) {
        if (!validIdentity(id, fingerprint)) {
            return;
        }

        Map<String, String> merged = sanitizeTranslations(translations);
        Set<String> manualLanguages = new HashSet<>();
        Entry existing = entries.get(id);
        if (sameSource(existing, fingerprint)) {
            for (String language : existing.manualLanguages()) {
                String manualText = existing.translations().get(language);
                if (manualText != null && !manualText.isBlank()) {
                    merged.put(language, manualText);
                    manualLanguages.add(language);
                }
            }
        }

        entries.put(id, new Entry(
                fingerprint,
                normalizedSource(source),
                merged,
                showOriginal,
                manualLanguages,
                false,
                CURRENT_POLICY_VERSION));
        save();
    }

    /**
     * Stores the semantic result that the sign already contains equivalent
     * Chinese and English text and therefore needs no overlay translation.
     */
    public synchronized void putSkipped(String id, String fingerprint, String source) {
        if (!validIdentity(id, fingerprint)) {
            return;
        }

        Entry existing = entries.get(id);
        if (sameSource(existing, fingerprint) && !existing.manualLanguages().isEmpty()) {
            Entry manualOnly = retainManualTranslations(existing);
            entries.put(id, new Entry(
                    fingerprint,
                    normalizedSource(source),
                    manualOnly.translations(),
                    manualOnly.showOriginal(),
                    manualOnly.manualLanguages(),
                    false,
                    CURRENT_POLICY_VERSION));
        } else {
            entries.put(id, new Entry(
                    fingerprint,
                    normalizedSource(source),
                    Map.of(),
                    false,
                    Set.of(),
                    true,
                    CURRENT_POLICY_VERSION));
        }
        save();
    }

    /** Adds or replaces an administrator-controlled translation language. */
    public synchronized boolean putManual(
            String id,
            String fingerprint,
            String source,
            String language,
            String translation,
            boolean showOriginal) {
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedTranslation = translation == null ? "" : translation.trim();
        if (!validIdentity(id, fingerprint)
                || normalizedLanguage.isEmpty()
                || normalizedTranslation.isEmpty()) {
            return false;
        }

        Map<String, String> merged = new LinkedHashMap<>();
        Set<String> manualLanguages = new HashSet<>();
        Entry existing = entries.get(id);
        if (sameSource(existing, fingerprint)) {
            Entry usableExisting = existing.policyVersion() == CURRENT_POLICY_VERSION
                    ? existing
                    : retainManualTranslations(existing);
            merged.putAll(usableExisting.translations());
            manualLanguages.addAll(usableExisting.manualLanguages());
        }
        merged.put(normalizedLanguage, normalizedTranslation);
        manualLanguages.add(normalizedLanguage);

        entries.put(id, new Entry(
                fingerprint,
                normalizedSource(source),
                merged,
                showOriginal,
                manualLanguages,
                false,
                CURRENT_POLICY_VERSION));
        save();
        return true;
    }

    /** Removes one cached language from the current source revision. */
    public synchronized boolean removeLanguage(String id, String fingerprint, String language) {
        Entry existing = entries.get(id);
        String normalizedLanguage = normalizeLanguage(language);
        if (!sameSource(existing, fingerprint)
                || normalizedLanguage.isEmpty()
                || !existing.translations().containsKey(normalizedLanguage)) {
            return false;
        }

        Map<String, String> translations = new LinkedHashMap<>(existing.translations());
        Set<String> manualLanguages = new HashSet<>(existing.manualLanguages());
        translations.remove(normalizedLanguage);
        manualLanguages.remove(normalizedLanguage);
        if (translations.isEmpty() && !existing.skipTranslation()) {
            entries.remove(id);
        } else {
            entries.put(id, new Entry(
                    existing.fingerprint(),
                    existing.source(),
                    translations,
                    existing.showOriginal(),
                    manualLanguages,
                    existing.skipTranslation(),
                    existing.policyVersion()));
        }
        save();
        return true;
    }

    /** Clears all sign translation entries and returns the number removed. */
    public synchronized int clearAll() {
        int removed = entries.size();
        if (removed > 0) {
            entries.clear();
            save();
        }
        return removed;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void save() {
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
                tag.putBoolean("skip_translation", entry.skipTranslation());
                tag.putInt("policy_version", entry.policyVersion());

                CompoundTag translations = new CompoundTag();
                entry.translations().forEach(translations::putString);
                tag.put("translations", translations);

                CompoundTag manualLanguages = new CompoundTag();
                entry.manualLanguages().forEach(language -> manualLanguages.putBoolean(language, true));
                tag.put("manual_languages", manualLanguages);
                list.add(tag);
            }
            root.put("entries", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException exc) {
            MineAstr.LOGGER.warn("MineAstr could not save the sign translation cache: {}", file, exc);
        }
    }

    private static Entry retainManualTranslations(Entry entry) {
        Map<String, String> manualTranslations = new LinkedHashMap<>();
        for (String language : entry.manualLanguages()) {
            String translation = entry.translations().get(language);
            if (translation != null && !translation.isBlank()) {
                manualTranslations.put(language, translation);
            }
        }
        return new Entry(
                entry.fingerprint(),
                entry.source(),
                manualTranslations,
                entry.showOriginal(),
                manualTranslations.keySet(),
                false,
                CURRENT_POLICY_VERSION);
    }

    private static Map<String, String> readTranslations(CompoundTag translatedTag) {
        Map<String, String> translations = new LinkedHashMap<>();
        for (String language : translatedTag.keySet()) {
            String normalizedLanguage = normalizeLanguage(language);
            String text = translatedTag.getStringOr(language, "").trim();
            if (!normalizedLanguage.isEmpty() && !text.isEmpty()) {
                translations.put(normalizedLanguage, text);
            }
        }
        return translations;
    }

    private static Set<String> readManualLanguages(CompoundTag manualTag) {
        Set<String> languages = new HashSet<>();
        for (String language : manualTag.keySet()) {
            String normalizedLanguage = normalizeLanguage(language);
            if (!normalizedLanguage.isEmpty() && manualTag.getBooleanOr(language, false)) {
                languages.add(normalizedLanguage);
            }
        }
        return languages;
    }

    private static Map<String, String> sanitizeTranslations(Map<String, String> translations) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (translations == null) {
            return sanitized;
        }
        translations.forEach((language, text) -> {
            String normalizedLanguage = normalizeLanguage(language);
            String normalizedText = text == null ? "" : text.trim();
            if (!normalizedLanguage.isEmpty() && !normalizedText.isEmpty()) {
                sanitized.put(normalizedLanguage, normalizedText);
            }
        });
        return sanitized;
    }

    private static boolean validIdentity(String id, String fingerprint) {
        return id != null && !id.isBlank() && fingerprint != null && !fingerprint.isBlank();
    }

    private static boolean sameSource(Entry entry, String fingerprint) {
        return entry != null && fingerprint != null && entry.fingerprint().equals(fingerprint);
    }

    private static String normalizedSource(String source) {
        return source == null ? "" : source;
    }

    static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.matches("[a-z0-9_]{2,16}") ? normalized : "";
    }

    public record Entry(
            String fingerprint,
            String source,
            Map<String, String> translations,
            boolean showOriginal,
            Set<String> manualLanguages,
            boolean skipTranslation,
            int policyVersion) {
        public Entry {
            fingerprint = fingerprint == null ? "" : fingerprint;
            source = source == null ? "" : source;
            translations = Map.copyOf(translations == null ? Map.of() : translations);
            Set<String> validManualLanguages = new HashSet<>(
                    manualLanguages == null ? Set.of() : manualLanguages);
            validManualLanguages.retainAll(translations.keySet());
            manualLanguages = Set.copyOf(validManualLanguages);
        }

        public boolean hasManualOverrides() {
            return !manualLanguages.isEmpty();
        }
    }
}
