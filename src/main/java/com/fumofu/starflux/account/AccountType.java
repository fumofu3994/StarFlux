package com.fumofu.starflux.account;

public enum AccountType {
    OFFLINE("离线"),
    MICROSOFT("Microsoft 正版"),
    YGGDRASIL("第三方认证");

    private final String displayName;

    AccountType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
