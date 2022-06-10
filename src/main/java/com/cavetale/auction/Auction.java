package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.font.Unicode;
import com.cavetale.core.font.VanillaEffects;
import com.cavetale.core.font.VanillaItems;
import com.cavetale.core.item.ItemKinds;
import com.cavetale.core.money.Money;
import com.cavetale.core.perm.Perm;
import com.cavetale.mytems.Mytems;
import com.cavetale.mytems.item.coin.Coin;
import com.cavetale.mytems.util.Items;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.event.ClickEvent.suggestCommand;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@Getter @RequiredArgsConstructor
public final class Auction {
    public static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#.##");
    private final AuctionPlugin plugin;
    private final int id;
    @Setter private SQLAuction auctionRow;
    private Inventory inventory;
    private Map<ItemStack, Integer> itemMap = Map.of(); // single item display
    private List<ItemStack> items = List.of(); // ???
    private List<ItemStack> allItems = List.of(); // tooltip
    private int totalItemCount;
    private final Map<UUID, SQLPlayerAuction> players = new HashMap<>();
    private boolean loading;

    public boolean isReady() {
        return auctionRow != null;
    }

    public void test(Player player) {
        this.auctionRow = new SQLAuction(player.getUniqueId(), 100.0, player.getInventory(), Duration.ofMinutes(10L));
        Instant now = Instant.now();
        auctionRow.setState(AuctionState.ACTIVE);
        auctionRow.setStartTime(Date.from(now));
        auctionRow.setEndTime(Date.from(now.plus(Duration.ofSeconds(auctionRow.getFullDuration()))));
        this.inventory = auctionRow.parseInventory();
        computeItems();
        player.sendMessage(getAnnouncementMessage());
    }

    protected String debug() {
        return "active=" + isActive()
            + " loading=" + loading
            + " auctionRow=" + (auctionRow != null)
            + " items=" + (!items.isEmpty())
            + " state=" + (auctionRow != null ? auctionRow.getState() : "")
            + " remain=" + (auctionRow != null ? "" + !auctionRow.getRemainingDuration().isNegative() : "");
    }

    public boolean isActive() {
        return !loading
            && auctionRow != null
            && inventory != null
            && !items.isEmpty()
            && auctionRow.getState().isActive()
            && !auctionRow.getRemainingDuration().isNegative();
    }

    public boolean hasEnded() {
        return !loading && auctionRow != null && auctionRow.getState().isEnded();
    }

    public void load() {
        if (loading) return;
        loading = true;
        plugin.database.find(SQLAuction.class)
            .idEq(id)
            .findUniqueAsync(row -> {
                    this.auctionRow = row;
                    this.inventory = row.parseInventory();
                    computeItems();
                });
        plugin.database.find(SQLPlayerAuction.class)
            .eq("auctionId", id)
            .findListAsync(rows -> {
                    loading = false;
                    players.clear();
                    for (SQLPlayerAuction row : rows) {
                        players.put(row.getPlayer(), row);
                    }
                });
    }

    public ListenType getListenType(UUID uuid) {
        SQLPlayerAuction value = players.get(uuid);
        return value != null
            ? value.getListenType()
            : ListenType.DEFAULT;
    }

    private void addItemMap(ItemStack item) {
        for (ItemStack old : List.copyOf(itemMap.keySet())) {
            if (ItemKinds.isSimilar(old, item)) {
                itemMap.put(old, itemMap.getOrDefault(old, 0) + item.getAmount());
                return;
            }
        }
        itemMap.put(item, item.getAmount());
    }

