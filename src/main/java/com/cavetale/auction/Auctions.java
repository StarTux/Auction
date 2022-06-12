package com.cavetale.auction;

import com.cavetale.auction.gui.Gui;
import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.core.connect.NetworkServer;
import com.cavetale.core.event.connect.ConnectMessageEvent;
import com.cavetale.sidebar.PlayerSidebarEvent;
import com.cavetale.sidebar.Priority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.*;

/**
 * Manage all active auctions.
 * While every server will maintain an updated instance of this class,
 * only the one manager server will actually edit auctions, accept
 * bids, update player listen types, and send announcements.
 * Auctions can be scheduled from any server, however.
 */
@RequiredArgsConstructor
public final class Auctions implements Listener {
    protected static final String CONNECT_REFRESH = "auction:refresh";
    protected static final String CONNECT_SCHEDULED = "auction:scheduled";
    protected static final String CONNECT_DELIVERED = "auction:delivered";
    private static final Comparator<Auction> END_TIME_COMPARATOR = Comparator
        .comparing(auc -> auc.getAuctionRow().getEndTime());
    private static final Comparator<SQLAuction> CREATED_TIME_COMPARATOR = Comparator
        .comparing(row -> row.getCreatedTime());
    private final AuctionPlugin plugin;
    private final Map<Integer, Auction> auctionMap = new TreeMap<>();
    private HashSet<UUID> deliveries = new HashSet<>();
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkDeliveries, 0L, 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::remindDeliveries, 1200L, 1200L);
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

    public void checkDeliveries() {
        plugin.database.find(SQLDelivery.class)
            .findValuesAsync("owner", UUID.class, uuids -> {
                    deliveries.clear();
                    deliveries.addAll(uuids);
                });
    }

    public void remindDeliveries() {
        for (UUID uuid : deliveries) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            String cmd = "/auc pickup";
            player.sendMessage(join(noSeparators(),
                                    text(tiny("Auction "), DARK_AQUA),
                                    text("There is a delivery waiting for you: "),
                                    text("/auc pickup", AQUA))
                               .hoverEvent(showText(text(cmd, GREEN)))
                               .clickEvent(runCommand(cmd)));
        }
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
                    Auction auction = new Auction(plugin, auctionRow);
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
        if (deliveries.contains(event.getPlayer().getUniqueId())) {
            event.add(plugin, Priority.HIGHEST, List.of(text("You have an", RED, BOLD),
                                                        text("auction delivery", RED, BOLD),
                                                        text("/auc pickup", YELLOW)));
        }
        if (!event.getPlayer().hasPermission("auction.auction")) return;
        if (auctionMap.isEmpty()) return;
        final UUID uuid = event.getPlayer().getUniqueId();
        List<Auction> playerAuctions = getPlayerAuctions(uuid);
        if (playerAuctions.isEmpty()) return;
        List<Component> lines = new ArrayList<>();
        lines.add(join(noSeparators(),
                       text("Current ", AQUA), text("/auc", YELLOW), text("tions", AQUA)));
        Priority prio = Priority.LOW;
        for (int i = 0; i < playerAuctions.size(); i += 1) {
            Auction auction = playerAuctions.get(i);
            if (!auction.isActive()) continue;
            boolean focus = auction.getListenType(uuid).isFocus();
            if (i > 0 && !focus) break;
            if (focus) prio = Priority.HIGH;
            lines.addAll(auction.getSidebarLines(uuid));
        }
        event.add(plugin, prio, lines);
    }

    @EventHandler
    private void onConnectMessage(ConnectMessageEvent event) {
        if (event.getChannel().equals(CONNECT_REFRESH)) {
            int id = Integer.parseInt(event.getPayload());
            refreshAuction(id);
        } else if (event.getChannel().equals(CONNECT_SCHEDULED)) {
            queueEmpty = false;
        } else if (event.getChannel().equals(CONNECT_DELIVERED)) {
            checkDeliveries();
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
        boolean anyLoading = false;
        boolean anyStartedRecently = false;
        auctionMap.values().removeIf(Auction::hasEnded);
        for (Auction auction : auctionMap.values()) {
            if (auction.isLoading()) {
                anyLoading = true;
                continue;
            }
            auction.managerTick();
            if (auction.getAuctionRow().getRemainingDuration().toMinutes() <= 10L) {
                anyEndsWithin = true;
            }
            if (Duration.between(auction.getAuctionRow().getStartTime().toInstant(), Instant.now()).toSeconds() < 60L) {
                anyStartedRecently = true;
            }
        }
        if (!anyLoading && !anyEndsWithin && !anyStartedRecently && !queueEmpty) {
            plugin.getLogger().info("scheduling...");
            schedule();
        }
    }

    public boolean isAwaitingDeliveries(UUID uuid) {
        return deliveries.contains(uuid);
    }
}
