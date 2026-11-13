package net.joeyscobblemon.gts;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Removes entities across a big area, including chunks nobody is standing in: chunks are force-loaded in batches,
 * the game is given time to load their entities, matching ones are removed, and the chunks are released again.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class EntityClear {
    private EntityClear() {}
    static final int BATCH = 7, WAIT = 40;     // 7 x 7 chunks at a time, 2 seconds for their entities to load

    record Batch(ServerLevel lvl, int cx0, int cz0, int cx1, int cz1, Predicate<Entity> match) {}
    static final Deque<Object> QUEUE = new ArrayDeque<>();   // Batch, or a Runnable to run when reached
    static Batch current; static int wait; static int removed;

    /** Queues an area (block coordinates). done gets the number removed once everything queued so far is finished. */
    static void area(ServerLevel lvl, int x0, int z0, int x1, int z1, Predicate<Entity> match) {
        int cx0 = Math.floorDiv(x0, 16), cz0 = Math.floorDiv(z0, 16), cx1 = Math.floorDiv(x1, 16), cz1 = Math.floorDiv(z1, 16);
        for (int cx = cx0; cx <= cx1; cx += BATCH) for (int cz = cz0; cz <= cz1; cz += BATCH)
            QUEUE.add(new Batch(lvl, cx, cz, Math.min(cx1, cx + BATCH - 1), Math.min(cz1, cz + BATCH - 1), match));
    }
    static void then(Runnable r) { QUEUE.add(r); }
    static void resetCount() { QUEUE.add((Runnable) () -> removed = 0); }
    static int count() { return removed; }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (current != null) {
            if (--wait > 0) return;
            AABB box = new AABB(current.cx0() * 16, current.lvl().getMinBuildHeight(), current.cz0() * 16, current.cx1() * 16 + 16, current.lvl().getMaxBuildHeight(), current.cz1() * 16 + 16);
            for (Entity ent : current.lvl().getEntities((Entity) null, box, current.match())) { ent.discard(); removed++; }
            for (int cx = current.cx0(); cx <= current.cx1(); cx++) for (int cz = current.cz0(); cz <= current.cz1(); cz++) current.lvl().setChunkForced(cx, cz, false);
            current = null;
        }
        while (!QUEUE.isEmpty()) {
            Object o = QUEUE.poll();
            if (o instanceof Runnable r) { try { r.run(); } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Entity clear step failed: {}", t.toString()); } continue; }
            Batch b = (Batch) o;
            for (int cx = b.cx0(); cx <= b.cx1(); cx++) for (int cz = b.cz0(); cz <= b.cz1(); cz++) b.lvl().setChunkForced(cx, cz, true);
            current = b; wait = WAIT; return;
        }
    }

    /** Everything JCL placed in the world: NPCs, leaderboards, floating text, machines, podiums (never players). */
    static boolean jcl(Entity e) {
        if (e instanceof net.minecraft.server.level.ServerPlayer) return false;
        for (String t : e.getTags()) if (t.startsWith("jcl")) return true;
        return false;
    }
}
