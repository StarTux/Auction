package com.cavetale.auction;

import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.font.VanillaEffects;
import com.cavetale.core.font.VanillaItems;
import com.cavetale.core.item.ItemKinds;
import com.cavetale.core.money.Money;
import com.cavetale.core.perm.Perm;
import com.cavetale.mytems.Mytems;
import com.cavetale.mytems.item.coin.Coin;
import com.winthier.playercache.PlayerCache;
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
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import static com.cavetale.core.font.Unicode.subscript;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.empty;
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
    private List<ItemStack> items = List.of(); // ???
    private List<ItemStack> allItems = List.of(); // tooltip
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
            && auctionRow.getState().isActive();
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
    private void setPlayerBid(UUID uuid, double bid) {
        SQLPlayerAuction row = players.get(uuid);
        if (row != null && row.getBid() == bid) return;
        if (row == null) {
            row = new SQLPlayerAuction(auctionRow, uuid);
            row.setBid(bid);
            players.put(uuid, row);
            plugin.database.insertAsync(row, null);
        } else {
            row.setBid(bid);
            plugin.database.updateAsync(row, Set.of("bid"), null);
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
            allItems = List.of();
            return;
        }
        itemMap = new IdentityHashMap<>();
        items = new ArrayList<>();
        allItems = new ArrayList<>();
        totalItemCount = 0;
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

    public Component getAuctionTag() {
        return text(tiny("Auction"), DARK_AQUA);
    }

    public Component getBidTag(boolean big) {
        double bidAmount = auctionRow.hasWinner()
            ? auctionRow.getCurrentPrice() + 1.0
            : auctionRow.getCurrentPrice();
        String cmd = "/bid " + id + " " + Mytems.PLUS_BUTTON.format(bidAmount);
        Component text = big
            ? join(noSeparators(), text("["), Mytems.GOLDEN_COIN, text("Bid]")).color(BLUE)
            : Mytems.PLUS_BUTTON.component;
        return text
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
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
            ? join(noSeparators(), text("["), VanillaEffects.BLINDNESS, text("Ignore]")).color(RED)
            : VanillaEffects.BLINDNESS.component;
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
            ? join(noSeparators(), text("["), VanillaItems.SPYGLASS, text("Focus]")).color(GREEN)
            : VanillaItems.SPYGLASS.component;
        return text
            .clickEvent(runCommand(cmd))
            .hoverEvent(showText(join(separator(newline()),
                                      text(cmd, GREEN),
                                      text("Focus this auction", GRAY),
                                      text(tiny("This auction will be"), DARK_GRAY),
                                      text(tiny("prioritized and you"), DARK_GRAY),
                                      text(tiny("receive instant"), DARK_GRAY),
                                      text(tiny("notifications."), DARK_GRAY))));
    }

    public Component getCancelTag(boolean big) {
        String cmd = "/auc cancel " + id;
        Component text = big
            ? join(noSeparators(), text("["), Mytems.BOMB, text("Cancel]")).color(DARK_RED)
            : Mytems.BOMB.component;
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

    public Component getItemTag() {
        final Component itemComponent;
        if (itemMap.size() == 1) {
            ItemStack theItem = itemMap.keySet().iterator().next();
            int count = itemMap.getOrDefault(theItem, 1);
            itemComponent = ItemKinds.chatDescription(theItem, count);
        } else {
            ItemStack hoverItem = new ItemStack(Material.BUNDLE);
            Component title = join(noSeparators(), text(totalItemCount), VanillaItems.BUNDLE, text("Items"));
            hoverItem.editMeta(m -> {
                    if (m instanceof BundleMeta meta) {
                        meta.setItems(allItems);
                    }
                    m.addItemFlags(ItemFlag.values());
                });
            itemComponent = title.hoverEvent(hoverItem.asHoverEvent());
        }
        return itemComponent.clickEvent(runCommand("/auc preview " + id));
    }

    public Component getIconTag() {
        final Component itemComponent;
        if (itemMap.size() == 1) {
            ItemStack theItem = itemMap.keySet().iterator().next();
            int count = itemMap.getOrDefault(theItem, 1);
            itemComponent = ItemKinds.iconDescription(theItem, count);
        } else {
            ItemStack hoverItem = new ItemStack(Material.BUNDLE);
            Component icon = join(noSeparators(), VanillaItems.BUNDLE, text(subscript(totalItemCount)));
            hoverItem.editMeta(m -> {
                    if (m instanceof BundleMeta meta) {
                        meta.setItems(allItems);
                    }
                    m.addItemFlags(ItemFlag.values());
                });
            itemComponent = icon.hoverEvent(hoverItem.asHoverEvent());
        }
        return itemComponent.clickEvent(runCommand("/auc preview " + id));
    }

    public Component getAnnouncementMessage() {
        return join(noSeparators(),
                    getBidTag(false),
                    getFocusTag(false),
                    getIgnoreTag(false),
                    space(),
                    getAuctionTag(),
                    text(tiny(" for "), DARK_GRAY),
                    getItemTag(),
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
        final BidType bidType;
        if (wasWinner) {
            if (!winning) {
                throw new CommandWarn("You are already winning");
            }
            setPlayerBid(player.getUniqueId(), amount);
            auctionRow.setHighestBid(amount);
            plugin.database.updateAsync(auctionRow, Set.of("highestBid"), this::postBid);
            bidType = BidType.SILENT;
        } else {
            if (winning) {
                final double newPrice = Math.max(price, auctionRow.getCurrentBid());
                auctionRow.setCurrentBid(newPrice);
                auctionRow.setCurrentPrice(newPrice);
                auctionRow.setHighestBid(amount);
                auctionRow.setWinner(player.getUniqueId());
                setPlayerBid(player.getUniqueId(), amount);
                plugin.database.updateAsync(auctionRow, Set.of("currentBid", "currentPrice", "highestBid", "winner"), this::postBid);
                bidType = BidType.WINNER;
            } else if (raising) {
                auctionRow.setCurrentBid(amount);
                auctionRow.setCurrentPrice(amount);
                setPlayerBid(player.getUniqueId(), amount);
                plugin.database.updateAsync(auctionRow, Set.of("currentBid", "currentPrice"), this::postBid);
                bidType = BidType.RAISE;
            } else {
                // Should never happen
                throw new CommandWarn("You must bid more");
            }
        }
        LogType.BID.log(auctionRow, player.getUniqueId(), amount);
        if (bidType.isSilent()) {
            player.sendMessage(join(noSeparators(),
                                    getAuctionTag(),
                                    space(),
                                    text("You raised your bid to "),
                                    Coin.format(amount)));
        } else if (bidType.isWinner()) {
            announce(ListenType.FOCUS,
                     join(noSeparators(),
                          getBidTag(false),
                          getIgnoreTag(false),
                          getAuctionTag(),
                          space(),
                          text(player.getName()),
                          text(tiny(" is winning "), DARK_GRAY),
                          getItemTag(),
                          text(tiny(" price "), DARK_GRAY),
                          Coin.format(auctionRow.getCurrentPrice())));
        } else if (bidType.isRaise()) {
            player.sendMessage(join(noSeparators(),
                                    getAuctionTag(),
                                    space(),
                                    text("There is a higher bid, so you raised the price to "),
                                    Coin.format(auctionRow.getCurrentPrice())));
        }
    }

    private void postBid(int saveResult) {
        if (saveResult == 0) {
            plugin.getLogger().severe("Save failed: " + auctionRow);
        }
        Connect.get().broadcastMessage(Auctions.CONNECT_REFRESH, "" + id);
    }

    protected void managerTick() {
        Duration remaining = auctionRow.getRemainingDuration();
        if (remaining.isNegative()) {
            end();
            return;
        }
        if (Duration.between(auctionRow.getAnnouncedTime().toInstant(), Instant.now()).toMinutes() >= 5) {
            auctionRow.setAnnouncedTime(new Date());
            plugin.database.updateAsync(auctionRow, Set.of("announcedTime"), null);
            announce(ListenType.DEFAULT, getAnnouncementMessage());
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
            });
        computeItems();
        LogType.START.log(auctionRow, auctionRow.getOwner(), auctionRow.getCurrentPrice());
    }

    public void end() {
        auctionRow.setState(AuctionState.ENDED);
        auctionRow.setExclusive(false);
        plugin.database.updateAsync(auctionRow, Set.of("state", "exclusive"), res -> {
                if (res == 0) return;
                Connect.get().broadcastMessage(Auctions.CONNECT_REFRESH, "" + id);
            });
        if (auctionRow.hasWinner()) {
            LogType.WIN.log(auctionRow, auctionRow.getWinner(), auctionRow.getCurrentPrice());
            boolean paid = Money.get().take(auctionRow.getWinner(), auctionRow.getCurrentPrice(), plugin, "Win auction #" + id);
            if (paid) {
                Money.get().give(auctionRow.getOwner(), auctionRow.getCurrentPrice(), plugin, "Auction #" + id);
            }
            double debt = paid
                ? 0.0
                : auctionRow.getCurrentPrice();
            plugin.database.insertAsync(new SQLDelivery(auctionRow, auctionRow.getWinner(), debt), r -> plugin.auctions.checkDeliveries());
            if (!paid) {
                LogType.DEBT.log(auctionRow, auctionRow.getWinner(), debt);
            }
            announce(ListenType.DEFAULT, join(noSeparators(),
                                              getAuctionTag(),
                                              space(),
                                              text(PlayerCache.nameForUuid(auctionRow.getWinner())),
                                              text(tiny(" wins "), DARK_GRAY),
                                              getItemTag(),
                                              text(tiny(" for "), DARK_GRAY),
                                              Coin.format(auctionRow.getCurrentPrice())));
        } else {
            LogType.FAIL.log(auctionRow, null, 0.0);
            plugin.database.insertAsync(new SQLDelivery(auctionRow, auctionRow.getOwner(), 0.0), r -> plugin.auctions.checkDeliveries());
            announce(ListenType.DEFAULT, join(noSeparators(),
                                              getAuctionTag(),
                                              space(),
                                              text(tiny(" ended: "), DARK_GRAY),
                                              getItemTag()));
        }
    }

    protected void announce(ListenType listenType, Function<RemotePlayer, Component> func) {
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
            Component message = func.apply(player)
                .hoverEvent(showText(join(separator(newline()), getInfoLines(player.getUniqueId(), false))))
                .clickEvent(runCommand("/auc info " + id));
            player.sendMessage(message);
        }
    }

    protected void announce(ListenType listenType, Component message) {
        announce(listenType, p -> message);
    }

    public List<Component> getBookLines(UUID target) {
        List<Component> lines = new ArrayList<>();
        lines.add(join(separator(space()),
                       text("#" + id, DARK_GRAY),
                       getIconTag(),
                       Format.money(auctionRow.getCurrentPrice(), true)));
        lines.add(join(separator(space()), space(),
                       (isActive()
                        ? Format.duration(auctionRow.getRemainingDuration(), true)
                        : auctionRow.getState().displayName),
                       (auctionRow.hasWinner()
                        ? text(PlayerCache.nameForUuid(auctionRow.getWinner()))
                        : empty())));
        for (int i = 0; i < lines.size(); i += 1) {
            Component line = lines.get(i);
            line = line
                .hoverEvent(showText(join(separator(newline()), getInfoLines(target, false))))
                .clickEvent(runCommand("/auc info " + id));
            lines.set(i, line);
        }
        return lines;
    }

    public List<Component> getInfoLines(UUID target, boolean dark) {
        List<Component> lines = new ArrayList<>();
        TextColor gray = GRAY;
        TextColor hl = dark ? BLACK : WHITE;
        lines.add(join(noSeparators(), text(tiny("id "), gray), text("" + id, hl)));
        lines.add(join(noSeparators(), text(tiny("state "), gray), auctionRow.getState().displayName));
        if (auctionRow.getState().isActive()) {
            lines.add(join(noSeparators(), text(tiny("time "), gray), Format.duration(auctionRow.getRemainingDuration(), dark)));
        }
        lines.add(join(noSeparators(), text(tiny("items "), gray), getIconTag()));
        lines.add(join(noSeparators(), text(tiny("price "), gray), Format.money(auctionRow.getCurrentPrice(), dark)));
        lines.add(join(noSeparators(), text(tiny("owner "), gray), text(PlayerCache.nameForUuid(auctionRow.getOwner()), hl)));
        if (auctionRow.hasWinner()) {
            lines.add(join(noSeparators(), text(tiny("winner "), gray), text(PlayerCache.nameForUuid(auctionRow.getWinner()), hl)));
        }
        SQLPlayerAuction playerAuction = players.get(target);
        if (playerAuction != null && playerAuction.getBid() >= 0.01) {
            lines.add(join(noSeparators(), text(tiny("your bid "), gray), Format.money(playerAuction.getBid(), dark)));
        }
        List<Component> buttons = new ArrayList<>();
        if (auctionRow.getState().isListenable()) {
            buttons.add(join(noSeparators(), text("["), Mytems.REDO, text("Refresh]")).color(GREEN)
                        .hoverEvent(showText(join(separator(newline()),
                                                  text("/auc info " + id, GREEN),
                                                  text("Refresh this view", GRAY))))
                        .clickEvent(runCommand("/auc info " + id)));
        }
        if (!auctionRow.isOwner(target)) {
            if (auctionRow.getState().isActive()) {
                buttons.add(getBidTag(true));
            }
        } else {
            if (auctionRow.getState().isScheduled() || auctionRow.getState().isActive() && !auctionRow.hasWinner()) {
                buttons.add(getCancelTag(true));
            }
        }
        if (auctionRow.getState().isListenable()) {
            buttons.add(getIgnoreTag(true));
            buttons.add(getFocusTag(true));
        }
        buttons.add(join(noSeparators(), text("["), Mytems.FOLDER, text("List]")).color(BLUE)
                    .hoverEvent(showText(join(separator(newline()),
                                              text("/auc list", BLUE),
                                              text("List all auctions", GRAY))))
                    .clickEvent(runCommand("/auc list")));
        if (!buttons.isEmpty()) {
            lines.add(join(separator(text("  ")), buttons));
        }
        return lines;
    }

    public List<Component> getSidebarLines(UUID target) {
        List<Component> lines = new ArrayList<>();
        lines.add(getItemTag());
        lines.add(join(separator(space()),
                       Format.duration(auctionRow.getRemainingDuration()),
                       Coin.format(auctionRow.getCurrentPrice())));
        return lines;
    }
}
