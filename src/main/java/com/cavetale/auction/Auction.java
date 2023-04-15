package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.connect.ServerGroup;
import com.cavetale.core.font.Unicode;
import com.cavetale.core.font.VanillaItems;
import com.cavetale.core.item.ItemKinds;
import com.cavetale.core.money.Money;
import com.cavetale.core.perm.Perm;
import com.cavetale.mytems.Mytems;
import com.cavetale.mytems.item.coin.Coin;
import java.text.DecimalFormat;
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
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import static com.cavetale.core.font.Unicode.subscript;
import static com.cavetale.core.font.Unicode.superscript;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.empty;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.textOfChildren;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.event.ClickEvent.suggestCommand;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;

/**
 * Hold all runtime information of one auction.
 * Several methods shall only be used by the manager server.
 */
@Getter @RequiredArgsConstructor
public final class Auction {
    public static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#.##");
    private final AuctionPlugin plugin;
    private final int id;
    private SQLAuction auctionRow;
    private Inventory inventory;
    private Map<ItemStack, Integer> itemMap = Map.of(); // single item display
    private List<ItemStack> items = List.of();
    private int totalItemCount;
    private final Map<UUID, SQLPlayerAuction> players = new HashMap<>();
    private boolean loading;

    public Auction(final AuctionPlugin plugin, final SQLAuction row) {
        this(plugin, row.getId());
        this.auctionRow = row;
    }

    public boolean isReady() {
        return auctionRow != null;
    }

    public void test(Player player) {
        this.auctionRow = new SQLAuction(player.getUniqueId(), 100.0, player.getInventory(), Duration.ofMinutes(10L));
        Instant now = Instant.now();
        auctionRow.setState(AuctionState.ACTIVE);
        auctionRow.setStartTime(Date.from(now));
        auctionRow.setEndTime(Date.from(now.plus(Duration.ofSeconds(auctionRow.getFullDuration()))));
        computeItems();
        player.sendMessage(getAnnouncementMessage(player.getUniqueId()));
    }

    protected String debug() {
        return "active=" + isActive()
            + " loading=" + loading
            + " auctionRow=" + (auctionRow != null)
            + " items=" + (!items.isEmpty())
            + " state=" + (auctionRow != null ? auctionRow.getState() : "")
            + " remain=" + (auctionRow != null ? "" + !auctionRow.getRemainingDuration().isNegative() : "");
    }

    protected void log(String msg) {
        plugin.getLogger().info("[" + id + "] " + msg);
    }

    public boolean isActive() {
        return !loading && auctionRow.getState().isActive();
    }

    public boolean hasEnded() {
        return !loading && !auctionRow.getState().isActive();
    }

    public void load() {
        if (loading) return;
        loading = true;
        plugin.database.find(SQLAuction.class)
            .idEq(id)
            .findUniqueAsync(row -> {
                    this.auctionRow = row;
                    computeItems();
                });
        loadPlayers(() -> loading = false);
    }

    public void loadPlayers(Runnable callback) {
        plugin.database.find(SQLPlayerAuction.class)
            .eq("auctionId", id)
            .findListAsync(rows -> {
                    players.clear();
                    for (SQLPlayerAuction row : rows) {
                        players.put(row.getPlayer(), row);
                    }
                    callback.run();
                });
    }

    public ListenType getListenType(UUID uuid) {
        SQLPlayerAuction row = players.get(uuid);
        return row != null
            ? row.getListenType()
            : ListenType.DEFAULT;
    }

    private void setListenType(UUID uuid, ListenType type) {
        SQLPlayerAuction row = players.get(uuid);
        if (row != null && row.getListenType() == type) return;
        if (row == null) {
            row = new SQLPlayerAuction(auctionRow, uuid);
            row.setListenType(type);
            players.put(uuid, row);
            plugin.database.insertAsync(row, null);
        } else {
            row.setListenType(type);
            plugin.database.updateAsync(row, Set.of("ListenType"), null);
        }
    }

    public double getPlayerBid(UUID uuid) {
        SQLPlayerAuction row = players.get(uuid);
        return row != null
            ? row.getBid()
            : 0.0;
    }

