package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MarketData {
    public Map<String, Listing> listings = new LinkedHashMap<>();
    /** Player UUID (as text) to the things waiting for them. */
    public Map<String, List<ClaimEntry>> claims = new LinkedHashMap<>();
    public String boardMessageId;

    public List<ClaimEntry> claimsOf(java.util.UUID id) {
        return claims.computeIfAbsent(id.toString(), k -> new ArrayList<>());
    }
}
