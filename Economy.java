package net.joeyscobblemon.gts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Talks to CobbleDollars through its own /cd command (give, remove, query), so it works with whatever
 * version is installed. Command output is captured to read balances.
 */
public final class Economy {
    private Economy() {}

    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*)");

    private static final class Capture implements CommandSource {
        final StringBuilder out = new StringBuilder();
        @Override public void sendSystemMessage(Component c) { out.append(c.getString()).append('\n'); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    public static final class Result {
        public String out = "";
        public int code = Integer.MIN_VALUE;
        public String error = "";
    }

    public static Result runFull(MinecraftServer server, String command) {
        Capture cap = new Capture();
        Result r = new Result();
        CommandSourceStack src = new CommandSourceStack(cap, Vec3.ZERO, Vec2.ZERO, server.overworld(), 4, "GTS", Component.literal("GTS"), server, null);
        try {
            r.code = server.getCommands().getDispatcher().execute(command, src);
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
            GtsMod.LOG.warn("[GTS] Economy command '{}' failed: {}", command, t.toString());
        }
        r.out = cap.out.toString();
        return r;
    }

    private static String run(MinecraftServer server, String command) {
        return runFull(server, command).out;
    }

    // ------------------------------------------------------------------ direct CobbleDollars calls
    // CobbleDollars 2.x keeps each player's exact balance as a BigInteger and exposes it through
    // PlayerExtensionKt.getCobbleDollars / setCobbleDollars. Its own /cd give and /cd remove commands use the same two calls.

    private static java.lang.reflect.Method getM, setM;
    private static boolean failedOnce;

    private static boolean init() {
        if (getM != null && setM != null) return true;
        try {
            Class<?> ext = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
            getM = ext.getMethod("getCobbleDollars", net.minecraft.world.entity.player.Player.class);
            setM = ext.getMethod("setCobbleDollars", net.minecraft.world.entity.player.Player.class, java.math.BigInteger.class);
            return true;
        } catch (Throwable t) {
            if (!failedOnce) { failedOnce = true; GtsMod.LOG.error("[GTS] Could not connect to CobbleDollars (is it installed, and version 2.x?)", t); }
            return false;
        }
    }

    private static java.math.BigInteger raw(ServerPlayer p) throws Exception {
        return (java.math.BigInteger) getM.invoke(null, p);
    }

    /** The player's exact CobbleDollars, or -1 if it can't be read. */
    public static long balance(ServerPlayer p) {
        if (!init()) return -1;
        try {
            java.math.BigInteger b = raw(p);
            return b.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not read a CobbleDollars balance", t);
            return -1;
        }
    }

    public static boolean give(ServerPlayer p, long amount) {
        if (amount <= 0) return true;
        if (!init()) return false;
        try {
            setM.invoke(null, p, raw(p).add(java.math.BigInteger.valueOf(amount)));
            return true;
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not give CobbleDollars", t);
            return false;
        }
    }

    /** Takes money, only if the player really has it. */
    public static boolean take(ServerPlayer p, long amount) {
        if (amount <= 0) return true;
        if (!init()) return false;
        try {
            java.math.BigInteger have = raw(p);
            java.math.BigInteger cost = java.math.BigInteger.valueOf(amount);
            if (have.compareTo(cost) < 0) return false;
            setM.invoke(null, p, have.subtract(cost));
            return true;
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not take CobbleDollars", t);
            return false;
        }
    }
}
