package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Infinite Mode leaderboards: floating boards in the Frontier lobby, plus /frontier leaderboard in chat. */
final class FrontierBoards {
    private FrontierBoards() {}

    static String title(String board) {
        String[] k = board.split(":");
        if (k.length < 2) return board;
        return FrontierData.bracket(k[0]).name + " · " + FrontierData.format(k[1]).name;
    }

    static List<Map.Entry<String, Integer>> top(String board, boolean season, int n) {
        var src = (season ? FrontierData.data.season : FrontierData.data.best).getOrDefault(board, Map.of());
        List<Map.Entry<String, Integer>> list = new ArrayList<>(src.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }

    static String name(String uuid) { return FrontierData.data.names.getOrDefault(uuid, "?"); }

    static void place(String board, ServerPlayer p) {
        var sp = new FrontierData.Spot();
        sp.dim = p.level().dimension().location().toString(); sp.x = p.getX(); sp.y = p.getY() + 2.6; sp.z = p.getZ();
        FrontierData.data.boards.put(board, sp);
        FrontierData.save();
        refresh(board);
    }

    static void remove(String board) {
        var sp = FrontierData.data.boards.remove(board);
        FrontierData.save();
        if (sp != null) { ServerLevel l = Cob.level(Frontier.server, sp.dim); if (l != null) Holo.remove(l, tag(board)); }
    }

    static String tag(String board) { return "jcl_fb_" + board.replace(":", "_"); }

    static void refreshAll() { for (String b : new ArrayList<>(FrontierData.data.boards.keySet())) refresh(b); }

    static void refresh(String board) {
        var sp = FrontierData.data.boards.get(board);
        if (sp == null || Frontier.server == null) return;
        ServerLevel l = Cob.level(Frontier.server, sp.dim);
        if (l == null) return;
        // only rebuild while the board's area is loaded: removing text in unloaded chunks misses it, which is what duplicated boards
        if (!l.isLoaded(net.minecraft.core.BlockPos.containing(sp.x, sp.y, sp.z))) return;
        Holo.remove(l, tag(board));
        double y = sp.y;
        Holo.spawn(l, sp.x, y, sp.z, Component.literal("§b§l✦ Infinite: " + title(board) + " ✦"), tag(board), 1.15f, true);
        y -= 0.34;
        Holo.spawn(l, sp.x, y, sp.z, Component.literal("§7Most rooms cleared this season"), tag(board), 0.8f, true);
        List<Map.Entry<String, Integer>> list = top(board, true, 10);
        if (list.isEmpty()) { Holo.spawn(l, sp.x, y - 0.4, sp.z, Component.literal("§8No runs yet. Be the first!"), tag(board), 0.95f, true); return; }
        String[] medal = {"§6§l1.", "§f§l2.", "§c§l3."};
        for (int i = 0; i < list.size(); i++) {
            y -= 0.29;
            String rank = i < 3 ? medal[i] : "§7" + (i + 1) + ".";
            Holo.spawn(l, sp.x, y, sp.z, Component.literal(rank + " §f" + name(list.get(i).getKey()) + " §8— §e" + list.get(i).getValue() + " rooms"), tag(board), 0.95f, true);
        }
    }

    static void chat(ServerPlayer p, String board) {
        List<String> boards = new ArrayList<>();
        if (board != null) boards.add(board);
        else boards.addAll(FrontierData.data.season.keySet());
        if (boards.isEmpty()) { Frontier.msg(p, "§7No Infinite runs this season yet."); return; }
        for (String b : boards) {
            p.sendSystemMessage(Component.literal("§b§l✦ " + title(b) + " §7(this season)"));
            var list = top(b, true, 5);
            if (list.isEmpty()) p.sendSystemMessage(Component.literal(" §8No runs yet."));
            for (int i = 0; i < list.size(); i++) p.sendSystemMessage(Component.literal(" §7" + (i + 1) + ". §f" + name(list.get(i).getKey()) + " §8— §e" + list.get(i).getValue()));
        }
    }
}
