package com.cavetale.auction.sql;

import com.cavetale.auction.ListenType;
import com.winthier.sql.SQLRow.Id;
import com.winthier.sql.SQLRow.Name;
import com.winthier.sql.SQLRow.NotNull;
import com.winthier.sql.SQLRow.UniqueKey;
import com.winthier.sql.SQLRow;
import java.util.Date;
import java.util.UUID;
import lombok.Data;

@Data @NotNull @Name("player_auctions")
@UniqueKey({"auctionId", "player"})
public final class SQLPlayerAuction implements SQLRow {
    @Id private Integer id;
    private int auctionId;
    private UUID player;
    private ListenType listenType;
    private Date creationTime;

    public SQLPlayerAuction() { }

    public SQLPlayerAuction(final SQLAuction auction, final UUID player, final ListenType listenType) {
        this.auctionId = auction.getId();
        this.player = player;
        this.listenType = listenType;
        this.creationTime = new Date();
    }
}
