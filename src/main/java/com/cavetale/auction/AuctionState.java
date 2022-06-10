package com.cavetale.auction;

public enum AuctionState {
    SCHEDULED,
    ACTIVE,
    ENDED,
    CANCELLED;

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
}
