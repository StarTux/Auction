package com.cavetale.auction;

import com.cavetale.core.money.Money;
import com.cavetale.mytems.item.coin.Coin;
import java.time.Duration;
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

    public Component toComponent() {
        Component text = join(separator(space()),
                              text(Money.get().format(price), BLUE),
                              text(showName, LIGHT_PURPLE));
        return text
            .hoverEvent(showText(join(separator(newline()),
                                      join(noSeparators(), text("Auction Fee ", GRAY), Coin.format(price)),
                                      join(noSeparators(), text("Minimum Bid ", GRAY), Coin.format(price)),
                                      join(noSeparators(), text("Duration ", GRAY), Auction.format(duration)),
                                      text(tiny("You will be able to"), GRAY),
                                      text(tiny("place the auction"), GRAY),
                                      text(tiny("items on the next"), GRAY),
                                      text(tiny("screen."), GRAY))))
            .clickEvent(runCommand("/auc startprice " + name()));
    }
}

