package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignTranslationStoreTest {
    private static final String ID = "minecraft:overworld/1,2,3/front";
    private static final String FINGERPRINT = "source-fingerprint";

    @TempDir
    Path worldRoot;

    @Test
    void automaticTranslationsPersistAndReload() {
        SignTranslationStore store = loadedStore();
        store.putAutomatic(ID, FINGERPRINT, "Welcome", Map.of("zh-cn", "欢迎"), true);

        SignTranslationStore.Entry written = store.find(ID, FINGERPRINT).orElseThrow();
        assertEquals(Map.of("zh_cn", "欢迎"), written.translations());
        assertTrue(written.showOriginal());
        assertFalse(written.skipTranslation());
        assertFalse(written.hasManualOverrides());

        SignTranslationStore reloaded = loadedStore();
        SignTranslationStore.Entry persisted = reloaded.find(ID, FINGERPRINT).orElseThrow();
        assertEquals(written, persisted);
    }

    @Test
    void bilingualSkipStatePersistsWithoutSyntheticTranslation() {
        SignTranslationStore store = loadedStore();
        store.putSkipped(ID, FINGERPRINT, "仓库\nWarehouse");

        SignTranslationStore.Entry entry = store.find(ID, FINGERPRINT).orElseThrow();
        assertTrue(entry.skipTranslation());
        assertTrue(entry.translations().isEmpty());

        SignTranslationStore.Entry persisted = loadedStore().find(ID, FINGERPRINT).orElseThrow();
        assertTrue(persisted.skipTranslation());
        assertTrue(persisted.translations().isEmpty());
    }

    @Test
    void automaticUpdateCannotOverwriteManualLanguage() {
        SignTranslationStore store = loadedStore();
        store.putAutomatic(
                ID,
                FINGERPRINT,
                "Spawn Warehouse",
                Map.of("zh_cn", "出生仓库", "ja_jp", "スポーン倉庫"),
                false);
        assertTrue(store.putManual(
                ID,
                FINGERPRINT,
                "Spawn Warehouse",
                "zh-CN",
                "出生点仓库",
                true));

        store.putAutomatic(
                ID,
                FINGERPRINT,
                "Spawn Warehouse",
                Map.of("zh_cn", "错误的新自动结果", "en_us", "Spawn Warehouse"),
                false);

        SignTranslationStore.Entry entry = store.find(ID, FINGERPRINT).orElseThrow();
        assertEquals("出生点仓库", entry.translations().get("zh_cn"));
        assertEquals("Spawn Warehouse", entry.translations().get("en_us"));
        assertFalse(entry.translations().containsKey("ja_jp"));
        assertEquals(Set.of("zh_cn"), entry.manualLanguages());
        assertFalse(entry.showOriginal());

        SignTranslationStore.Entry persisted = loadedStore().find(ID, FINGERPRINT).orElseThrow();
        assertEquals(entry, persisted);
    }

    @Test
    void manualTranslationTakesPrecedenceOverSkipResult() {
        SignTranslationStore store = loadedStore();
        store.putSkipped(ID, FINGERPRINT, "仓库\nWarehouse");
        assertTrue(store.putManual(ID, FINGERPRINT, "仓库\nWarehouse", "ja_jp", "倉庫", false));
        store.putSkipped(ID, FINGERPRINT, "仓库\nWarehouse");

        SignTranslationStore.Entry entry = store.find(ID, FINGERPRINT).orElseThrow();
        assertFalse(entry.skipTranslation());
        assertEquals(Map.of("ja_jp", "倉庫"), entry.translations());
        assertEquals(Set.of("ja_jp"), entry.manualLanguages());
    }

    @Test
    void languagesEntriesAndWholeStoreCanBeRemoved() {
        SignTranslationStore store = loadedStore();
        store.putAutomatic(ID, FINGERPRINT, "Welcome", Map.of("zh_cn", "欢迎", "ja_jp", "ようこそ"), false);

        assertTrue(store.removeLanguage(ID, FINGERPRINT, "zh-CN"));
        assertEquals(Map.of("ja_jp", "ようこそ"), store.find(ID, FINGERPRINT).orElseThrow().translations());
        assertFalse(store.removeLanguage(ID, "wrong-fingerprint", "ja_jp"));
        assertEquals(1, store.size());

        store.remove(ID, FINGERPRINT);
        assertEquals(0, store.size());

        store.putSkipped(ID, FINGERPRINT, "仓库\nWarehouse");
        store.putAutomatic("second", "second-fingerprint", "Exit", Map.of("zh_cn", "出口"), false);
        assertEquals(2, store.clearAll());
        assertEquals(0, store.size());
        assertEquals(0, store.clearAll());
    }

    @Test
    void removingLastNonSkipLanguageRemovesEntry() {
        SignTranslationStore store = loadedStore();
        store.putManual(ID, FINGERPRINT, "Welcome", "zh_cn", "欢迎", false);

        assertTrue(store.removeLanguage(ID, FINGERPRINT, "zh_cn"));
        assertTrue(store.find(ID, FINGERPRINT).isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void oldAutomaticPolicyIsInvalidButOldManualValueSurvives() throws IOException {
        writeLegacyEntries();

        SignTranslationStore store = loadedStore();
        assertTrue(store.find("legacy-auto", "auto-fingerprint").isEmpty());

        SignTranslationStore.Entry manual = store.find("legacy-manual", "manual-fingerprint").orElseThrow();
        assertEquals(Map.of("zh_cn", "人工翻译"), manual.translations());
        assertEquals(Set.of("zh_cn"), manual.manualLanguages());
        assertEquals(SignTranslationStore.CURRENT_POLICY_VERSION, manual.policyVersion());
        assertFalse(manual.skipTranslation());

        store.putAutomatic(
                "legacy-manual",
                "manual-fingerprint",
                "Welcome",
                Map.of("zh_cn", "新的自动翻译", "ja_jp", "ようこそ"),
                false);
        SignTranslationStore.Entry merged = store.find("legacy-manual", "manual-fingerprint").orElseThrow();
        assertEquals("人工翻译", merged.translations().get("zh_cn"));
        assertEquals("ようこそ", merged.translations().get("ja_jp"));
    }

    @Test
    void editedSourceFingerprintInvalidatesEveryEntryType() {
        SignTranslationStore store = loadedStore();
        store.putManual(ID, FINGERPRINT, "Welcome", "zh_cn", "欢迎", false);

        assertTrue(store.find(ID, "edited-fingerprint").isEmpty());
        assertFalse(store.removeLanguage(ID, "edited-fingerprint", "zh_cn"));
        assertEquals(1, store.size());
    }

    private SignTranslationStore loadedStore() {
        SignTranslationStore store = new SignTranslationStore();
        store.load(worldRoot);
        return store;
    }

    private void writeLegacyEntries() throws IOException {
        Path dataDirectory = worldRoot.resolve("data");
        Files.createDirectories(dataDirectory);

        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        ListTag entries = new ListTag();
        entries.add(legacyEntry(
                "legacy-auto",
                "auto-fingerprint",
                Map.of("zh_cn", "旧自动翻译"),
                Set.of()));
        entries.add(legacyEntry(
                "legacy-manual",
                "manual-fingerprint",
                Map.of("zh_cn", "人工翻译", "ja_jp", "旧自动翻译"),
                Set.of("zh_cn")));
        root.put("entries", entries);
        NbtIo.writeCompressed(root, dataDirectory.resolve("mineastr_sign_translations.dat"));
    }

    private CompoundTag legacyEntry(
            String id,
            String fingerprint,
            Map<String, String> translations,
            Set<String> manualLanguages) {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", id);
        entry.putString("fingerprint", fingerprint);
        entry.putString("source", "Welcome");
        entry.putBoolean("show_original", false);
        entry.putInt("policy_version", 1);

        CompoundTag translationTag = new CompoundTag();
        translations.forEach(translationTag::putString);
        entry.put("translations", translationTag);

        CompoundTag manualTag = new CompoundTag();
        manualLanguages.forEach(language -> manualTag.putBoolean(language, true));
        entry.put("manual_languages", manualTag);
        return entry;
    }
}
