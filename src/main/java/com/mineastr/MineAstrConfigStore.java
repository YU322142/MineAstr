package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;

final class MineAstrConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final List<Value<?>> values = new ArrayList<>();

    MineAstrConfigStore(String fileName) {
        this.path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }

    BooleanValue bool(String key, boolean defaultValue) {
        return register(new BooleanValue(key, defaultValue));
    }

    IntValue integer(String key, int defaultValue, int minimum, int maximum) {
        return register(new IntValue(key, defaultValue, minimum, maximum));
    }

    DoubleValue decimal(String key, double defaultValue, double minimum, double maximum) {
        return register(new DoubleValue(key, defaultValue, minimum, maximum));
    }

    StringValue string(String key, String defaultValue, int maxLength) {
        return register(new StringValue(key, defaultValue, maxLength));
    }

    StringListValue stringList(String key, List<String> defaultValue) {
        return register(new StringListValue(key, defaultValue));
    }

    <E extends Enum<E>> EnumValue<E> enumValue(String key, E defaultValue, Class<E> enumClass) {
        return register(new EnumValue<>(key, defaultValue, enumClass));
    }

    private <T extends Value<?>> T register(T value) {
        values.add(value);
        return value;
    }

    synchronized void load() {
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                throw new IOException("配置根节点不是 JSON 对象");
            }
            JsonObject object = root.getAsJsonObject();
            for (Value<?> value : values) {
                value.load(object.get(value.key));
            }
        } catch (Exception exc) {
            MineAstr.LOGGER.error("MineAstr 配置 {} 读取失败，将使用安全默认值：{}", path, exc.getMessage());
            values.forEach(Value::reset);
        }
    }

    synchronized void save() {
        JsonObject object = new JsonObject();
        for (Value<?> value : values) {
            object.add(value.key, value.toJson());
        }
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(object) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exc) {
            MineAstr.LOGGER.error("MineAstr 配置 {} 保存失败：{}", path, exc.getMessage());
        }
    }

    abstract static class Value<T> {
        private final String key;
        private final T defaultValue;
        private volatile T value;

        Value(String key, T defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        public T getDefault() {
            return defaultValue;
        }

        public void set(T newValue) {
            value = normalize(newValue);
        }

        final void load(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                reset();
                return;
            }
            try {
                value = normalize(read(element));
            } catch (RuntimeException exc) {
                reset();
                MineAstr.LOGGER.warn("MineAstr 配置项 {} 无效，已使用默认值。", key);
            }
        }

        final void reset() {
            value = defaultValue;
        }

        T normalize(T candidate) {
            return candidate == null ? defaultValue : candidate;
        }

        abstract T read(JsonElement element);

        abstract JsonElement toJson();
    }

    static final class BooleanValue extends Value<Boolean> {
        BooleanValue(String key, boolean defaultValue) {
            super(key, defaultValue);
        }

        public boolean getAsBoolean() {
            return get();
        }

        @Override
        Boolean read(JsonElement element) {
            return element.getAsBoolean();
        }

        @Override
        JsonElement toJson() {
            return GSON.toJsonTree(get());
        }
    }

    static final class IntValue extends Value<Integer> {
        private final int minimum;
        private final int maximum;

        IntValue(String key, int defaultValue, int minimum, int maximum) {
            super(key, defaultValue);
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public int getAsInt() {
            return get();
        }

        @Override
        Integer normalize(Integer candidate) {
            return Math.max(minimum, Math.min(maximum, super.normalize(candidate)));
        }

        @Override
        Integer read(JsonElement element) {
            return element.getAsInt();
        }

        @Override
        JsonElement toJson() {
            return GSON.toJsonTree(get());
        }
    }

    static final class DoubleValue extends Value<Double> {
        private final double minimum;
        private final double maximum;

        DoubleValue(String key, double defaultValue, double minimum, double maximum) {
            super(key, defaultValue);
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public double getAsDouble() {
            return get();
        }

        @Override
        Double normalize(Double candidate) {
            return Math.max(minimum, Math.min(maximum, super.normalize(candidate)));
        }

        @Override
        Double read(JsonElement element) {
            return element.getAsDouble();
        }

        @Override
        JsonElement toJson() {
            return GSON.toJsonTree(get());
        }
    }

    static final class StringValue extends Value<String> {
        private final int maxLength;

        StringValue(String key, String defaultValue, int maxLength) {
            super(key, defaultValue);
            this.maxLength = maxLength;
        }

        @Override
        String normalize(String candidate) {
            String normalized = super.normalize(candidate).strip();
            return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
        }

        @Override
        String read(JsonElement element) {
            return element.getAsString();
        }

        @Override
        JsonElement toJson() {
            return GSON.toJsonTree(get());
        }
    }

    static final class StringListValue extends Value<List<String>> {
        StringListValue(String key, List<String> defaultValue) {
            super(key, List.copyOf(defaultValue));
        }

        @Override
        List<String> normalize(List<String> candidate) {
            if (candidate == null) {
                return getDefault();
            }
            return candidate.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(item -> item.strip().substring(0, Math.min(256, item.strip().length())))
                    .distinct()
                    .toList();
        }

        @Override
        List<String> read(JsonElement element) {
            JsonArray array = element.getAsJsonArray();
            List<String> result = new ArrayList<>();
            for (JsonElement item : array) {
                result.add(item.getAsString());
            }
            return result;
        }

        @Override
        JsonElement toJson() {
            return GSON.toJsonTree(get());
        }
    }

    static final class EnumValue<E extends Enum<E>> extends Value<E> {
        private final Function<String, E> parser;

        EnumValue(String key, E defaultValue, Class<E> enumClass) {
            super(key, defaultValue);
            this.parser = value -> Enum.valueOf(enumClass, value.toUpperCase(Locale.ROOT));
        }

        @Override
        E read(JsonElement element) {
            return parser.apply(element.getAsString());
        }

        @Override
        JsonElement toJson() {
            return GSON.toJsonTree(get().name());
        }
    }
}
