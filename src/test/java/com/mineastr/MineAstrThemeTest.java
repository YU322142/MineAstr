package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class MineAstrThemeTest {
    @Test void darkColorsAreRejectedWithoutChangingTheRequestedColor() {
        assertFalse(MineAstrThemeColor.isReadable(0x000000));
        assertFalse(MineAstrThemeColor.isReadable(0x303030));
        assertFalse(MineAstrThemeColor.isReadable(0xFFFFFF | 0xFF000000));
        assertEquals(0x080910, MineAstrThemeColor.parse("#080910"));
        assertFalse(MineAstrThemeColor.isReadable(MineAstrThemeColor.parse("#080910")));
        assertThrows(IllegalArgumentException.class, () -> MineAstrThemeColor.parse("#FFF"));
    }
    @Test void freelyChosenRgbValuesUseTheActualContrastRatio() {
        var random = new Random(2026);
        for (int n = 0; n < 10000; n++) {
            int color = random.nextInt(0x1000000);
            assertEquals(MineAstrThemeColor.contrast(color) >= 4.5, MineAstrThemeColor.isReadable(color));
        }
    }
    @Test void everyGradientStepRetainsContrastIncludingRounding() {
        int[] colors = {0xFFFFFF, 0x72E6C1, 0xB5B8FF, 0xFF9CCC, 0xDDB344, 0x22B8B4};
        for (int a : colors) for (int b : colors) {
            assertTrue(MineAstrThemeColor.isReadable(a)); assertTrue(MineAstrThemeColor.isReadable(b));
            for (int step = 0; step <= 1024; step++) assertTrue(MineAstrThemeColor.isReadable(MineAstrThemeColor.blend(a, b, step / 1024.0)));
        }
    }
    @Test void slowGradientWrapsContinuouslyAndSolidColorStaysExact() {
        for (int count : new int[]{2, 3}) {
            assertEquals(0xFFFFFF, MineAstrThemeColor.gradient(0xFFFFFF,0x72E6C1,0xB5B8FF,count,0));
            assertEquals(0xFFFFFF, MineAstrThemeColor.gradient(0xFFFFFF,0x72E6C1,0xB5B8FF,count,1));
            assertEquals(MineAstrThemeColor.gradient(0xFFFFFF,0x72E6C1,0xB5B8FF,count,.2),MineAstrThemeColor.gradient(0xFFFFFF,0x72E6C1,0xB5B8FF,count,1.2));
        }
        assertEquals(0x72E6C1,MineAstrThemeColor.gradient(0x72E6C1,0,0,1,99.8));
    }
    @Test void onlyActiveStopsAreValidatedAndFastAnimationsAreRejected() {
        UUID id = UUID.randomUUID();
        assertTrue(new MineAstrPayloads.PlayerTheme(id,"Alice",0xFFFFFF,0,0,1,8000).valid());
        assertFalse(new MineAstrPayloads.PlayerTheme(id,"Alice",0xFFFFFF,0,0,2,8000).valid());
        assertFalse(new MineAstrPayloads.PlayerTheme(id,"Alice",0xFFFFFF,0x72E6C1,0xB5B8FF,3,1000).valid());
        assertTrue(new MineAstrPayloads.PlayerTheme(id,"Alice",0xFFFFFF,0x72E6C1,0xB5B8FF,3,20000).valid());
    }
    @Test void themeWireRoundTripPreservesAllThreeColors() {
        var profile = new MineAstrPayloads.PlayerTheme(UUID.randomUUID(),"Alice",0xFFFFFF,0x72E6C1,0xB5B8FF,3,8000);
        var message = new MineAstrPayloads.ThemePalette(true,List.of(profile));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try { MineAstrPayloads.ThemePalette.CODEC.encode(buffer,message); assertEquals(message,MineAstrPayloads.ThemePalette.CODEC.decode(buffer)); }
        finally { buffer.release(); }
        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try { buffer.writeBoolean(false).writeVarInt(129); final var oversized=buffer; assertThrows(IllegalArgumentException.class,()->MineAstrPayloads.ThemePalette.CODEC.decode(oversized)); }
        finally { buffer.release(); }
    }
    @Test void savedThemesSurviveWorldReloadAndCorruptDarkEntriesAreRejected() {
        UUID id=UUID.randomUUID(); var tag=new CompoundTag(); var entries=new ListTag(); var entry=new CompoundTag();
        entry.putUUID("uuid",id); entry.putString("name","Alice"); entry.putInt("color",0xFFFFFF);
        entry.putInt("second",0x72E6C1); entry.putInt("third",0xB5B8FF); entry.putInt("count",3); entry.putInt("period",8000);
        entries.add(entry); var invalid=entry.copy(); invalid.putUUID("uuid",UUID.randomUUID()); invalid.putInt("color",0); entries.add(invalid); tag.put("players",entries);
        var store=MineAstrPlayerThemes.load(tag,RegistryAccess.EMPTY);
        var saved=store.save(new CompoundTag(),RegistryAccess.EMPTY);
        assertEquals(1,saved.getList("players",10).size()); assertEquals(entry,saved.getList("players",10).getCompound(0));
        assertEquals(saved,MineAstrPlayerThemes.load(saved,RegistryAccess.EMPTY).save(new CompoundTag(),RegistryAccess.EMPTY));
    }
}
