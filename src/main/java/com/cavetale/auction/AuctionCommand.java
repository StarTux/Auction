package com.cavetale.auction;

import com.cavetale.auction.gui.Gui;
import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.core.command.AbstractCommand;
import com.cavetale.core.command.CommandArgCompleter;
import com.cavetale.core.command.CommandNode;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.connect.NetworkServer;
import com.cavetale.core.money.Money;
import com.cavetale.inventory.mail.ItemMail;
import com.cavetale.mytems.item.coin.Coin;
import java.util.ArrayList;
import java.util.List;
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
            .completers(CommandArgCompleter.integer(i -> i > 0))
            .playerCaller(this::preview);
        bidNode = rootNode.addChild("bid").arguments("[id] <amount>")
            .description("Place a bid")
            .completers(CommandArgCompleter.integer(i -> i > 0),
                        CommandArgCompleter.integer(i -> i > 0))
            .remoteServer(NetworkServer.manager())
            .remotePlayerCaller(this::bid);
        rootNode.addChild("start").denyTabCompletion()
            .description("Start an auction")
            .playerCaller(this::start);
        rootNode.addChild("startprice").denyTabCompletion().hidden(true)
            .playerCaller(this::startPrice);
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

    protected boolean bid(RemotePlayer player, String[] args) {
        if (args.length > 2) return false;
        final Auction auction;
        final double amount;
        if (args.length == 2) {
            int id = CommandArgCompleter.requireInt(args[0], i -> i > 0);
            amount = (double) CommandArgCompleter.requireInt(args[1], i -> i > 0);
            auction = plugin.auctions.getActiveAuction(id);
            if (auction == null) {
                throw new CommandWarn("Auction not found: " + id);
            }
        } else {
            amount = (double) CommandArgCompleter.requireInt(args[0], i -> i > 0);
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
            lines.add(it.toComponent());
        }
        book.editMeta(m -> {
                if (m instanceof BookMeta meta) {
                    meta.setAuthor("cavetale");
                    meta.setTitle("auction");
                    meta.pages(List.of(join(separator(newline()), lines)));
                }
            });
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
        if (!Money.get().take(player.getUniqueId(), price.price)) {
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
                Connect.get().broadcastMessageToAll(Auctions.CONNECT_SCHEDULED, "");
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
}
