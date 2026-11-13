package net.joeyscobblemon.gts;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/** The sidebar's lines plus where and how big this player wants it (x and y in percent of the screen). */
public record SidebarPayload(List<String> lines, float x, float y, float scale, boolean visible) implements CustomPacketPayload {
    public static final Type<SidebarPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GtsMod.MODID, "sidebar"));
    public static final StreamCodec<ByteBuf, SidebarPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(32)), SidebarPayload::lines,
            ByteBufCodecs.FLOAT, SidebarPayload::x,
            ByteBufCodecs.FLOAT, SidebarPayload::y,
            ByteBufCodecs.FLOAT, SidebarPayload::scale,
            ByteBufCodecs.BOOL, SidebarPayload::visible,
            SidebarPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** What the client last received (plain data, safe to reference on the server). */
    public static volatile SidebarPayload LATEST;

    @EventBusSubscriber(modid = GtsMod.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reg {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent e) {
            e.registrar("1").optional().playToClient(TYPE, CODEC, (payload, ctx) -> ctx.enqueueWork(() -> LATEST = payload));
        }
    }
}
