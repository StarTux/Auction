package com.cavetale.auction.sql;

import com.cavetale.auction.LogType;
import com.winthier.sql.SQLRow;
import com.winthier.sql.SQLRow.Id;
import com.winthier.sql.SQLRow.Key;
import com.winthier.sql.SQLRow.Name;
import com.winthier.sql.SQLRow.NotNull;
import java.util.Date;
import java.util.UUID;
import lombok.Data;

@Data @NotNull @Name("logs")
@Key({"auctionId", "time"})
public final class SQLLog implements SQLRow {
    @Id private Integer id;
    private int auctionId;
    @Default("NOW()") private Date time;
    private LogType type;
    @Nullable private UUID player;
    private double money;

    public SQLLog() { }

    public SQLLog(final int auctionId, final LogType type, final UUID player, final double money) {
        this.auctionId = auctionId;
        this.type = type;
        this.time = new Date();
        this.player = player;
        this.money = money;
    }

    public SQLLog(final SQLAuction auction, final LogType type, final UUID player, final double money) {
        this(auction.getId(), type, player, money);
    }
}
