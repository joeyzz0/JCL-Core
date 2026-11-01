package net.joeyscobblemon.gts;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Text and number helpers. */
public final class Fmt {
    private Fmt() {}

    public static String money(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    public static String cd(long v) {
        return money(v) + " CD";
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** A non-italic line (item lore and names default to italic, which looks wrong in menus). */
    public static MutableComponent line(String text, ChatFormatting... formats) {
        MutableComponent c = Component.literal(text).withStyle(s -> s.withItalic(false));
        if (formats.length > 0) c = c.withStyle(formats);
        return c;
    }

    public static MutableComponent chat(String text, ChatFormatting... formats) {
        MutableComponent c = Component.literal(text);
        if (formats.length > 0) c = c.withStyle(formats);
        return c;
    }

    public static String pretty(String raw) {
        if (raw == null) return "";
        String s = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        StringBuilder sb = new StringBuilder();
        for (String w : s.replace('_', ' ').replace('-', ' ').split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
