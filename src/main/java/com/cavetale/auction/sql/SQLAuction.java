package com.cavetale.auction.sql;

import com.cavetale.auction.AuctionState;
import com.cavetale.core.playercache.PlayerCache;
import com.cavetale.core.util.Json;
import com.cavetale.inventory.storage.InventoryStorage;
import com.winthier.sql.SQLRow;
import com.winthier.sql.SQLRow.Id;
import com.winthier.sql.SQLRow.Key;
import com.winthier.sql.SQLRow.Name;
import com.winthier.sql.SQLRow.NotNull;
import com.winthier.sql.SQLRow.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lombok.Data;
import org.bukkit.inventory.Inventory;

@Data @NotNull @Name("auctions")
@Key({"owner", "createdTime"})
@Key({"state", "endTime"})
@Key({"exclusive", "owner"})
public final class SQLAuction implements SQLRow {
    public static final UUID SERVER_UUID = new UUID(0L, 0L);
    @Id private Integer id;
    private UUID owner;
    @Nullable private UUID winner;
    private AuctionState state;
    /**
     * This flag indicates that the same owner cannot start a second
     * auction.
     */
    private boolean exclusive;
    private double currentBid; // secret
    private double currentPrice; // shown
    private double highestBid; // secret
    private double auctionFee;
    @SQLRow.LongText private String inventory;
    private Date createdTime;
    private long fullDuration;
    private Date startTime;
    private Date endTime;
    private Date announcedTime;

    public SQLAuction() { }

    public SQLAuction(final UUID owner, final double startingBid, final Inventory inventory, final Duration duration) {
        this.owner = owner;
        this.state = AuctionState.SCHEDULED;
        this.currentBid = 0.0;
        this.currentPrice = startingBid;
        this.highestBid = 0.0;
        this.inventory = Json.serialize(InventoryStorage.of(inventory));
        this.createdTime = new Date();
        this.fullDuration = duration.toSeconds();
        // These will change later!
        this.startTime = new Date(0L);
        this.endTime = new Date(0L);
        this.announcedTime = new Date(0L);
    }

    public Duration getRemainingDuration() {
        return Duration.between(Instant.now(), endTime.toInstant());
    }

    public Inventory parseInventory() {
        return Json.deserialize(inventory, InventoryStorage.class, InventoryStorage::new).toInventory();
    }

    public boolean isOwner(UUID uuid) {
        return uuid.equals(owner);
    }

    public boolean isWinner(UUID uuid) {
        return uuid.equals(winner);
    }

    public boolean hasWinner() {
        return winner != null;
    }

    public String getOwnerName() {
        if (owner == null) return "N/A";
        return SERVER_UUID.equals(owner)
            ? "The Server"
            : PlayerCache.nameForUuid(owner);
    }

    public String getWinnerName() {
        return winner != null
            ? PlayerCache.nameForUuid(winner)
            : "N/A";
    }

    public boolean isServerAuction() {
        return SERVER_UUID.equals(owner);
    }
}
