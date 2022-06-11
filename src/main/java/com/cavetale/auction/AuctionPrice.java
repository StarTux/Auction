package com.cavetale.auction;

import com.cavetale.mytems.item.coin.Coin;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@RequiredArgsConstructor
public enum AuctionPrice {
    B("5m", Duration.ofMinutes(5), 100.0),
    C("10m", Duration.ofMinutes(10), 200.0),
    D("1h", Duration.ofHours(1), 300.0),
    E("12h", Duration.ofHours(12), 400.0),
    F("24h", Duration.ofHours(24), 500.0);

    public final String showName;
    public final Duration duration;
    public final double price;

    public Component toBookComponent() {
        Component text = join(separator(space()),
                              Format.money(price, true).hoverEvent(null).clickEvent(null),
                              Format.duration(duration, true).hoverEvent(null).clickEvent(null));
        Date then = Date.from(Instant.now().plus(duration));
        return text
            .hoverEvent(showText(join(separator(newline()),
                                      text(Format.date(then), GRAY),
                                      join(noSeparators(), text("Auction Fee ", GRAY), Coin.format(price)),
                                      join(noSeparators(), text("Minimum Bid ", GRAY), Coin.format(price)),
                                      join(noSeparators(), text("Duration ", GRAY), Format.duration(duration)),
                                      space(),
                                      text(tiny("You will be able to"), DARK_GRAY),
                                      text(tiny("place the auction"), DARK_GRAY),
                                      text(tiny("items on the next"), DARK_GRAY),
                                      text(tiny("screen."), DARK_GRAY))))
            .clickEvent(runCommand("/auc startprice " + name()));
    }
}

