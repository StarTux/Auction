package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLLog;
import com.cavetale.core.command.AbstractCommand;
import com.cavetale.core.command.CommandArgCompleter;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.connect.Connect;
import com.winthier.playercache.PlayerCache;
import java.util.List;
import org.bukkit.command.CommandSender;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.*;

public final class AuctionAdminCommand extends AbstractCommand<AuctionPlugin> {
    protected AuctionAdminCommand(final AuctionPlugin plugin) {
        super(plugin, "auctionadmin");
    }

    @Override
    protected void onEnable() {
        rootNode.addChild("debug").denyTabCompletion()
            .senderCaller(this::debug);
        rootNode.addChild("log").arguments("<id>")
            .completers(CommandArgCompleter.integer(i -> i > 0))
            .description("View auction logs")
            .senderCaller(this::auctionLog);
        rootNode.addChild("deliveries")
            .description("List deliveries")
            .senderCaller(this::deliveryList);
        rootNode.addChild("cancel").arguments("<id>")
            .description("Cancel an auction")
            .completers(CommandArgCompleter.integer(i -> i > 0))
            .senderCaller(this::cancel);
    }

    private void debug(CommandSender sender) {
        plugin.auctions.debug(sender);
    }

    private boolean auctionLog(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        List<SQLLog> logs = plugin.database.find(SQLLog.class)
            .eq("auctionId", id)
            .orderByAscending("time")
            .findList();
        if (logs.isEmpty()) {
            throw new CommandWarn("No logs to show: " + id);
        }
        sender.sendMessage(text("Logs for auction #" + id, AQUA));
        for (SQLLog log : logs) {
            String playerName = log.getPlayer() != null
                ? PlayerCache.nameForUuid(log.getPlayer())
                : "N/A";
            sender.sendMessage(join(separator(space()),
                                    text(log.getType().name(), YELLOW),
                                    text(Format.date(log.getTime()), GRAY),
                                    text(playerName),
                                    Format.money(log.getMoney())));
        }
        return true;
    }

    private void deliveryList(CommandSender sender) {
        List<SQLDelivery> rows = plugin.database.find(SQLDelivery.class).findList();
        if (rows.isEmpty()) {
            throw new CommandWarn("No deliveries to show");
        }
        for (SQLDelivery row : rows) {
            String ownerName = row.getOwner() != null
                ? row.getOwnerName()
                : "N/A";
            String recipientName = row.getMoneyRecipient() != null
                ? row.getRecipientName()
                : "N/A";
            sender.sendMessage(join(separator(space()),
                                    text("#" + row.getId(), DARK_GRAY),
                                    text("auc=" + row.getAuctionId(), GRAY),
                                    text("owner=" + ownerName),
                                    text(Format.date(row.getCreationTime()), GRAY),
                                    text("debt=" + Auction.MONEY_FORMAT.format(row.getDebt()),
                                         row.hasDebt() ? DARK_RED : GRAY),
                                    text("rec=" + recipientName, GRAY)));
        }
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        SQLAuction auction = plugin.database.find(SQLAuction.class)
            .idEq(id).findUnique();
        if (auction == null) {
            throw new CommandWarn("Auction not found: " + id);
        }
        if (!auction.getState().isCancellable()) {
            throw new CommandWarn("Auction cannot be cancelled: " + id);
        }
        auction.setState(AuctionState.CANCELLED);
        auction.setExclusive(false);
        plugin.database.update(auction, "state", "exclusive");
        if (!auction.isServerAuction()) {
            plugin.database.insert(new SQLDelivery(auction, auction.getOwner(), 0.0));
        }
        Connect.get().broadcastMessageToAll(Auctions.CONNECT_REFRESH, "" + id);
        sender.sendMessage(text("Auction cancelled: " + id, AQUA));
        return true;
    }
}
