package com.cavetale.auction;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@RequiredArgsConstructor
public enum AuctionState {
    SCHEDULED(text(tiny("Scheduled"), BLUE)),
    ACTIVE(text(tiny("Active"), GREEN)),
    ENDED(text(tiny("Ended"), DARK_GRAY)),
    CANCELLED(text(tiny("Cancelled"), DARK_RED));

    public final Component displayName;

    public boolean isScheduled() {
        return this == SCHEDULED;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isEnded() {
        return this == ENDED;
    }

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    public boolean isListenable() {
        return this == SCHEDULED
            || this == ACTIVE;
    }

    public boolean isCancellable() {
        return this == SCHEDULED
            || this == ACTIVE;
    }
}
