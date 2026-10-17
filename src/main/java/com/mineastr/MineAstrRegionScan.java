package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 按列推进的区域分析；仅访问已加载区块，所有世界访问留在服务器线程。
 * Incremental column scan of loaded chunks, accessed only on the server thread.
 * Results describe the world during the scan, not an atomic world snapshot.
 */
final class MineAstrRegionScan {
    private static final int MAX_PALETTE_RESULTS = 24;
    private static final int MAX_FEATURE_SAMPLES = 8;
    private static final int GRID_X = 6, GRID_Y = 4, GRID_Z = 6;
    private final ServerLevel level;
    private final BlockPos center;
    private int column;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Map<String, Integer> palette = new HashMap<>();
    private final Map<String, Integer> biomes = new HashMap<>();
    private final Map<String, Integer> features = new LinkedHashMap<>();
    private final Map<String, JsonArray> featureSamples = new LinkedHashMap<>();
    private final Map<String, Integer> blockEntityTypes = new HashMap<>();
    private final int[] gridTotal = new int[GRID_X * GRID_Y * GRID_Z];
    private final int[] gridOccupied = new int[gridTotal.length];
    private final int[] gridConstructed = new int[gridTotal.length];
    private int scanned = 0;
    private int unloaded = 0;
    private int nonAir = 0;
    private int solid = 0;
    private int fluid = 0;
    private int likelyConstructed = 0;
    private long surfaceTotal = 0;
    private int surfaceColumns = 0;
    private int surfaceMin = Integer.MAX_VALUE;
    private int surfaceMax = Integer.MIN_VALUE;
    private final Bounds occupiedBounds = new Bounds();
    private final Bounds constructedBounds = new Bounds();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    MineAstrRegionScan(ServerLevel level, BlockPos center, int horizontalRadius, int verticalRadius) {
        this.level = level;
        this.center = center.immutable();
        minX = center.getX() - horizontalRadius;
        maxX = center.getX() + horizontalRadius;
        minY = Math.max(level.getMinBuildHeight(), center.getY() - verticalRadius);
        maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + verticalRadius);
        minZ = center.getZ() - horizontalRadius;
        maxZ = center.getZ() + horizontalRadius;
        sizeX = maxX - minX + 1;
        sizeY = maxY - minY + 1;
        sizeZ = maxZ - minZ + 1;
    }

    /** 每一步最多 33 个方块，区块卸载后跳过；one bounded column per step. */
    boolean step() {
        if (column >= sizeX * sizeZ) return true;
        int x = minX + column / sizeZ;
        int z = minZ + column % sizeZ;
        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        boolean loaded = chunk != null;
        int columnTop = Integer.MIN_VALUE;
        for (int y = minY; y <= maxY; y++) {
            int gridIndex = gridIndex(x, y, z, minX, minY, minZ, sizeX, sizeY, sizeZ);
            if (!loaded) {
                unloaded++;
                continue;
            }
            gridTotal[gridIndex]++;
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            scanned++;
            if (state.isAir()) {
                continue;
            }

            nonAir++;
            gridOccupied[gridIndex]++;
            occupiedBounds.include(x, y, z);
            columnTop = Math.max(columnTop, y);
            ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String blockId = blockKey == null ? "unknown" : blockKey.toString();
            palette.merge(blockId, 1, Integer::sum);
            if (state.blocksMotion()) {
                solid++;
            }
            if (!state.getFluidState().isEmpty()) {
                fluid++;
            }

            String path = blockKey == null ? blockId : blockKey.getPath();
            boolean constructed = isLikelyConstructed(path);
            if (constructed) {
                likelyConstructed++;
                gridConstructed[gridIndex]++;
                constructedBounds.include(x, y, z);
            }
            String feature = featureCategory(path);
            if (feature != null) {
                features.merge(feature, 1, Integer::sum);
                JsonArray samples = featureSamples.computeIfAbsent(feature, ignored -> new JsonArray());
                if (samples.size() < MAX_FEATURE_SAMPLES) {
                    samples.add(positionObject(x, y, z, blockId));
                }
            }
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = chunk.getBlockEntity(pos);
                if (blockEntity != null) {
                    ResourceLocation typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                    blockEntityTypes.merge(typeKey == null ? "unknown" : typeKey.toString(), 1, Integer::sum);
                }
            }
        }
        if (loaded) {
            int biomeY = columnTop == Integer.MIN_VALUE ? center.getY() : columnTop;
            pos.set(x, Math.max(minY, Math.min(maxY, biomeY)), z);
            String biome = level.getBiome(pos).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");
            biomes.merge(biome, 1, Integer::sum);
        }
        if (columnTop != Integer.MIN_VALUE) {
            surfaceMin = Math.min(surfaceMin, columnTop);
            surfaceMax = Math.max(surfaceMax, columnTop);
            surfaceTotal += columnTop;
            surfaceColumns++;
        }
        column++;
        return column >= sizeX * sizeZ;
    }

    JsonObject result() {
        if (column < sizeX * sizeZ) throw new IllegalStateException("Region scan is incomplete");
        JsonObject data = new JsonObject();
        data.addProperty("dimension", level.dimension().location().toString());
        data.add("center", positionObject(center.getX(), center.getY(), center.getZ(), null));
        data.add("bounds", boundsObject(minX, minY, minZ, maxX, maxY, maxZ));
        data.addProperty("requested_blocks", sizeX * sizeY * sizeZ);
        data.addProperty("scanned_loaded_blocks", scanned);
        data.addProperty("skipped_unloaded_blocks", unloaded);
        data.addProperty("non_air_blocks", nonAir);
        data.addProperty("solid_blocks", solid);
        data.addProperty("fluid_blocks", fluid);
        data.addProperty("likely_constructed_blocks", likelyConstructed);
        data.addProperty("likely_constructed_ratio", nonAir == 0 ? 0.0 : round3((double) likelyConstructed / nonAir));
        data.add("occupied_bounds", occupiedBounds.toJson());
        data.add("likely_constructed_bounds", constructedBounds.toJson());
        data.add("top_block_palette", countArray(palette, MAX_PALETTE_RESULTS));
        data.add("biomes", countArray(biomes, 12));
        data.add("feature_counts", countObject(features, 32));

        JsonObject samples = new JsonObject();
        for (Map.Entry<String, JsonArray> entry : featureSamples.entrySet()) {
            samples.add(entry.getKey(), entry.getValue());
        }
        data.add("feature_samples", samples);
        data.add("block_entity_counts", countObject(blockEntityTypes, 24));

        JsonObject surface = new JsonObject();
        surface.addProperty("sampled_columns", surfaceColumns);
        if (surfaceColumns > 0) {
            surface.addProperty("min_y", surfaceMin);
            surface.addProperty("max_y", surfaceMax);
            surface.addProperty("average_y", round2((double) surfaceTotal / surfaceColumns));
            surface.addProperty("height_variation", surfaceMax - surfaceMin);
        }
        data.add("surface", surface);
        data.add("coarse_shape", coarseShape(gridTotal, gridOccupied, gridConstructed));
        data.addProperty("privacy_note", "仅包含方块类型、计数和粗略形状；未读取容器内容、告示牌文字或方块实体 NBT。");
        return data;
    }

    private static JsonObject countObject(Map<String, Integer> counts, int limit) {
        JsonObject output = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> output.addProperty(entry.getKey(), entry.getValue()));
        return output;
    }

    private static JsonArray countArray(Map<String, Integer> counts, int limit) {
        JsonArray output = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    output.add(item);
                });
        return output;
    }

    private static JsonObject coarseShape(int[] total, int[] occupied, int[] constructed) {
        JsonObject data = new JsonObject();
        data.addProperty("grid_x", GRID_X);
        data.addProperty("grid_y", GRID_Y);
        data.addProperty("grid_z", GRID_Z);
        data.addProperty("order", "y,z,x");
        JsonArray occupancy = new JsonArray();
        JsonArray constructedRatio = new JsonArray();
        for (int index = 0; index < total.length; index++) {
            occupancy.add(total[index] == 0 ? 0 : Math.round(occupied[index] * 100.0F / total[index]));
            constructedRatio.add(occupied[index] == 0 ? 0 : Math.round(constructed[index] * 100.0F / occupied[index]));
        }
        data.add("occupancy_percent", occupancy);
        data.add("constructed_percent_of_occupied", constructedRatio);
        return data;
    }

    private static int gridIndex(
            int x,
            int y,
            int z,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ) {
        int gx = Math.min(GRID_X - 1, (x - minX) * GRID_X / Math.max(1, sizeX));
        int gy = Math.min(GRID_Y - 1, (y - minY) * GRID_Y / Math.max(1, sizeY));
        int gz = Math.min(GRID_Z - 1, (z - minZ) * GRID_Z / Math.max(1, sizeZ));
        return (gy * GRID_Z + gz) * GRID_X + gx;
    }

    private static String featureCategory(String path) {
        String id = path.toLowerCase(Locale.ROOT);
        if (id.contains("door") && !id.contains("trapdoor")) return "doors";
        if (id.contains("trapdoor")) return "trapdoors";
        if (id.contains("stairs")) return "stairs";
        if (id.contains("slab")) return "slabs";
        if (id.contains("glass") || id.contains("pane")) return "windows_or_glass";
        if (id.contains("fence") || id.endsWith("_wall")) return "fences_or_walls";
        if (id.contains("torch") || id.contains("lantern") || id.contains("light") || id.contains("glowstone")) return "lighting";
        if (id.contains("chest") || id.contains("barrel") || id.contains("shulker_box")) return "storage";
        if (id.contains("furnace") || id.contains("smoker") || id.contains("blast_furnace")) return "furnaces";
        if (id.contains("crafting_table") || id.contains("anvil") || id.contains("stonecutter") || id.contains("loom")) return "workstations";
        if (id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("piston") || id.contains("observer")) return "redstone";
        if (id.contains("rail")) return "rails";
        if (id.contains("bed")) return "beds";
        if (id.contains("sign")) return "signs";
        if (id.contains("ladder") || id.contains("scaffolding")) return "vertical_access";
        if (id.contains("crop") || id.contains("wheat") || id.contains("carrot") || id.contains("potato") || id.contains("farmland")) return "farming";
        return null;
    }

    private static boolean isLikelyConstructed(String path) {
        String id = path.toLowerCase(Locale.ROOT);
        if (featureCategory(id) != null) {
            return true;
        }
        String[] constructedMarkers = {
                "planks", "bricks", "brick_", "concrete", "terracotta", "wool", "carpet", "tiles",
                "polished", "chiseled", "cut_", "smooth_", "quartz", "prismarine", "purpur",
                "copper", "bookshelf", "decorated_pot", "banner", "painting"
        };
        for (String marker : constructedMarkers) {
            if (id.contains(marker)) {
                return true;
            }
        }
        String[] naturalMarkers = {
                "air", "stone", "deepslate", "dirt", "grass", "podzol", "mycelium", "sand", "gravel",
                "ore", "raw_", "bedrock", "water", "lava", "snow", "ice", "clay", "mud", "moss",
                "netherrack", "soul_sand", "soul_soil", "basalt", "blackstone", "end_stone", "obsidian",
                "log", "wood", "leaves", "sapling", "vine", "flower", "mushroom", "cactus", "bamboo",
                "kelp", "seagrass", "coral", "dripstone", "amethyst", "sculk", "tuff", "calcite"
        };
        for (String marker : naturalMarkers) {
            if (id.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject positionObject(int x, int y, int z, String blockId) {
        JsonObject data = new JsonObject();
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        if (blockId != null) {
            data.addProperty("block", blockId);
        }
        return data;
    }

    private static JsonObject boundsObject(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        JsonObject data = new JsonObject();
        data.add("min", positionObject(minX, minY, minZ, null));
        data.add("max", positionObject(maxX, maxY, maxZ, null));
        return data;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class Bounds {
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private void include(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        private JsonObject toJson() {
            if (minX == Integer.MAX_VALUE) {
                return new JsonObject();
            }
            return boundsObject(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
