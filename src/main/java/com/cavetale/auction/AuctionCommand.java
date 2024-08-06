package com.cavetale.auction;

import com.cavetale.auction.gui.Gui;
import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.cavetale.core.command.AbstractCommand;
import com.cavetale.core.command.CommandArgCompleter;
import com.cavetale.core.command.CommandNode;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.connect.NetworkServer;
import com.cavetale.core.connect.ServerCategory;
import com.cavetale.core.connect.ServerGroup;
import com.cavetale.core.event.item.PlayerReceiveItemsEvent;
import com.cavetale.core.font.GuiOverlay;
import com.cavetale.core.money.Money;
import com.cavetale.inventory.mail.ItemMail;
import com.cavetale.mytems.item.coin.Coin;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import static net.kyori.adventure.text.Component.empty;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class AuctionCommand extends AbstractCommand<AuctionPlugin> {
    protected CommandNode bidNode;

    protected AuctionCommand(final AuctionPlugin plugin) {
        super(plugin, "auction");
    }

    @Override
    protected void onEnable() {
        rootNode.addChild("preview").arguments("<id>")
            .description("Auction preview")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .playerCaller(this::preview);
        rootNode.addChild("list").denyTabCompletion()
            .description("List current auctions")
            .playerCaller(this::list);
        rootNode.addChild("hist").denyTabCompletion()
            .description("View auction history")
            .playerCaller(this::hist);
        rootNode.addChild("queue").denyTabCompletion()
            .description("View auction queue")
            .playerCaller(this::queue);
        rootNode.addChild("my").denyTabCompletion()
            .description("View your own auctions")
            .playerCaller(this::my);
        rootNode.addChild("info").arguments("[id]")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("View auction info")
            .playerCaller(this::info);
        rootNode.addChild("ignore").arguments("<id>")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("Ignore an auction")
            .playerCaller(this::ignore);
        rootNode.addChild("focus").arguments("<id>")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .description("Focus an auction")
            .playerCaller(this::focus);
        bidNode = rootNode.addChild("bid").arguments("[id] <amount>")
            .description("Place a bid")
            .completers(CommandArgCompleter.integer(i -> i > 0),
                        CommandArgCompleter.integer(i -> i > 0))
            .remoteServer(NetworkServer.current().getManager())
            .remotePlayerCaller(this::bid);
        rootNode.addChild("start").denyTabCompletion()
            .permission("auction.start")
            .description("Start an auction")
            .playerCaller(this::start);
        rootNode.addChild("startprice").denyTabCompletion().hidden(true)
            .playerCaller(this::startPrice);
        rootNode.addChild("pickup").denyTabCompletion()
            .permission("auction.pickup")
            .description("Pick up a delivery")
            .playerCaller(this::pickup);
        rootNode.addChild("cancel").arguments("<id>")
            .description("Cancel your auction")
            .completers(CommandArgCompleter.supplyList(plugin.auctions::complete))
            .remoteServer(NetworkServer.current().getManager())
            .remotePlayerCaller(this::cancel);
    }

    private void listAuctionsInBook(Player player, List<Auction> auctions) {
        if (auctions.isEmpty()) {
            player.sendMessage(text("No auctions to show", RED));
            return;
        }
        List<Component> pages = new ArrayList<>(auctions.size());
        for (Auction auction : auctions) {
            pages.add(join(separator(newline()), auction.getInfoLines(player.getUniqueId(), true)));
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(m -> {
                if (!(m instanceof BookMeta meta)) return;
                meta.setAuthor("cavetale");
                meta.title(text("auction"));
                meta.pages(pages);
            });
        player.closeInventory();
        player.openBook(book);
    }

    private void listAuctionRowsInBook(Player player, List<SQLAuction> rows) {
        if (rows.isEmpty()) {
            player.sendMessage(text("No auctions to show", RED));
            return;
        }
        List<Auction> auctions = new ArrayList<>(rows.size());
        for (SQLAuction row : rows) {
            Auction auction = new Auction(plugin, row);
            auction.computeItems();
            auctions.add(auction);
        }
        listAuctionsInBook(player, auctions);
    }

    private void list(Player player) {
        listAuctionsInBook(player, plugin.auctions.getActiveAuctions());
    }

    private void hist(Player player) {
        Date then = Date.from(Instant.now().minus(Duration.ofHours(24)));
        plugin.database.find(SQLAuction.class)
            .eq("state", AuctionState.ENDED)
            .gte("endTime", then)
            .orderByAscending("endTime")
            .findListAsync(rows -> listAuctionRowsInBook(player, rows));
    }

    private void queue(Player player) {
        plugin.database.find(SQLAuction.class)
            .eq("state", AuctionState.SCHEDULED)
            .orderByAscending("createdTime")
            .findListAsync(rows -> listAuctionRowsInBook(player, rows));
    }

    private void my(Player player) {
        Date then = Date.from(Instant.now().minus(Duration.ofHours(24)));
        plugin.database.find(SQLAuction.class)
            .eq("owner", player.getUniqueId())
            .orderByAscending("createdTime")
            .findListAsync(rows -> listAuctionRowsInBook(player, rows));
    }

    private void viewAuctionInBook(Player player, Auction auction) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(m -> {
                if (!(m instanceof BookMeta meta)) return;
                meta.setAuthor("cavetale");
                meta.title(text("auction"));
                meta.pages(join(separator(newline()), auction.getInfoLines(player.getUniqueId(), true)));
            });
        player.closeInventory();
        player.openBook(book);
    }

    private void viewAuctionInBook(Player player, SQLAuction row) {
        Auction auction = new Auction(plugin, row);
        auction.computeItems();
        auction.loadPlayers(() -> {
                viewAuctionInBook(player, auction);
            });
    }

    private void viewAuctionInBook(Player player, int id) {
        plugin.database.find(SQLAuction.class)
            .idEq(id)
            .findUniqueAsync(row -> {
                    if (row == null) {
                        player.sendMessage(text("Auction not found: " + id, RED));
                        return;
                    }
                    viewAuctionInBook(player, row);
                });
    }

    private boolean info(Player player, String[] args) {
        if (args.length > 1) return false;
        if (args.length == 0) {
            listAuctionsInBook(player, plugin.auctions.getPlayerAuctions(player.getUniqueId()));
            return true;
        }
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        viewAuctionInBook(player, id);
        return true;
    }

    protected boolean preview(Player player, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        plugin.database.find(SQLAuction.class)
            .idEq(id)
            .findUniqueAsync(row -> CommandNode.wrap(player, () -> {
                        if (row == null) throw new CommandWarn("Auction not found: " + id);
                        plugin.auctions.previewAuction(player, row);
                    }));
        return true;
    }

    private boolean listen(Player player, ListenType listenType, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        plugin.database.find(SQLAuction.class)
            .idEq(id)
            .findRowCountAsync(count -> {
                    if (count == 0) {
                        player.sendMessage(text("Auction not found: " + id, RED));
                        return;
                    }
                    plugin.database.find(SQLPlayerAuction.class)
                        .eq("auctionId", id)
                        .eq("player", player.getUniqueId())
                        .findUniqueAsync(playerAuction -> {
                                if (playerAuction != null) {
                                    if (playerAuction.getListenType() != listenType) {
                                        playerAuction.setListenType(listenType);
                                        plugin.database.updateAsync(playerAuction, Set.of("listenType"), r -> {
                                                Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_REFRESH, "" + id);
                                            });
                                    }
                                } else {
                                    playerAuction = new SQLPlayerAuction(id, player.getUniqueId());
                                    playerAuction.setListenType(listenType);
                                    plugin.database.insertAsync(playerAuction, r -> {
                                            Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_REFRESH, "" + id);
                                        });
                                }
                                if (listenType == ListenType.IGNORE) {
                                    player.sendMessage(text("You are now ignoring this auction", GREEN));
                                }
                                if (listenType == ListenType.FOCUS) {
                                    player.sendMessage(text("You are now focusing this auction", GREEN));
                                    viewAuctionInBook(player, id);
                                }
                            });
                });
        return true;
    }

    private boolean ignore(Player player, String[] args) {
        return listen(player, ListenType.IGNORE, args);
    }

    private boolean focus(Player player, String[] args) {
        return listen(player, ListenType.FOCUS, args);
    }

    private double requireMoney(String arg) {
        double amount;
        try {
            amount = Double.parseDouble(arg);
        } catch (NumberFormatException nfe) {
            throw new CommandWarn("Coin amount expected: " + arg);
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new CommandWarn("Coin amount expected: " + amount);
        }
        int integer = (int) Math.floor(amount * 100.0);
        return ((double) integer) / 100.0;
    }

    protected boolean bid(RemotePlayer player, String[] args) {
        if (plugin.auctions.isAwaitingDeliveries(player.getUniqueId())) {
            throw new CommandWarn("You have deliveries waiting for you!");
        }
        if (args.length == 0) return false;
        if (args.length > 2) return false;
        final Auction auction;
        final double amount;
        if (args.length == 2) {
            int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
            amount = requireMoney(args[1]);
            auction = plugin.auctions.getActiveAuction(id);
            if (auction == null) {
                throw new CommandWarn("Auction not found: " + id);
            }
        } else {
            amount = requireMoney(args[0]);
            List<Auction> aucs = plugin.auctions.getPlayerAuctions(player.getUniqueId());
            if (aucs.isEmpty()) {
                throw new CommandWarn("Auction not found");
            }
            auction = aucs.get(0);
        }
        auction.bidCommand(player, amount);
        return true;
    }

    protected void start(Player player) {
        if (!ServerCategory.current().isSurvival()) {
            throw new CommandWarn("Must be in survival mode!");
        }
        if (plugin.auctions.isAwaitingDeliveries(player.getUniqueId())) {
            throw new CommandWarn("You have deliveries waiting for you!");
        }
        assertExclusivity(player, () -> start2(player), null);
    }

    private void assertExclusivity(Player player, Runnable callback, Runnable failCallback) {
        plugin.database.find(SQLAuction.class)
            .eq("exclusive", true)
            .eq("owner", player.getUniqueId())
            .findRowCountAsync(count -> {
                    if (!player.isOnline()) return;
                    if (count != 0) {
                        player.sendMessage(text("You already have an auction!", RED));
                        if (failCallback != null) {
                            CommandNode.wrap(player, failCallback);
                        }
                        return;
                    } else {
                        CommandNode.wrap(player, callback);
                    }
                });
    }

    private void start2(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        List<Component> lines = new ArrayList<>();
        lines.add(text("Auction Duration", DARK_AQUA));
        for (AuctionPrice it : AuctionPrice.values()) {
            lines.add(empty());
            lines.add(it.toBookComponent());
        }
        book.editMeta(m -> {
                if (m instanceof BookMeta meta) {
                    meta.setAuthor("cavetale");
                    meta.title(text("auction"));
                    meta.pages(List.of(join(separator(newline()), lines)));
                }
            });
        player.closeInventory();
        player.openBook(book);
    }

    private boolean startPrice(Player player, String[] args) {
        if (args.length != 1) return true;
        AuctionPrice price;
        try {
            price = AuctionPrice.valueOf(args[0]);
        } catch (IllegalArgumentException iae) {
            return true;
        }
        assertExclusivity(player, () -> startPrice2(player, price), null);
        return true;
    }

    private void startPrice2(Player player, AuctionPrice price) {
        if (!Money.get().has(player.getUniqueId(), price.price)) {
            throw new CommandWarn(join(noSeparators(),
                                       text("You do not have ", RED),
                                       Coin.format(price.price)));
        }
        Gui gui = new Gui(plugin)
            .size(3 * 9)
            .title(text("Start a " + price.showName + " auction", DARK_AQUA));
        gui.setEditable(true);
        gui.onClose(evt -> startPriceInventory(player, price, gui.getInventory()));
        gui.open(player);
    }

    private void startPriceInventory(Player player, AuctionPrice price, Inventory inventory) {
        if (inventory.isEmpty()) return;
        if (!Money.get().take(player.getUniqueId(), price.price, plugin, "Start an auction")) {
            retour(player, inventory);
            player.sendMessage(join(noSeparators(),
                                    text("You do not have ", RED),
                                    Coin.format(price.price)));
            return;
        }
        SQLAuction auction = new SQLAuction(player.getUniqueId(), price.price, inventory, price.duration);
        auction.setExclusive(true);
        auction.setAuctionFee(price.price);
        plugin.database.insertAsync(auction, res -> {
                if (res == null) {
                    retour(player, inventory);
                    plugin.getLogger().severe("Failed to insert auction: " + auction);
                    player.sendMessage(text("Auction creation failed. Please contact an administrator", RED));
                    return;
                }
                player.sendMessage(text("Auction scheduled!", GREEN));
                Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_SCHEDULED, "");
                LogType.CREATE.log(auction, player.getUniqueId(), price.price);
            });
    }

    private void retour(Player player, Inventory inventory) {
        if (player.isOnline()) {
            for (ItemStack item : inventory) {
                if (item == null || item.getType().isAir()) continue;
                for (ItemStack drop : player.getInventory().addItem(item).values()) {
                    player.getWorld().dropItem(player.getEyeLocation(), drop).setPickupDelay(0);
                }
            }
        } else {
            ItemMail.send(player.getUniqueId(), inventory, text("Auction"));
        }
    }

    private void pickup(Player player) {
        if (!ServerCategory.current().isSurvival()) {
            throw new CommandWarn("Must be in survival mode!");
        }
        final UUID uuid = player.getUniqueId();
        plugin.database.find(SQLDelivery.class)
            .eq("owner", uuid)
            .findUniqueAsync(row -> {
                    if (row == null) {
                        player.sendMessage(text("No deliveries found", RED));
                        return;
                    }
                    if (row.getDebt() >= 0.01) {
                        if (!Money.get().take(uuid, row.getDebt(), plugin, "Auction debt #" + row.getAuctionId())) {
                            player.sendMessage(join(noSeparators(),
                                                    text("You cannot afford the debt of ", RED),
                                                    Coin.format(row.getDebt())));
                            return;
                        } else {
                            if (!row.wasServerAuction()) {
                                Money.get().give(row.getMoneyRecipient(), row.getDebt(), plugin, "Auction #" + row.getAuctionId());
                            }
                        }
                    }
                    plugin.database.deleteAsync(row, r -> {
                            if (r == 0) {
                                player.sendMessage(text("Delivery already gone", RED));
                                return;
                            }
                            Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_DELIVERED, "");
                            Inventory inv = row.parseInventory();
                            if (!player.isOnline()) {
                                retour(player, inv);
                            } else {
                                final int size = inv.getSize();
                                Gui gui = new Gui(plugin)
                                    .size(size)
                                    .title(GuiOverlay.HOLES.builder(size, DARK_AQUA)
                                           .title(text("Auction #" + row.getId(), WHITE))
                                           .build());
                                for (int i = 0; i < inv.getSize(); i += 1) {
                                    gui.setItem(i, inv.getItem(i));
                                }
                                gui.onClose(evt -> PlayerReceiveItemsEvent.receiveInventory(player, gui.getInventory()));
                                gui.setEditable(true);
                                gui.open(player);
                            }
                            LogType.DELIVERED.log(row.getAuctionId(), uuid, row.getDebt());
                        });
                });
    }

    private boolean cancel(RemotePlayer player, String[] args) {
        if (args.length != 1) return false;
        int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
        // Active Auction
        Auction auction = plugin.auctions.getActiveAuction(id);
        if (auction != null) {
            if (!auction.getAuctionRow().isOwner(player.getUniqueId())) {
                throw new CommandWarn("This auction does not belong to you");
            }
            if (auction.getAuctionRow().hasWinner()) {
                throw new CommandWarn("This auction has bids!");
            }
            auction.cancel(player.getUniqueId());
            player.sendMessage(text("Auction cancelled", GREEN));
            return true;
        }
        // Scheduled Auction
        plugin.database.find(SQLAuction.class)
            .idEq(id)
            .findUniqueAsync(row -> CommandNode.wrap(player, () -> {
                        if (row == null || !row.isOwner(player.getUniqueId()) || !row.getState().isScheduled()) {
                            throw new CommandWarn("Auction not found: " + id);
                        }
                        plugin.database.update(SQLAuction.class)
                            .row(row)
                            .atomic("state", AuctionState.CANCELLED)
                            .set("exclusive", false)
                            .async(r -> CommandNode.wrap(player, () -> {
                                        if (r == null) {
                                            throw new CommandWarn("Auction not found: " + id);
                                        }
                                        LogType.CANCEL.log(row, player.getUniqueId(), 0.0);
                                        plugin.database.insertAsync(new SQLDelivery(row, row.getOwner(), 0.0), rr -> {
                                                player.sendMessage(text("Auction cancelled", GREEN));
                                                plugin.auctions.checkDeliveries();
                                            });
                                    }));
                    }));
        return true;
    }
}
