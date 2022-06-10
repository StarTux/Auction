package com.cavetale.auction;

import com.cavetale.auction.gui.Gui;
import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.core.connect.NetworkServer;
import com.cavetale.core.event.connect.ConnectMessageEvent;
import com.cavetale.mytems.item.coin.Coin;
import com.cavetale.sidebar.PlayerSidebarEvent;
import com.cavetale.sidebar.Priority;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@RequiredArgsConstructor
public final class Auctions implements Listener {
    protected static final String CONNECT_REFRESH = "auction:refresh";
    protected static final String CONNECT_SCHEDULED = "auction:scheduled";
    private static final Comparator<Auction> END_TIME_COMPARATOR = Comparator
        .comparing(auc -> auc.getAuctionRow().getEndTime());
    private static final Comparator<SQLAuction> CREATED_TIME_COMPARATOR = Comparator
        .comparing(row -> row.getCreatedTime());
    private final AuctionPlugin plugin;
    private final Map<Integer, Auction> auctionMap = new TreeMap<>();
    private boolean manage;
    private boolean refreshing;
    private boolean scheduling;
    /**
     * If set to true, we assume that there are no further auctions
     * waiting to be scheduled.
     */
    private boolean queueEmpty;

    protected void debug(CommandSender sender) {
        sender.sendMessage("refreshing " + refreshing);
        sender.sendMessage("scheduling " + scheduling);
        sender.sendMessage("queueEmpty " + queueEmpty);
        for (Auction auction : auctionMap.values()) {
            sender.sendMessage("auction #" + auction.getId() + " " + auction.debug());
        }
    }

    protected void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        manage = NetworkServer.current() == NetworkServer.manager();
        refresh();
        if (manage) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::managerTick, 0L, 20L);
            plugin.getLogger().info("Auction manager active!");
        }
    }

    protected void refresh() {
        if (refreshing) return;
        refreshing = true;
        plugin.database.find(SQLAuction.class)
            .eq("state", AuctionState.ACTIVE)
            .findValuesAsync("id", Integer.class, values -> {
                    refreshing = false;
                    auctionMap.keySet().removeIf(id -> !values.contains(id));
                    for (int id : values) refreshAuction(id);
                });
    }

    protected void refreshAuction(int id) {
        auctionMap.computeIfAbsent(id, i -> new Auction(plugin, i)).load();
    }

    protected void schedule() {
        if (scheduling) return;
        queueEmpty = true;
        scheduling = true;
        plugin.database.find(SQLAuction.class)
            .eq("state", AuctionState.SCHEDULED)
            .findListAsync(list -> {
                    scheduling = false;
                    if (list.isEmpty()) return;
                    if (list.size() > 1) queueEmpty = false;
                    list.sort(CREATED_TIME_COMPARATOR);
                    SQLAuction auctionRow = list.get(0);
                    int id = auctionRow.getId();
                    Auction auction = new Auction(plugin, id);
                    auction.setAuctionRow(auctionRow);
                    auctionMap.put(id, auction);
                    auction.start();
                });
    }

    public List<Auction> getActiveAuctions() {
        List<Auction> result = new ArrayList<>();
        for (Auction auction : auctionMap.values()) {
            if (!auction.isActive()) continue;
            result.add(auction);
        }
        result.sort(END_TIME_COMPARATOR);
        return result;
    }

    public List<Auction> getPlayerAuctions(UUID uuid) {
        List<Auction> result = new ArrayList<>();
        for (Auction auction : auctionMap.values()) {
            if (!auction.isActive()) continue;
            if (auction.getListenType(uuid).isIgnore()) continue;
            result.add(auction);
        }
        Comparator<Auction> listenComparator = Comparator
            .comparingInt(auc -> auc.getListenType(uuid).priority);
        result.sort(listenComparator.reversed().thenComparing(END_TIME_COMPARATOR));
        return result;
    }

    @EventHandler
    private void onPlayerSidebar(PlayerSidebarEvent event) {
        if (auctionMap.isEmpty()) return;
        if (!event.getPlayer().hasPermission("auction.auction")) return;
        final UUID uuid = event.getPlayer().getUniqueId();
        List<Auction> playerAuctions = getPlayerAuctions(uuid);
        if (playerAuctions.isEmpty()) return;
        List<Component> lines = new ArrayList<>();
        lines.add(join(noSeparators(),
                       text("Current ", AQUA), text("/auc", YELLOW), text("tions", AQUA)));
        boolean focusAny = false;
        for (int i = 0; i < playerAuctions.size(); i += 1) {
            Auction auction = playerAuctions.get(i);
            boolean focus = auction.getListenType(uuid).isFocus();
            if (i > 0 && !focus) break;
            focusAny |= focus;
            lines.add(auction.getItemTag());
            lines.add(join(separator(space()),
                           Auction.format(auction.getAuctionRow().getRemainingDuration()),
                           Coin.format(auction.getAuctionRow().getCurrentPrice())));
        }
        event.add(plugin, focusAny ? Priority.HIGH : Priority.LOW, lines);
    }

    @EventHandler
    private void onConnectMessage(ConnectMessageEvent event) {
        if (event.getChannel().equals(CONNECT_REFRESH)) {
            int id = Integer.parseInt(event.getPayload());
            refreshAuction(id);
        } else if (event.getChannel().equals(CONNECT_SCHEDULED)) {
            queueEmpty = false;
        }
    }

    public void previewAuction(Player player, SQLAuction row) {
        Inventory inventory = row.parseInventory();
        Gui gui = new Gui(plugin)
            .size(inventory.getSize())
            .title(text("Auction Preview", DARK_AQUA));
        for (int i = 0; i < inventory.getSize(); i += 1) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            gui.setItem(i, item, click -> {
                    if (click.isLeftClick() && item.hasItemMeta()
                        && item.getItemMeta() instanceof BlockStateMeta meta
                        && meta.hasBlockState()
                        && meta.getBlockState() instanceof Container container) {
                        previewContainer(player, row, container.getInventory());
                    }
                });
        }
        gui.open(player);
    }

    public void previewContainer(Player player, SQLAuction row, Inventory inventory) {
        Gui gui = new Gui(plugin)
            .size(inventory.getSize())
            .title(text("Auction Preview", DARK_AQUA));
        for (int i = 0; i < inventory.getSize(); i += 1) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            gui.setItem(i, item);
        }
        gui.setItem(Gui.OUTSIDE, null, click -> {
                if (click.isLeftClick()) previewAuction(player, row);
            });
        gui.open(player);
    }

    public Auction getActiveAuction(int id) {
        Auction auc = auctionMap.get(id);
        return auc != null && auc.isActive()
            ? auc
            : null;
    }

    private void managerTick() {
        if (refreshing) return;
        boolean anyEndsWithin = false;
        for (Auction auction : auctionMap.values()) {
            if (!auction.isActive()) continue;
            auction.managerTick();
            if (auction.getAuctionRow().getRemainingDuration().toMinutes() <= 10L) anyEndsWithin = true;
        }
        auctionMap.values().removeIf(Auction::hasEnded);
        if (!anyEndsWithin && !queueEmpty) {
            plugin.getLogger().info("scheduling...");
            schedule();
        }
    }
}