    /**
     * This is called right before an auctionRow update, which
     * triggers the refresh broadcast.
     */
    private void setPlayerBid(UUID uuid, double bid, ListenType listenType) {
        SQLPlayerAuction row = players.get(uuid);
        if (row != null && row.getBid() == bid) return;
        if (row == null) {
            row = new SQLPlayerAuction(auctionRow, uuid);
            row.setBid(bid);
            row.setListenType(listenType);
            players.put(uuid, row);
            plugin.database.insertAsync(row, null);
        } else {
            row.setBid(bid);
            row.setListenType(listenType);
            plugin.database.updateAsync(row, Set.of("bid", "listenType"), null);
        }
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

    public void computeItems() {
        inventory = auctionRow.parseInventory();
        if (inventory == null) {
            itemMap = Map.of();
            items = List.of();
            return;
        }
        itemMap = new IdentityHashMap<>();
        items = new ArrayList<>();
        totalItemCount = 0;
        for (ItemStack item : inventory) {
            if (item == null || item.getType().isAir()) continue;
            items.add(item);
            addItemMap(item);
            totalItemCount += item.getAmount();
            if (item.hasItemMeta()
                && item.getItemMeta() instanceof BlockStateMeta meta
                && meta.hasBlockState()
                && meta.getBlockState() instanceof Container container) {
                for (ItemStack item2 : container.getInventory()) {
                    if (item2 == null || item2.getType().isAir()) continue;
                    totalItemCount += item2.getAmount();
                }
            }
        }
    }

    public Component getAuctionTag() {
        return text(tiny("Auction"), DARK_AQUA);
    }

    public Component getBidTag(UUID target, boolean big, boolean run) {
        double bidAmount = run
            ? auctionRow.getCurrentPrice()
            : Math.max((auctionRow.hasWinner()
                        ? auctionRow.getCurrentPrice() + 1.0
                        : auctionRow.getCurrentPrice()),
                       getPlayerBid(target) + 1.0);
        String cmd = "/bid " + id + " " + MONEY_FORMAT.format(bidAmount);
        Component text = big
            ? textOfChildren(text("["), Mytems.PLUS_BUTTON, text("Bid]")).color(BLUE)
            : Mytems.PLUS_BUTTON.component;
        return text
            .clickEvent(run ? runCommand(cmd) : suggestCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GREEN),
                                      Coin.format(bidAmount),
                                      text("Bid on this auction", GRAY),
                                      text(tiny("Bid amounts are hidden."), DARK_GRAY),
                                      text(tiny("The winning bid is just"), DARK_GRAY),
                                      text(tiny("enough to match any"), DARK_GRAY),
                                      text(tiny("other bid."), DARK_GRAY))));
    }

    public Component getIgnoreTag(boolean big) {
        String cmd = "/auc ignore " + id;
        Component text = big
            ? textOfChildren(text("["), Mytems.BLIND_EYE, text("Ignore]")).color(RED)
            : Mytems.BLIND_EYE.component;
        return text
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GREEN),
                                      text("Ignore this auction", GRAY),
                                      text(tiny("You will stop receiving"), DARK_GRAY),
                                      text(tiny("further updates on this"), DARK_GRAY),
                                      text(tiny("item."), DARK_GRAY))));
    }

    public Component getFocusTag(boolean big) {
        String cmd = "/auc focus " + id;
        Component text = big
            ? textOfChildren(text("["), VanillaItems.SPYGLASS, text("Focus]")).color(GOLD)
            : VanillaItems.SPYGLASS.component;
        return text
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GOLD),
                                      text("Focus this auction", GRAY),
                                      text(tiny("This auction will be"), DARK_GRAY),
                                      text(tiny("prioritized and you"), DARK_GRAY),
                                      text(tiny("receive instant"), DARK_GRAY),
                                      text(tiny("notifications."), DARK_GRAY))));
    }

    public Component getCancelTag(boolean big) {
        String cmd = "/auc cancel " + id;
        Component text = big
            ? textOfChildren(text("["), VanillaItems.BARRIER, text("Cancel]")).color(DARK_RED)
            : VanillaItems.BARRIER.component;
        return text
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, DARK_RED),
                                      text("Cancel this auction", GRAY),
                                      text(tiny("You can cancel an"), DARK_GRAY),
                                      text(tiny("auction before it"), DARK_GRAY),
                                      text(tiny("starts to get the"), DARK_GRAY),
                                      text(tiny("items back."), DARK_GRAY))));
    }

    /**
     * Strip item for view in bundle tooltip.  Each item needs its
     * looks intact but tooltips and metadata don't matter.
     */
    private List<ItemStack> stripItemsForBundle() {
        List<ItemStack> result = new ArrayList<>(items.size());
        for (ItemStack item : items) result.add(stripItemForBundle(item));
        return result;
    }

    /**
     * Prepare for single item to display it as a tooltip in chat.
     */
    public static ItemStack stripItemForChat(ItemStack topItem) {
        topItem = topItem.clone();
        if (topItem.hasItemMeta()) {
            topItem.editMeta(meta -> {
                    for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                        if ("worldmarker".equals(key.getNamespace())) continue;
                        meta.getPersistentDataContainer().remove(key);
                    }
                    if (meta instanceof BlockStateMeta blockStateMeta
                        && blockStateMeta.hasBlockState()
                        && blockStateMeta.getBlockState() instanceof Container container) {
                        for (ItemStack item : container.getInventory()) {
                            if (item == null || item.getType().isAir()) continue;
                            item.editMeta(meta2 -> {
                                    for (NamespacedKey key : meta2.getPersistentDataContainer().getKeys()) {
                                        meta2.getPersistentDataContainer().remove(key);
                                    }
                                    meta2.lore(null);
                                });
                        }
                        blockStateMeta.setBlockState(container);
                    }
                });
        }
        return topItem;
    }

    public static ItemStack stripItemForBundle(ItemStack item) {
        item = item.clone();
        if (item.hasItemMeta()) {
            item.editMeta(meta -> {
                    for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                        // We can delete the worldmarker/mytems ids
                        // because this will be an actual item view,
                        // meaning the CustomModelData will be used.
                        meta.getPersistentDataContainer().remove(key);
                    }
                    meta.displayName(null);
                    meta.lore(null);
                    // We clear the container because contents cannot
                    // be seen anyway.
                    if (meta instanceof BlockStateMeta blockStateMeta
                        && blockStateMeta.hasBlockState()
                        && blockStateMeta.getBlockState() instanceof Container container) {
                        container.getInventory().clear();
                        blockStateMeta.setBlockState(container);
                    }
                });
        }
        return item;
    }

    /**
     * Produce the component to display the item in chat.
     */
    public Component getChatItemTag() {
        final Component itemComponent;
        if (itemMap.size() == 1) {
            ItemStack theItem = itemMap.keySet().iterator().next();
            int count = itemMap.getOrDefault(theItem, 1);
            itemComponent = ItemKinds.chatDescription(stripItemForChat(theItem), count);
        } else {
            ItemStack hoverItem = new ItemStack(Material.BUNDLE);
            Component title = textOfChildren(text(totalItemCount), VanillaItems.BUNDLE, text("Items"));
            hoverItem.editMeta(m -> {
                    if (m instanceof BundleMeta meta) {
                        meta.setItems(stripItemsForBundle());
                    }
                    m.addItemFlags(ItemFlag.values());
                });
            itemComponent = title.hoverEvent(hoverItem.asHoverEvent());
        }
        return itemComponent.clickEvent(runCommand("/auc preview " + id));
    }

    /**
     * Same as getChatItemTag but shorter.
     */
    public Component getChatIconTag() {
        final Component itemComponent;
        if (itemMap.size() == 1) {
            ItemStack theItem = itemMap.keySet().iterator().next();
            int count = itemMap.getOrDefault(theItem, 1);
            Component icon = ItemKinds.icon(stripItemForChat(theItem));
            if (empty().equals(icon)) {
                itemComponent = bundleIconTag();
            } else {
                itemComponent = ItemKinds.iconDescription(stripItemForChat(theItem), count);
            }
        } else {
            itemComponent = bundleIconTag();
        }
        return itemComponent.clickEvent(runCommand("/auc preview " + id));
    }

    /**
     * Produce the item tag for the sidebar.  This differs from chat
     * because we do not need a tooltip.  If a tooltip is too long,
     * players will disconnect.  It's also just an icon.
     */
    private Component getSidebarItemTag() {
        if (itemMap.size() == 1) {
            ItemStack theItem = itemMap.keySet().iterator().next();
            int count = itemMap.getOrDefault(theItem, 1);
            Component icon = ItemKinds.icon(stripItemForChat(theItem));
            return empty().equals(icon)
                ? VanillaItems.BUNDLE.component
                : icon;
        } else {
            return VanillaItems.BUNDLE.component;
        }
    }

    private Component bundleIconTag() {
        ItemStack hoverItem = new ItemStack(Material.BUNDLE);
        Component icon = textOfChildren(VanillaItems.BUNDLE, text(subscript(totalItemCount)));
        hoverItem.editMeta(m -> {
                if (m instanceof BundleMeta meta) {
                    meta.setItems(stripItemsForBundle());
                }
                m.addItemFlags(ItemFlag.values());
            });
        return icon.hoverEvent(hoverItem.asHoverEvent());
    }

    public Component getUserTags(UUID target) {
        List<Component> result = new ArrayList<>();
        if (!auctionRow.isOwner(target)) {
            result.add(getBidTag(target, false, false));
        }
        ListenType listenType = getListenType(target);
        if (!listenType.isFocus()) {
            result.add(getFocusTag(false));
        }
        if (!listenType.isIgnore()) {
            result.add(getIgnoreTag(false));
        }
        return join(noSeparators(), result);
    }

    public Component getAnnouncementMessage(UUID target) {
        return textOfChildren(getUserTags(target),
                              getAuctionTag(),
                              text(tiny(" for "), DARK_GRAY),
                              getChatItemTag(),
                              text(tiny(" price "), DARK_GRAY),
                              Coin.format(auctionRow.getCurrentPrice()),
                              text(tiny(" time "), DARK_GRAY),
                              Format.duration(auctionRow.getRemainingDuration()));
    }

    protected void bidCommand(RemotePlayer player, double amount) {
        if (auctionRow.isOwner(player.getUniqueId())) {
            throw new CommandWarn("You cannot bid on your own auction");
        }
        if (!auctionRow.getState().isActive()) {
            throw new CommandWarn("Auction not active");
        }
        final double price = auctionRow.getCurrentPrice();
        if (auctionRow.hasWinner() && amount - price < 0.01) {
            if (Math.abs(amount - price) < 0.01) {
                Component bidTag = getBidTag(player.getUniqueId(), true, false);
                player.sendMessage(join(separator(space()),
                                        bidTag,
                                        text("Click here to bid more than", GREEN),
                                        Coin.format(price))
                                   .hoverEvent(bidTag.hoverEvent())
                                   .clickEvent(bidTag.clickEvent()));
                return;
            }
            throw new CommandWarn(textOfChildren(text("You must bid more than ", RED), Coin.format(price)));
        } else if (amount < price) {
            throw new CommandWarn(textOfChildren(text("You must bid at least ", RED), Coin.format(price)));
        }
        if (!Money.get().has(player.getUniqueId(), amount)) {
            throw new CommandWarn(textOfChildren(text("You do not have ", RED), Coin.format(amount)));
        }
        final double highest = auctionRow.getHighestBid();
        final boolean winning = !auctionRow.hasWinner() || amount - highest >= 0.01;
        final boolean raising = winning || amount - price >= 0.01;
        final boolean wasWinner = auctionRow.isWinner(player.getUniqueId());
        log("[bid] "
            + " highest=" + highest
            + " winning=" + winning
            + " raising=" + raising
            + " wasWinner=" + wasWinner);
        final BidType bidType;
        if (wasWinner) {
            if (!winning) {
                throw new CommandWarn(textOfChildren(text("You already bid ", RED), Coin.format(highest)));
            }
            setPlayerBid(player.getUniqueId(), amount, ListenType.FOCUS);
            auctionRow.setHighestBid(amount);
            plugin.database.updateAsync(auctionRow, Set.of("highestBid"), this::postBid);
            bidType = BidType.SILENT;
        } else {
            if (winning) {
                final double newPrice = Math.max(highest, price);
                auctionRow.setCurrentBid(newPrice);
                auctionRow.setCurrentPrice(newPrice);
                auctionRow.setHighestBid(amount);
                auctionRow.setWinner(player.getUniqueId());
                setPlayerBid(player.getUniqueId(), amount, ListenType.FOCUS);
                plugin.database.updateAsync(auctionRow, Set.of("currentBid", "currentPrice", "highestBid", "winner"), this::postBid);
                bidType = BidType.WINNER;
            } else if (raising) {
                auctionRow.setCurrentBid(amount);
                auctionRow.setCurrentPrice(amount);
                setPlayerBid(player.getUniqueId(), amount, ListenType.FOCUS);
                plugin.database.updateAsync(auctionRow, Set.of("currentBid", "currentPrice"), this::postBid);
                bidType = BidType.RAISE;
            } else {
                // Should never happen
                throw new CommandWarn("You must bid more");
            }
        }
        LogType.BID.log(auctionRow, player.getUniqueId(), amount);
        if (bidType.isSilent()) {
            player.sendMessage(textOfChildren(getAuctionTag(),
                                              space(),
                                              text("You raised your bid to "),
                                              Coin.format(amount)));
        } else if (bidType.isWinner()) {
            announce(ListenType.FOCUS, Set.of(player.getUniqueId()),
                     uuid -> textOfChildren(getUserTags(uuid),
                                            getAuctionTag(),
                                            space(),
                                            text(player.getName()),
                                            text(tiny(" is winning "), DARK_GRAY),
                                            getChatItemTag(),
                                            text(tiny(" for "), DARK_GRAY),
                                            Coin.format(auctionRow.getCurrentPrice())));
        } else if (bidType.isRaise()) {
            announce(ListenType.FOCUS, Set.of(player.getUniqueId()),
                     uuid -> textOfChildren(getUserTags(uuid),
                                            getAuctionTag(),
                                            space(),
                                            text(player.getName()),
                                            text(tiny(" raised "), DARK_GRAY),
                                            getChatItemTag(),
                                            text(tiny(" to "), DARK_GRAY),
                                            Coin.format(auctionRow.getCurrentPrice())));
        }
    }

    private void postBid(int saveResult) {
        if (saveResult == 0) {
            plugin.getLogger().severe("Save failed: " + auctionRow);
        }
        Connect.get().broadcastMessage(ServerGroup.current(), Auctions.CONNECT_REFRESH, "" + id);
    }

    /**
     * Only ACTIVE auctions should be ticked this way, and only while
     * on the manager server.
     */
    protected void managerTick() {
        Duration remaining = auctionRow.getRemainingDuration();
        if (remaining.isNegative()) {
            end();
            return;
        }
        if (Duration.between(auctionRow.getAnnouncedTime().toInstant(), Instant.now()).toMinutes() >= 15) {
            auctionRow.setAnnouncedTime(new Date());
            plugin.database.updateAsync(auctionRow, Set.of("announcedTime"), null);
            announce(ListenType.DEFAULT, Set.of(), this::getAnnouncementMessage);
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
                Connect.get().broadcastMessage(ServerGroup.current(), Auctions.CONNECT_REFRESH, "" + id);
            });
        computeItems();
        LogType.START.log(auctionRow, null, auctionRow.getCurrentPrice());
    }

    public void end() {
        plugin.getLogger().info("End Auction " + id);
        auctionRow.setState(AuctionState.ENDED);
        auctionRow.setExclusive(false);
        plugin.database.updateAsync(auctionRow, Set.of("state", "exclusive"), res -> {
                if (res == 0) return;
                Connect.get().broadcastMessage(ServerGroup.current(), Auctions.CONNECT_REMOVE, "" + id);
            });
        if (auctionRow.hasWinner()) {
            LogType.WIN.log(auctionRow, auctionRow.getWinner(), auctionRow.getCurrentPrice());
            boolean paid = Money.get().take(auctionRow.getWinner(), auctionRow.getCurrentPrice(), plugin, "Win auction #" + id);
            if (paid && !auctionRow.isServerAuction()) {
                Money.get().give(auctionRow.getOwner(), auctionRow.getCurrentPrice(), plugin, "Auction #" + id);
            }
            double debt = paid
                ? 0.0
                : auctionRow.getCurrentPrice();
            plugin.database.insertAsync(new SQLDelivery(auctionRow, auctionRow.getWinner(), debt), r -> {
                    plugin.auctions.checkDeliveries();
                    RemotePlayer remote = Connect.get().getRemotePlayer(auctionRow.getWinner());
                    if (remote != null) {
                        remote.sendMessage(textOfChildren(textOfChildren(newline(),
                                                                         Mytems.MOUSE_LEFT,
                                                                         text("You have an auction delivery: ", WHITE),
                                                                         text("/auc pickup", YELLOW),
                                                                         newline()))
                                           .hoverEvent(showText(text("/auc pickup", YELLOW)))
                                           .clickEvent(runCommand("/auc pickup")));
                    }
                });
            if (!paid) {
                LogType.DEBT.log(auctionRow, auctionRow.getWinner(), debt);
            }
            announce(ListenType.FOCUS, Set.copyOf(List.of(auctionRow.getWinner(), auctionRow.getOwner())),
                     textOfChildren(getAuctionTag(),
                                    space(),
                                    text(auctionRow.getWinnerName()),
                                    text(tiny(" wins "), DARK_GRAY),
                                    getChatItemTag(),
                                    text(tiny(" for "), DARK_GRAY),
                                    Coin.format(auctionRow.getCurrentPrice())));
        } else {
            LogType.FAIL.log(auctionRow, null, 0.0);
            if (!auctionRow.isServerAuction()) {
                plugin.database.insertAsync(new SQLDelivery(auctionRow, auctionRow.getOwner(), 0.0), r -> plugin.auctions.checkDeliveries());
            }
            announce(ListenType.FOCUS, Set.of(auctionRow.getOwner()),
                     textOfChildren(getAuctionTag(),
                                    space(),
                                    text(tiny(" ended: "), DARK_GRAY),
                                    getChatItemTag()));
        }
    }

    public void cancel(UUID sender) {
        auctionRow.setState(AuctionState.CANCELLED);
        auctionRow.setExclusive(false);
        plugin.database.updateAsync(auctionRow, Set.of("state", "exclusive"), r -> {
                if (r == 0) return;
                Connect.get().broadcastMessage(ServerGroup.current(), Auctions.CONNECT_REMOVE, "" + id);
                if (!auctionRow.isServerAuction()) {
                    plugin.database.insertAsync(new SQLDelivery(auctionRow, auctionRow.getOwner(), 0.0), rr -> {
                            plugin.auctions.checkDeliveries();
                        });
                }
            });
    }

    protected void announce(ListenType listenType, Set<UUID> targets, Function<UUID, Component> func) {
        for (RemotePlayer player : Connect.get().getRemotePlayers()) {
            if (player.getOriginServer().group != ServerGroup.current()) continue;
            if (!targets.contains(player.getUniqueId())) {
                if (getListenType(player.getUniqueId()) != listenType) {
                    continue;
                }
                if (!Perm.get().has(player.getUniqueId(), "auction.auction")) {
                    continue;
                }
            }
            Component message = func.apply(player.getUniqueId())
                .hoverEvent(showText(join(separator(newline()), getInfoLines(player.getUniqueId(), false))))
                .clickEvent(runCommand("/auc info " + id));
            player.sendMessage(message);
        }
    }

    protected void announce(ListenType listenType, Set<UUID> targets, Component message) {
        announce(listenType, targets, p -> message);
    }

    public List<Component> getInfoLines(UUID target, boolean book) {
        List<Component> lines = new ArrayList<>();
        TextColor gray = GRAY;
        TextColor hl = book ? BLACK : WHITE;
        lines.add(textOfChildren(text(tiny("id "), gray), text("" + id, hl)));
        lines.add(textOfChildren(text(tiny("state "), gray), auctionRow.getState().displayName));
        if (auctionRow.getState().isActive()) {
            lines.add(textOfChildren(text(tiny("time "), gray), Format.duration(auctionRow.getRemainingDuration(), book)));
        }
        lines.add(textOfChildren(text(tiny("items "), gray)
                                 .hoverEvent(showText(textOfChildren(Mytems.MOUSE_LEFT, text(" Open a preview", GRAY))))
                                 .clickEvent(runCommand("/auc preview " + id)),
                                 getChatIconTag()));
        lines.add(textOfChildren(text(tiny("price "), gray), Format.money(auctionRow.getCurrentPrice(), book)));
        lines.add(textOfChildren(text(tiny("owner "), gray), text(auctionRow.getOwnerName(), hl)));
        if (auctionRow.hasWinner()) {
            lines.add(textOfChildren(text(tiny("winner "), gray), text(auctionRow.getWinnerName(), hl)));
        }
        SQLPlayerAuction playerAuction = players.get(target);
        if (playerAuction != null && playerAuction.getBid() >= 0.01) {
            lines.add(textOfChildren(text(tiny("your bid "), gray), Format.money(playerAuction.getBid(), book)));
        }
        ListenType listening = getListenType(target);
        if (listening.isIgnore()) {
            lines.add(textOfChildren(text(tiny("ignored "), gray), Mytems.BLIND_EYE));
        } else if (listening.isFocus()) {
            lines.add(textOfChildren(text(tiny("focused "), gray), VanillaItems.SPYGLASS));
        }
        if (!book) return lines;
        List<Component> buttons = new ArrayList<>();
        if (auctionRow.getState().isListenable()) {
            buttons.add(textOfChildren(text("["), Mytems.REDO, text("Refresh]")).color(DARK_GREEN)
                        .hoverEvent(showText(join(separator(newline()),
                                                  text("/auc info " + id, DARK_GREEN),
                                                  text("Refresh this view", GRAY))))
                        .clickEvent(runCommand("/auc info " + id)));
        }
        if (!auctionRow.isOwner(target)) {
            if (auctionRow.getState().isActive()) {
                buttons.add(getBidTag(target, true, true));
            }
        } else {
            if (auctionRow.getState().isCancellable() && !auctionRow.hasWinner()) {
                buttons.add(getCancelTag(true));
            }
        }
        if (auctionRow.getState().isListenable()) {
            if (!listening.isIgnore()) buttons.add(getIgnoreTag(true));
            if (!listening.isFocus()) buttons.add(getFocusTag(true));
        }
        buttons.add(textOfChildren(text("["), Mytems.FOLDER, text("List]")).color(BLUE)
                    .hoverEvent(showText(join(separator(newline()),
                                              text("/auc list", BLUE),
                                              text("List all auctions", GRAY))))
                    .clickEvent(runCommand("/auc list")));
        if (!buttons.isEmpty()) {
            lines.add(join(separator(space()), buttons));
        }
        return lines;
    }

    public List<Component> getSidebarLines(UUID target) {
        List<Component> lines = new ArrayList<>();
        lines.add(textOfChildren(text(superscript(auctionRow.getId()) + Unicode.SUPER_RPAR.string, DARK_AQUA),
                                 getSidebarItemTag(),
                                 space(),
                                 Coin.format(auctionRow.getCurrentPrice())));
        double bid = getPlayerBid(target);
        if (bid >= 0.01) {
            lines.add(textOfChildren(space(),
                                     text(tiny("your bid "), GRAY),
                                     Coin.format(bid)));
        }
        lines.add(textOfChildren(space(),
                                 Format.duration(auctionRow.getRemainingDuration()),
                                 (auctionRow.hasWinner()
                                  ? textOfChildren(space(), text(auctionRow.getWinnerName(), DARK_AQUA))
                                  : empty())));
        return lines;
    }
}
