package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLLog;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.cavetale.core.command.AbstractCommand;
import com.cavetale.core.command.CommandArgCompleter;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.connect.ServerGroup;
import com.cavetale.core.playercache.PlayerCache;
import com.cavetale.mytems.util.Gui;
import java.time.Duration;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.textOfChildren;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.ClickEvent.suggestCommand;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.*;

public final class AuctionAdminCommand extends AbstractCommand<AuctionPlugin> {
    protected AuctionAdminCommand(final AuctionPlugin plugin) {
        super(plugin, "auctionadmin");
    }

    @Override
    protected void onEnable() {
        rootNode.addChild("debug").denyTabCompletion()
            .description("Dump auctions debug")
            .senderCaller(this::debug);
        rootNode.addChild("hist").denyTabCompletion()
            .description("List auction history")
            .senderCaller(this::hist);
        rootNode.addChild("info").arguments("<id>")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("View auction info")
            .senderCaller(this::info);
        rootNode.addChild("players").arguments("[id]")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("View player auction info")
            .playerCaller(this::players);
        rootNode.addChild("log").arguments("<id>")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("View auction logs")
            .senderCaller(this::auctionLog);
        rootNode.addChild("deliveries")
            .description("List deliveries")
            .senderCaller(this::deliveryList);
        rootNode.addChild("cancel").arguments("<id>")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("Cancel an auction")
            .senderCaller(this::cancel);
        rootNode.addChild("bankauction").arguments("<price> <minutes>")
            .description("Start a bank auction")
            .completers(CommandArgCompleter.integer(i -> i >= 0),
                        CommandArgCompleter.integer(i -> i > 0))
            .playerCaller(this::bankAuction);
        rootNode.addChild("delete").arguments("<id>")
            .description("Delete an auction")
            .completers(CommandArgCompleter.integer(i -> i > 0))
            .senderCaller(this::delete);
        rootNode.addChild("open").arguments("<id>")
            .description("Open auction inventory")
            .completers(CommandArgCompleter.integer(i -> i > 0))
            .playerCaller(this::open);
    }

    private void debug(CommandSender sender) {
        plugin.auctions.debug(sender);
    }

    private void hist(CommandSender sender) {
        plugin.database.find(SQLAuction.class)
            .orderByAscending("createdTime")
            .findListAsync(rows -> {
                    Component colon = text(":", GRAY);
                    for (SQLAuction row : rows) {
                        sender.sendMessage(textOfChildren(text("#" + row.getId(), YELLOW),
                                                          space(),
                                                          text("created"), colon, text(Format.BRIEF_DATE_FORMAT.format(row.getCreatedTime()), YELLOW),
                                                          space(),
                                                          text("state"), colon, text(row.getState().name().toLowerCase(), YELLOW),
                                                          space(),
                                                          text("owner"), colon, text(row.getOwnerName(), YELLOW),
                                                          space(),
                                                          text("price"), colon, text(Auction.MONEY_FORMAT.format(row.getCurrentPrice()), YELLOW),
                                                          space(),
                                                          text("winner"), colon, text(row.getWinnerName()))
                                           .clickEvent(suggestCommand("/aucadm info " + row.getId()))
                                           .hoverEvent(showText(text("/aucadm info " + row.getId(), YELLOW))));
                    }
                });
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        SQLAuction row = plugin.database.find(SQLAuction.class).idEq(id).findUnique();
        if (row == null) {
            throw new CommandWarn("Auction not found: " + id);
        }
        sender.sendMessage(textOfChildren(text("id ", AQUA), text(row.getId(), YELLOW)));
        sender.sendMessage(textOfChildren(text("owner ", AQUA), text(row.getOwnerName(), YELLOW)));
        sender.sendMessage(textOfChildren(text("winner ", AQUA), text(row.getWinnerName(), YELLOW)));
        sender.sendMessage(textOfChildren(text("state ", AQUA), text(row.getState().name(), YELLOW)));
        sender.sendMessage(textOfChildren(text("bid ", AQUA),
                                          text(Auction.MONEY_FORMAT.format(row.getCurrentBid()), YELLOW)));
        sender.sendMessage(textOfChildren(text("price ", AQUA),
                                          text(Auction.MONEY_FORMAT.format(row.getCurrentPrice()), YELLOW)));
        sender.sendMessage(textOfChildren(text("highest ", AQUA),
                                          text(Auction.MONEY_FORMAT.format(row.getHighestBid()), YELLOW)));
        sender.sendMessage(textOfChildren(text("auctionFee ", AQUA),
                                          text(Auction.MONEY_FORMAT.format(row.getAuctionFee()), YELLOW)));
        sender.sendMessage(textOfChildren(text("created ", AQUA), text(Format.date(row.getCreatedTime()), YELLOW)));
        sender.sendMessage(textOfChildren(text("duration ", AQUA), text(row.getFullDuration(), YELLOW)));
        sender.sendMessage(textOfChildren(text("start ", AQUA), text(Format.date(row.getStartTime()), YELLOW)));
        sender.sendMessage(textOfChildren(text("end ", AQUA), text(Format.date(row.getEndTime()), YELLOW)));
        sender.sendMessage(textOfChildren(text("announced ", AQUA), text(Format.date(row.getAnnouncedTime()), YELLOW)));
        Auction auction = new Auction(plugin, row);
        auction.computeItems();
        sender.sendMessage(textOfChildren(text("chat-item ", AQUA), auction.getChatItemTag()));
        sender.sendMessage(textOfChildren(text("bundle-item ", AQUA), auction.bundleIconTag()));
        return true;
    }

