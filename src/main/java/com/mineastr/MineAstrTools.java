package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * <p>服务器状态、玩家与实体查询；调用方须在服务端线程访问游戏对象。<br>
 * Query server, player and entity state; callers must access game objects on the server thread.</p>
 */
public final class MineAstrTools {
    private static final int MAX_ENTITY_DETAILS = 32;

    private MineAstrTools() {
    }

    public static JsonObject buildPlayerState(ServerPlayer player) {
        JsonObject data = playerIdentity(player);
        data.addProperty("dimension", player.serverLevel().dimension().location().toString());
        data.addProperty("x", round2(player.getX()));
        data.addProperty("y", round2(player.getY()));
        data.addProperty("z", round2(player.getZ()));
        data.addProperty("yaw", round2(player.getYRot()));
        data.addProperty("pitch", round2(player.getXRot()));
        data.addProperty("game_mode", player.gameMode.getGameModeForPlayer().getName());
        data.addProperty("health", round2(player.getHealth()));
        data.addProperty("max_health", round2(player.getMaxHealth()));
        data.addProperty("armor", player.getArmorValue());
        data.addProperty("food", player.getFoodData().getFoodLevel());
        data.addProperty("saturation", round2(player.getFoodData().getSaturationLevel()));
        data.addProperty("air", player.getAirSupply());
        data.addProperty("experience_level", player.experienceLevel);
        data.addProperty("experience_progress", round2(player.experienceProgress));
        data.addProperty("on_ground", player.onGround());
        data.addProperty("sprinting", player.isSprinting());
        data.addProperty("swimming", player.isSwimming());
        data.addProperty("sleeping", player.isSleeping());

        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject item = new JsonObject();
            String id = effect.getEffect().unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");
            item.addProperty("id", id);
            item.addProperty("amplifier", effect.getAmplifier());
            item.addProperty("duration_ticks", effect.getDuration());
            item.addProperty("ambient", effect.isAmbient());
            item.addProperty("visible", effect.isVisible());
            effects.add(item);
        }
        data.add("effects", effects);
        return data;
    }

    public static JsonObject buildInventory(ServerPlayer player, boolean includeEnderChest) {
        JsonObject data = playerIdentity(player);
        Inventory inventory = player.getInventory();
        data.addProperty("selected_hotbar_slot", inventory.selected);
        data.add("selected_item", stackData(inventory.getSelected(), "selected"));

        JsonArray hotbar = new JsonArray();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            addStack(hotbar, inventory.getItem(slot), "hotbar_" + slot, slot);
        }
        data.add("hotbar", hotbar);

        JsonArray main = new JsonArray();
        for (int slot = Inventory.getSelectionSize(); slot < inventory.items.size(); slot++) {
            addStack(main, inventory.items.get(slot), "inventory_" + slot, slot);
        }
        data.add("inventory", main);

        String[] armorNames = {"feet", "legs", "chest", "head"};
        JsonArray armor = new JsonArray();
        for (int slot = 0; slot < inventory.armor.size(); slot++) {
            addStack(armor, inventory.armor.get(slot), armorNames[Math.min(slot, armorNames.length - 1)], slot);
        }
        data.add("armor", armor);

        JsonArray offhand = new JsonArray();
        for (int slot = 0; slot < inventory.offhand.size(); slot++) {
            addStack(offhand, inventory.offhand.get(slot), "offhand", slot);
        }
        data.add("offhand", offhand);

        int occupied = 0;
        int totalItems = 0;
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty()) {
                occupied++;
                totalItems += stack.getCount();
            }
        }
        data.addProperty("occupied_inventory_slots", occupied);
        data.addProperty("free_inventory_slots", Math.max(0, inventory.items.size() - occupied));
        data.addProperty("total_inventory_item_count", totalItems);

        if (includeEnderChest) {
            data.add("ender_chest", containerData(player.getEnderChestInventory(), "ender_chest"));
        }
        return data;
    }

    public static JsonObject buildNearbyEntities(ServerPlayer player, double radius) {
        ServerLevel level = player.serverLevel();
        AABB bounds = new AABB(
                player.getX() - radius,
                player.getY() - radius,
                player.getZ() - radius,
                player.getX() + radius,
                player.getY() + radius,
                player.getZ() + radius);
        List<Entity> entities = new ArrayList<>(level.getEntities(player, bounds, entity -> entity != player));
        entities.sort(Comparator.comparingDouble(player::distanceToSqr));

        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (Entity entity : entities) {
            String type = entityTypeId(entity);
            typeCounts.merge(type, 1, Integer::sum);
            categoryCounts.merge(entity.getType().getCategory().getName(), 1, Integer::sum);
        }

        JsonObject data = playerIdentity(player);
        data.addProperty("dimension", level.dimension().location().toString());
        data.addProperty("radius", round2(radius));
        data.addProperty("entity_count", entities.size());
        data.add("counts_by_type", countObject(typeCounts, 32));
        data.add("counts_by_category", countObject(categoryCounts, 16));

        JsonArray details = new JsonArray();
        for (int index = 0; index < Math.min(MAX_ENTITY_DETAILS, entities.size()); index++) {
            Entity entity = entities.get(index);
            JsonObject item = new JsonObject();
            item.addProperty("type", entityTypeId(entity));
            item.addProperty("name", entity.getName().getString());
            item.addProperty("x", round2(entity.getX()));
            item.addProperty("y", round2(entity.getY()));
            item.addProperty("z", round2(entity.getZ()));
            item.addProperty("distance", round2(Math.sqrt(player.distanceToSqr(entity))));
            if (entity instanceof LivingEntity living) {
                item.addProperty("health", round2(living.getHealth()));
                item.addProperty("max_health", round2(living.getMaxHealth()));
            }
            details.add(item);
        }
        data.add("nearest_entities", details);
        data.addProperty("details_truncated", entities.size() > MAX_ENTITY_DETAILS);
        return data;
    }

    private static JsonObject playerIdentity(ServerPlayer player) {
        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().getName());
        data.addProperty("display_name", player.getDisplayName().getString());
        return data;
    }

    private static JsonArray containerData(Container container, String prefix) {
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            addStack(items, container.getItem(slot), prefix + "_" + slot, slot);
        }
        return items;
    }

    private static void addStack(JsonArray output, ItemStack stack, String slotName, int slot) {
        if (stack.isEmpty()) {
            return;
        }
        JsonObject data = stackData(stack, slotName);
        data.addProperty("slot_index", slot);
        output.add(data);
    }

    private static JsonObject stackData(ItemStack stack, String slotName) {
        JsonObject data = new JsonObject();
        data.addProperty("slot", slotName);
        if (stack.isEmpty()) {
            data.addProperty("empty", true);
            return data;
        }
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        data.addProperty("id", itemKey == null ? "unknown" : itemKey.toString());
        data.addProperty("name", stack.getHoverName().getString());
        data.addProperty("count", stack.getCount());
        data.addProperty("enchanted", stack.isEnchanted());
        if (stack.isDamageableItem()) {
            data.addProperty("damage", stack.getDamageValue());
            data.addProperty("max_damage", stack.getMaxDamage());
            data.addProperty("remaining_durability", Math.max(0, stack.getMaxDamage() - stack.getDamageValue()));
        }
        return data;
    }

    private static String entityTypeId(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "unknown" : key.toString();
    }

    private static JsonObject countObject(Map<String, Integer> counts, int limit) {
        JsonObject output = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> output.addProperty(entry.getKey(), entry.getValue()));
        return output;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
