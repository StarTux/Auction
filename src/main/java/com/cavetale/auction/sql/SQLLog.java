package com.cavetale.auction.sql;

import com.cavetale.auction.LogType;
import com.winthier.sql.SQLRow.Id;
import com.winthier.sql.SQLRow.Name;
import com.winthier.sql.SQLRow.NotNull;
import com.winthier.sql.SQLRow;
import java.util.Date;
import java.util.UUID;
import lombok.Data;

@Data @NotNull @Name("logs")
public final class SQLLog implements SQLRow {
    @Id private Integer id;
    private int auctionId;
    private Date time;
    private LogType type;
    private UUID player;
    private double money;

    public SQLLog() { }

    public SQLLog(final SQLAuction auction, final LogType type, final UUID player, final double money) {
        this.auctionId = auction.getId();
        this.type = type;
        this.time = new Date();
        this.player = player;
        this.money = money;
    }
}