    private boolean players(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        final int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        final List<SQLPlayerAuction> rows = plugin.database.find(SQLPlayerAuction.class).eq("auctionId", id).findList();
        if (rows.isEmpty()) {
            throw new CommandWarn("No players found for auction #" + id);
        }
        sender.sendMessage(text(rows.size() + " players for auction #" + id, YELLOW));
        for (SQLPlayerAuction row : rows) {
            sender.sendMessage(textOfChildren(text(" #", GRAY), text(row.getId(), WHITE),
                                              text(" " + PlayerCache.nameForUuid(row.getPlayer()), YELLOW),
                                              text(" bid:", GRAY), text(Auction.MONEY_FORMAT.format(row.getBid()), WHITE),
                                              text(" listen:", GRAY), text(row.getListenType().name().toLowerCase(), WHITE)));
        }
        return true;
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
        Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_REFRESH, "" + id);
        sender.sendMessage(text("Auction cancelled: " + id, AQUA));
        return true;
    }

    private boolean bankAuction(Player player, String[] args) {
        if (args.length != 2) return false;
        int price = CommandArgCompleter.requireInt(args[0], i -> i >= 0);
        int minutes = CommandArgCompleter.requireInt(args[1], i -> i > 0);
        Gui gui = new Gui(plugin)
            .size(6 * 9)
            .title(text("Bank Auction", DARK_RED));
        gui.setEditable(true);
        gui.onClose(evt -> {
                if (gui.getInventory().isEmpty()) return;
                SQLAuction auction = new SQLAuction(SQLAuction.SERVER_UUID, (double) price, gui.getInventory(), Duration.ofMinutes(minutes));
                List<Integer> ids = plugin.database.find(SQLAuction.class).findValues("id", Integer.class);
                for (int i = 1;; i += 1) {
                    if (ids.contains(i)) continue;
                    auction.setId(i);
                    int res = plugin.database.insertIgnore(auction);
                    if (res != 0) break;
                }
                LogType.CREATE.log(auction, player.getUniqueId(), (double) price);
                Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_SCHEDULED, "");
                player.sendMessage(text("Auction scheduled: " + auction.getId(), AQUA));
            });
        gui.open(player);
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        plugin.auctions.deleteAuction(id, (int[] r) -> {
                if (r[0] == 0 && r[1] == 0 && r[2] == 0) {
                    sender.sendMessage(text("Nothing was deleted!", RED));
                } else {
                    sender.sendMessage(text("Auction deleted:"
                                            + " auctions:" + r[0]
                                            + " logs:" + r[1]
                                            + " players:" + r[2],
                                            AQUA));
                }
            });
        return true;
    }

    private boolean open(Player player, String[] args) {
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        SQLAuction row = plugin.database.find(SQLAuction.class).idEq(id).findUnique();
        if (row == null) {
            throw new CommandWarn("Auction not found: " + id);
        }
        player.openInventory(row.parseInventory());
        player.sendMessage(textOfChildren(text("Opened auction #" + row.getId(), AQUA)));
        return true;
    }
}
