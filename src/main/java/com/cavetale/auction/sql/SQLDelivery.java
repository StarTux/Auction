package com.cavetale.auction.sql;

import com.cavetale.core.util.Json;
import com.cavetale.inventory.storage.InventoryStorage;
import com.winthier.sql.SQLRow.Name;
import com.winthier.sql.SQLRow.NotNull;
import com.winthier.sql.SQLRow;
import java.util.Date;
import java.util.UUID;
import lombok.Data;
import org.bukkit.inventory.Inventory;

@Data @NotNull @Name("deliveries")
public final class SQLDelivery implements SQLRow {
    @Id private Integer id;
    private int auctionId;
    @Keyed private UUID owner;
    @Text private String inventory;
    private double debt;
    private UUID moneyRecipient;
    private Date creationTime;

    public SQLDelivery() { }

    public SQLDelivery(final SQLAuction auction, final UUID owner, final double debt) {
        this.auctionId = auction.getId();
        this.owner = owner;
        this.inventory = auction.getInventory();
        this.debt = debt;
        this.moneyRecipient = auction.getOwner();
        this.creationTime = new Date();
    }

    public boolean isRetour() {
        return owner.equals(moneyRecipient);
    }

    public Inventory parseInventory() {
        return Json.deserialize(inventory, InventoryStorage.class, InventoryStorage::new).toInventory();
    }

    public boolean hasDebt() {
        return debt >= 0.01;
    }
}
