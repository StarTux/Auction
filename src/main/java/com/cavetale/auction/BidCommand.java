package com.cavetale.auction;

import com.cavetale.core.command.AbstractCommand;

public final class BidCommand extends AbstractCommand<AuctionPlugin> {
    protected BidCommand(final AuctionPlugin plugin) {
        super(plugin, "bid");
    }

    @Override
    protected void onEnable() {
        rootNode.copy(plugin.auctionCommand.bidNode);
    }
}
