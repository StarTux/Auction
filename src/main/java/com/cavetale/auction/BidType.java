package com.cavetale.auction;

public enum BidType {
    WINNER, // A new winner
    RAISE, // The price was raised
    SILENT;

    public boolean isWinner() {
        return this == WINNER;
    }

    public boolean isRaise() {
        return this == RAISE;
    }

    public boolean isSilent() {
        return this == SILENT;
    }
}
