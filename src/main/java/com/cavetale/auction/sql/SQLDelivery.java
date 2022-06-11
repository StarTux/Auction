package com.cavetale.auction.sql;

import com.winthier.sql.SQLRow.Id;
import com.winthier.sql.SQLRow.Name;
import com.winthier.sql.SQLRow.NotNull;
import com.winthier.sql.SQLRow;
import java.util.UUID;
import lombok.Data;

@Data @NotNull @Name("deliveries")
public final class SQLDelivery implements SQLRow {
    @Id private Integer id;
    private int auctionId;
    @Keyed private UUID owner;
    @SQLRow.Text private String inventory;
    private double debt;
    private UUID moneyRecipient;

    public SQLDelivery() { }

    public SQLDelivery(final SQLAuction auction, final UUID owner, final double debt) {
        this.auctionId = auction.getId();
        this.owner = owner;
        this.inventory = auction.getInventory();
        this.debt = debt;
        this.moneyRecipient = auction.getOwner();
    }

    public boolean isRetour() {
        return owner.equals(moneyRecipient);
    }
}
