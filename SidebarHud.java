package net.joeyscobblemon.gts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Client only: draws the JCL sidebar where the player put it, at the size they chose. */
@EventBusSubscriber(modid = GtsMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SidebarHud {
    private SidebarHud() {}

    /** Set if anything ever goes wrong drawing: the panel switches itself off and the server sends the vanilla sidebar. */
    public static volatile boolean BROKEN;

    @SubscribeEvent
    public static void layers(RegisterGuiLayersEvent e) {
        try { e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(GtsMod.MODID, "sidebar"), (g, delta) -> { if (!BROKEN) try { draw(g); } catch (Throwable t) { BROKEN = true; GtsMod.LOG.warn("[JCL] Sidebar panel turned off: {}", t.toString()); } }); }
        catch (Throwable t) { BROKEN = true; GtsMod.LOG.warn("[JCL] Sidebar panel unavailable: {}", t.toString()); }
    }

    static void draw(GuiGraphics g) {
        SidebarPayload p = SidebarPayload.LATEST;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || !p.visible() || p.lines().isEmpty() || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        Font f = mc.font;
        int w = 0;
        for (String l : p.lines()) w = Math.max(w, f.width(l));
        int lineH = 10, pad = 4, title = 13;
        int h = title + (p.lines().size() - 1) * lineH + pad * 2;
        int boxW = w + pad * 2;
        float s = p.scale();
        int sw = g.guiWidth(), sh = g.guiHeight();
        float px = (sw - boxW * s) * (p.x() / 100f), py = (sh - h * s) * (p.y() / 100f);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(px, py, 0);
        pose.scale(s, s, 1);
        g.fill(0, 0, boxW, h, 0x88000000);                       // a soft dark panel
        g.fill(0, title + pad - 2, boxW, title + pad - 1, 0x55FFFFFF); // a thin line under the title
        // title centred, then the lines left-aligned (blank lines keep their spacing)
        String t = p.lines().get(0);
        g.drawString(f, t, (boxW - f.width(t)) / 2, pad, 0xFFFFFF, true);
        for (int i = 1; i < p.lines().size(); i++) g.drawString(f, p.lines().get(i), pad, pad + title + (i - 1) * lineH, 0xFFFFFF, true);
        pose.popPose();
    }
}
