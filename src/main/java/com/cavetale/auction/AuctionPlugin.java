package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLLog;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.winthier.sql.SQLDatabase;
import java.util.List;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuctionPlugin extends JavaPlugin {
    private static AuctionPlugin instance;
    @Getter protected final SQLDatabase database = new SQLDatabase(this);
    protected final Auctions auctions = new Auctions(this);
    protected final AuctionAdminCommand auctionAdminCommand = new AuctionAdminCommand(this);
    protected final AuctionCommand auctionCommand = new AuctionCommand(this);
    protected final BidCommand bidCommand = new BidCommand(this);

    @Override
    public void onEnable() {
        instance = this;
        database.registerTables(List.of(SQLAuction.class,
                                        SQLPlayerAuction.class,
                                        SQLLog.class,
                                        SQLDelivery.class));
        if (!database.createAllTables()) {
            throw new IllegalStateException("Database creation failed");
        }
        auctions.enable();
        auctionAdminCommand.enable();
        auctionCommand.enable();
        bidCommand.enable();
    }

    public static AuctionPlugin auctionPlugin() {
        return instance;
    }

    public static SQLDatabase auctionDatabase() {
        return instance.database;
    }
}
