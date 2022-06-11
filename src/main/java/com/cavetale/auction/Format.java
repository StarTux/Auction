package com.cavetale.auction;

import com.cavetale.core.font.Unicode;
import com.cavetale.mytems.item.coin.Coin;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.*;

public final class Format {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy MMM dd HH:mm:ss");

    public static TextColor invert(TextColor color, boolean dark) {
        return dark
            ? TextColor.color(255 - color.red(),
                              255 - color.green(),
                              255 - color.blue())
            : color;
    }

    public static String date(Date date) {
        return DATE_FORMAT.format(date);
    }

    public static Component duration(Duration duration, boolean dark) {
        final long seconds = duration.toSeconds();
        final long minutes = duration.toMinutes();
        final long hours = duration.toHours();
        final long days = duration.toDays();
        ArrayList<Component> list = new ArrayList<>(8);
        TextColor gray = GRAY;
        if (days > 0) {
            list.add(text(days));
            list.add(text(Unicode.SMALLD.character, gray));
        }
        if (hours > 0) {
            list.add(text(hours % 24));
            list.add(text(Unicode.SMALLH.character, gray));
        }
        if (minutes > 0) {
            list.add(text(minutes % 60));
            list.add(text(Unicode.SMALLM.character, gray));
        }
        list.add(text(seconds % 60));
        list.add(text(Unicode.SMALLS.character, gray));
        Component title = join(noSeparators(), list).color(invert(YELLOW, dark));
        Date then = Date.from(Instant.now().plus(duration));
        return title
            .hoverEvent(showText(join(separator(newline()),
                                      join(noSeparators(), list).color(YELLOW),
                                      text(date(then), GRAY, ITALIC),
                                      join(noSeparators(), text(days, YELLOW), text(" Days", GRAY)),
                                      join(noSeparators(), text(hours, YELLOW), text(" Hours", GRAY)),
                                      join(noSeparators(), text(minutes, YELLOW), text(" Minutes", GRAY)),
                                      join(noSeparators(), text(seconds, YELLOW), text(" Seconds", GRAY)))));
    }

    public static Component duration(Duration duration) {
        return duration(duration, false);
    }

    public static Component money(double amount, boolean dark) {
        return dark
            ? Coin.format(amount).color(DARK_GREEN)
            : Coin.format(amount);
    }

    public static Component money(double amount) {
        return money(amount, false);
    }

    private Format() { }
}
