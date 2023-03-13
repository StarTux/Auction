package com.cavetale.auction;

import com.cavetale.auction.gui.Gui;
import com.cavetale.auction.sql.SQLAuction;
import com.cavetale.auction.sql.SQLDelivery;
import com.cavetale.auction.sql.SQLLog;
import com.cavetale.auction.sql.SQLPlayerAuction;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.connect.NetworkServer;
import com.cavetale.core.connect.ServerGroup;
import com.cavetale.core.event.connect.ConnectMessageEvent;
import com.cavetale.core.event.hud.PlayerHudEvent;
import com.cavetale.core.event.hud.PlayerHudPriority;
import com.cavetale.core.font.GuiOverlay;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.bossbar.BossBar;
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
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.textOfChildren;
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
    protected static final String CONNECT_REMOVE = "auction:remove";
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
        manage = NetworkServer.current() == NetworkServer.current().getManager();
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
            player.sendMessage(textOfChildren(text(tiny("Auction "), DARK_AQUA),
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

    /**
     * Get all auctions which a player is not actively ignoring.
     */
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

    /**
     * Get all auctions which a player is focusing.
     */
    public List<Auction> getFocusAuctions(UUID uuid) {
        List<Auction> result = new ArrayList<>();
        for (Auction auction : auctionMap.values()) {
            if (!auction.isActive()) continue;
            if (!auction.getListenType(uuid).isFocus()) continue;
            result.add(auction);
        }
        result.sort(END_TIME_COMPARATOR);
        return result;
    }

    private static final List<Component> DELIVERY_SIDEBAR = List.of(text("You have an", RED, BOLD),
                                                                    text("auction delivery", RED, BOLD),
                                                                    text("/auc pickup", YELLOW));
    private static final Component DELIVERY_BOSS_BAR = textOfChildren(text("You have an auction delivery: ", WHITE),
                                                                      text("/auc pickup", YELLOW));

    @EventHandler
    private void onPlayerHud(PlayerHudEvent event) {
        Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        if (deliveries.contains(uuid)) {
            event.sidebar(PlayerHudPriority.HIGH, DELIVERY_SIDEBAR);
            event.bossbar(PlayerHudPriority.HIGH, DELIVERY_BOSS_BAR, BossBar.Color.PINK, BossBar.Overlay.PROGRESS, 1.0f);
        }
        if (!player.hasPermission("auction.auction")) return;
        if (auctionMap.isEmpty()) return;
        List<Auction> playerAuctions = getFocusAuctions(uuid);
        if (playerAuctions.isEmpty()) return;
        List<Component> lines = new ArrayList<>();
        lines.add(textOfChildren(text("/auc", YELLOW), text("tion", AQUA)));
        PlayerHudPriority prio = PlayerHudPriority.LOW;
        for (int i = 0; i < playerAuctions.size(); i += 1) {
            Auction auction = playerAuctions.get(i);
            if (!auction.isActive()) continue;
            boolean focus = auction.getListenType(uuid).isFocus();
            if (i > 0 && !focus) break;
            if (focus) prio = PlayerHudPriority.HIGH;
            lines.addAll(auction.getSidebarLines(uuid));
        }
        event.sidebar(prio, lines);
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
        } else if (event.getChannel().equals(CONNECT_REMOVE)) {
            int id = Integer.parseInt(event.getPayload());
            auctionMap.remove(id);
        }
    }

    public void previewAuction(Player player, SQLAuction row) {
        Inventory inventory = row.parseInventory();
        final int size = inventory.getSize();
        Gui gui = new Gui(plugin)
            .size(size)
            .title(GuiOverlay.BLANK.builder(size, DARK_AQUA)
                   .title(text("Auction Preview #" + row.getId(), WHITE))
                   .build());
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
        gui.setItem(Gui.OUTSIDE, null, click -> {
                if (!click.isLeftClick()) return;
                player.performCommand("auc info " + row.getId());
            });
        gui.open(player);
    }

    public void previewContainer(Player player, SQLAuction row, Inventory inventory) {
        final int size = inventory.getSize();
        Gui gui = new Gui(plugin)
            .size(size)
            .title(GuiOverlay.BLANK.builder(size, DARK_AQUA)
                   .title(text("Auction Preview #" + row.getId(), WHITE))
                   .build());
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

    public List<String> complete() {
        List<String> list = new ArrayList<>(auctionMap.size());
        for (Integer id : auctionMap.keySet()) {
            list.add("" + id);
        }
        return list;
    }

    public void deleteAuction(int id, Consumer<int[]> callback) {
        int[] results = new int[3];
        auctionMap.remove(id);
        plugin.database.find(SQLAuction.class).idEq(id).deleteAsync(r -> results[0] += r);
        plugin.database.find(SQLLog.class).eq("auctionId", id).deleteAsync(r -> results[1] += r);
        plugin.database.find(SQLPlayerAuction.class).eq("auctionId", id).deleteAsync(r -> {
                Connect.get().broadcastMessageToAll(ServerGroup.current(), Auctions.CONNECT_REMOVE, "" + id);
                results[2] += r;
                callback.accept(results);
            });
    }
}
