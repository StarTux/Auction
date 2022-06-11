package com.cavetale.auction;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@RequiredArgsConstructor
public enum AuctionState {
    SCHEDULED(text("Scheduled", BLUE)),
    ACTIVE(text("Active", GREEN)),
    ENDED(text("Ended", DARK_GRAY)),
    CANCELLED(text("Cancelled", DARK_RED));

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
}
