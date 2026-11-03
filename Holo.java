package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.mojang.math.Transformation;
import org.joml.Vector3f;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Floating text, built directly as entities (no /summon commands), so there's nothing for the
 * server to mis-parse. Every piece is tagged so it can be found and removed again.
 */
final class Holo {
    private Holo() {}

    static void spawn(ServerLevel level, double x, double y, double z, Component text, String tag, float scale, boolean background) {
        Display.TextDisplay d = EntityType.TEXT_DISPLAY.create(level);
        if (d == null) return;
        d.setPos(x, y, z);
        d.addTag("jcl_holo");
        d.addTag(tag);
        call(d, Display.TextDisplay.class, "setText", Component.class, text);
        call(d, Display.class, "setBillboardConstraints", Display.BillboardConstraints.class, Display.BillboardConstraints.CENTER);
        call(d, Display.class, "setTransformation", Transformation.class, new Transformation(null, null, new Vector3f(scale, scale, scale), null));
        call(d, Display.TextDisplay.class, "setBackgroundColor", int.class, background ? 0x40000000 : 0);
        d.setNoGravity(true);
        level.addFreshEntity(d);
    }

    static int remove(ServerLevel level, String tag) {
        List<Entity> found = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e instanceof Display.TextDisplay && e.getTags().contains(tag)) found.add(e);
        for (Entity e : found) e.discard();
        return found.size();
    }

    static int removeNear(ServerLevel level, double x, double y, double z, double r) {
        List<Entity> found = new ArrayList<>();
        for (Entity e : level.getAllEntities())
            if (e instanceof Display.TextDisplay && e.getTags().contains("jcl_holo") && e.distanceToSqr(x, y, z) <= r * r) found.add(e);
        for (Entity e : found) e.discard();
        return found.size();
    }

    private static void call(Object target, Class<?> owner, String name, Class<?> type, Object value) {
        try {
            Method m = owner.getDeclaredMethod(name, type);
            m.setAccessible(true);
            m.invoke(target, value);
        } catch (Throwable t) {
            GtsMod.LOG.debug("[JCL] Floating text: could not call {}", name, t);
        }
    }
}
