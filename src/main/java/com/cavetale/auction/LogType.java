package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLLog;
import java.util.UUID;
import static com.cavetale.auction.AuctionPlugin.auctionPlugin;

public enum LogType {
    CREATE,
    BID,
    START,
    END,
    VICTORY;

    protected void log(SQLAuction auc, UUID uuid, double amount) {
        SQLLog log = new SQLLog(auc, this, uuid, amount);
        auctionPlugin().getDatabase().insertAsync(log, null);
    }
}