    private void computeItems() {
        if (inventory == null) {
            itemMap = Map.of();
            items = List.of();
            allItems = List.of();
            return;
        }
        itemMap = new IdentityHashMap<>();
        items = new ArrayList<>();
        allItems = new ArrayList<>();
        for (ItemStack item : inventory) {
            if (item == null || item.getType().isAir()) continue;
            items.add(item);
            allItems.add(item);
            addItemMap(item);
            totalItemCount += item.getAmount();
            if (item.hasItemMeta()
                && item.getItemMeta() instanceof BlockStateMeta meta
                && meta.hasBlockState()
                && meta.getBlockState() instanceof Container container) {
                for (ItemStack item2 : container.getInventory()) {
                    if (item == null || item.getType().isAir()) continue;
                    allItems.add(item2);
                    totalItemCount += item2.getAmount();
                }
            }
        }
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy MMM dd HH:mm:ss");

    protected static Component format(Duration time) {
        final long seconds = time.toSeconds() % 60L;
        final long minutes = time.toMinutes() % 60L;
        final long hours = time.toHours() % 24L;
        final long days = time.toDays() % 24;
        List<Component> list = new ArrayList<>(8);
        if (days > 0) {
            list.add(text(days));
            list.add(text(Unicode.SMALLD.character, GRAY));
        }
        list.add(text(hours));
        list.add(text(Unicode.SMALLH.character, GRAY));
        list.add(text(minutes));
        list.add(text(Unicode.SMALLM.character, GRAY));
        list.add(text(seconds));
        list.add(text(Unicode.SMALLS.character, GRAY));
        Component title = join(noSeparators(), list).color(YELLOW);
        Date then = Date.from(Instant.now().plus(time));
        return title
            .hoverEvent(showText(join(separator(newline()),
                                      title,
                                      text(DATE_FORMAT.format(then), GRAY),
                                      join(noSeparators(), text(days, YELLOW), text(" Days", GRAY)),
                                      join(noSeparators(), text(hours, YELLOW), text(" Hours", GRAY)),
                                      join(noSeparators(), text(minutes, YELLOW), text(" Minutes", GRAY)),
                                      join(noSeparators(), text(seconds, YELLOW), text(" Seconds", GRAY)))));
    }

    public Component getAuctionTag() {
        return text(tiny("Auction"), DARK_AQUA)
            .hoverEvent(showText(text("/auc", GREEN)))
            .clickEvent(runCommand("/auc"));
    }

    public Component getBidTag() {
        String cmd = "/bid " + id + " " + MONEY_FORMAT.format(auctionRow.getCurrentPrice());
        return Mytems.PLUS_BUTTON.component
            .clickEvent(suggestCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GREEN),
                                      text("Bid on this auction", GRAY),
                                      text(tiny("Bid amounts are hidden."), DARK_GRAY),
                                      text(tiny("The winning bid is just"), DARK_GRAY),
                                      text(tiny("enough to match any"), DARK_GRAY),
                                      text(tiny("other bid."), DARK_GRAY))));
    }

    public Component getIgnoreTag() {
        String cmd = "/auc ignore " + id;
        return VanillaEffects.BLINDNESS.component
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GREEN),
                                      text("Ignore this auction", GRAY),
                                      text(tiny("You will stop receiving"), DARK_GRAY),
                                      text(tiny("further updates on this"), DARK_GRAY),
                                      text(tiny("item."), DARK_GRAY))));
    }

    public Component getFocusTag() {
        String cmd = "/auc focus " + id;
        return VanillaItems.SPYGLASS.component
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GREEN),
                                      text("Focus this auction", GRAY),
                                      text(tiny("This auction will be"), DARK_GRAY),
                                      text(tiny("prioritized and you"), DARK_GRAY),
                                      text(tiny("receive instant"), DARK_GRAY),
                                      text(tiny("notifications."), DARK_GRAY))));
    }

    public Component getItemTag() {
        final Component itemComponent;
        if (itemMap.size() == 1) {
            ItemStack theItem = itemMap.keySet().iterator().next();
            int count = itemMap.getOrDefault(theItem, 1);
            itemComponent = ItemKinds.chatDescription(theItem, count);
        } else {
            ItemStack hoverItem = new ItemStack(Material.BUNDLE);
            Component title = join(noSeparators(),
                                   text(totalItemCount, YELLOW),
                                   VanillaItems.BUNDLE,
                                   text("Items", WHITE));
            hoverItem.editMeta(m -> {
                    if (m instanceof BundleMeta meta) {
                        meta.setItems(allItems);
                    }
                    m.addItemFlags(ItemFlag.values());
                    Items.text(m, List.of(title));
                });
            itemComponent = title
                .hoverEvent(hoverItem.asHoverEvent());
        }
        return itemComponent.clickEvent(runCommand("/auc preview " + id));
    }

    public Component getAnnouncementMessage() {
        return join(noSeparators(),
                    getBidTag(),
                    getFocusTag(),
                    getIgnoreTag(),
                    space(),
                    getAuctionTag(),
                    text(tiny(" for "), DARK_GRAY),
                    getItemTag(),
                    text(tiny(" price "), DARK_GRAY),
                    Coin.format(auctionRow.getCurrentPrice()),
                    text(tiny(" time "), DARK_GRAY),
                    format(auctionRow.getRemainingDuration()));
    }

    protected void bidCommand(RemotePlayer player, double amount) {
        final double price = auctionRow.getCurrentPrice();
        if (auctionRow.hasWinner() && amount - price < 0.01) {
            throw new CommandWarn(join(noSeparators(), text("You must bid more than ", RED), Coin.format(price)));
        } else if (amount < price) {
            throw new CommandWarn(join(noSeparators(), text("You must bid at least ", RED), Coin.format(price)));
        }
        if (!Money.get().has(player.getUniqueId(), amount)) {
            throw new CommandWarn(join(noSeparators(), text("You do not have ", RED), Coin.format(amount)));
        }
        final double highest = auctionRow.getHighestBid();
        final boolean winning = !auctionRow.hasWinner() || amount - price >= 0.01;
        final boolean raising = winning || amount - price >= 0.01;
        final boolean wasWinner = auctionRow.isWinner(player.getUniqueId());
        if (wasWinner) {
            if (!winning) {
                throw new CommandWarn("You are already winning");
            }
            auctionRow.setHighestBid(amount);
            plugin.database.updateAsync(auctionRow, Set.of("highestBid"),
                                        r -> postBid(player, amount, BidType.SILENT, r));
        } else {
            if (winning) {
                final double newPrice = Math.max(price, auctionRow.getCurrentBid());
                auctionRow.setCurrentBid(newPrice);
                auctionRow.setCurrentPrice(newPrice);
                auctionRow.setHighestBid(amount);
                auctionRow.setWinner(player.getUniqueId());
                plugin.database.updateAsync(auctionRow, Set.of("currentBid", "currentPrice", "highestBid", "winner"),
                                            r -> postBid(player, amount, BidType.WINNER, r));
            } else if (raising) {
                auctionRow.setCurrentBid(amount);
                auctionRow.setCurrentPrice(amount);
                plugin.database.updateAsync(auctionRow, Set.of("currentBid", "currentPrice"),
                                            r -> postBid(player, amount, BidType.RAISE, r));
            }
        }
    }

    private void postBid(RemotePlayer player, double amount, BidType type, int saveResult) {
        if (saveResult == 0) {
            plugin.getLogger().severe("Save failed: " + auctionRow);
            return;
        }
        LogType.BID.log(auctionRow, player.getUniqueId(), amount);
        Connect.get().broadcastMessage(Auctions.CONNECT_REFRESH, "" + id);
    }

    protected void managerTick() {
        Duration duration = auctionRow.getRemainingDuration();
        if (duration.isNegative()) {
            end();
            return;
        }
    }

    public void start() {
        Instant now = Instant.now();
        auctionRow.setState(AuctionState.ACTIVE);
        auctionRow.setStartTime(Date.from(now));
        auctionRow.setEndTime(Date.from(now.plus(Duration.ofSeconds(auctionRow.getFullDuration()))));
        plugin.database.updateAsync(auctionRow, Set.of("state", "startTime", "endTime"), res -> {
                if (res == 0) {
                    plugin.getLogger().severe("res == 0");
                    return;
                }
                Connect.get().broadcastMessage(Auctions.CONNECT_REFRESH, "" + id);
                plugin.database.find(SQLPlayerAuction.class)
                    .eq("auctionId", id)
                    .findListAsync(rows -> {
                            players.clear();
                            for (SQLPlayerAuction row : rows) {
                                players.put(row.getPlayer(), row);
                            }
                            announce(ListenType.DEFAULT, getAnnouncementMessage());
                        });
            });
        inventory = auctionRow.parseInventory();
        computeItems();
    }

    public void end() {
        auctionRow.setState(AuctionState.ENDED);
        auctionRow.setExclusive(false);
        plugin.database.updateAsync(auctionRow, Set.of("state", "exclusive"), res -> {
                if (res == 0) return;
                Connect.get().broadcastMessage(Auctions.CONNECT_REFRESH, "" + id);
            });
    }

    protected void announce(ListenType listenType, Component message) {
        for (RemotePlayer player : Connect.get().getRemotePlayers()) {
            if (!player.getOriginServerName().equals("alpha") && !player.getOriginServerName().equals("beta")) {
                continue; // debug
            }
            if (!getListenType(player.getUniqueId()).doesEntail(listenType)) {
                continue;
            }
            if (!Perm.get().has(player.getUniqueId(), "auction.auction")) {
                continue;
            }
            player.sendMessage(message);
        }
    }

    // protected void announce(ListenType listenType, Function<UUID, Component> func) {
    //     for (RemotePlayer player : Connect.get().getRemotePlayers()) {
    //         if (getListenType(player.getUniqueId()).doesEntail(listenType)) {
    //             player.sendMessage(func.apply(player.getUniqueId()));
    //         }
    //     }
    // }
}
