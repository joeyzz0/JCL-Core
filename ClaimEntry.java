package net.joeyscobblemon.gts;

/** Something waiting for a player in /gts claims. */
public class ClaimEntry {
    public enum Type { ITEM, POKEMON, MONEY }

    public Type type;
    public String itemSnbt;     // ITEM: the stack. POKEMON: the icon.
    public String pokemonSnbt;  // POKEMON only
    public String title;
    public long amount;         // MONEY only
    public String reason;
    public long createdAt = System.currentTimeMillis();
}
