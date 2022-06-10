package com.cavetale.auction;

import com.cavetale.core.command.AbstractCommand;
import org.bukkit.command.CommandSender;

public final class AuctionAdminCommand extends AbstractCommand<AuctionPlugin> {
    protected AuctionAdminCommand(final AuctionPlugin plugin) {
        super(plugin, "auctionadmin");
    }

    @Override
    protected void onEnable() {
        rootNode.addChild("debug").denyTabCompletion()
            .senderCaller(this::debug);
    }

    private void debug(CommandSender sender) {
        plugin.auctions.debug(sender);
    }
}
