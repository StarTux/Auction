package com.cavetale.auction;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ListenType {
    DEFAULT(0),
    FOCUS(1),
    IGNORE(-1);

    @Getter public final int priority;

    public boolean isFocus() {
        return this == FOCUS;
    }

    public boolean isIgnore() {
        return this == IGNORE;
    }

    public boolean doesEntail(ListenType other) {
        return priority >= other.priority;
    }
}
