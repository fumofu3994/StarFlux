package com.fumofu.starflux.account;

public final class MinecraftSession {
    private final String playerName;
    private final String uuid;
    private final String accessToken;
    private final String userType;
    private final String clientId;
    private final String xuid;
    private final String userProperties;

    public MinecraftSession(String playerName, String uuid, String accessToken, String userType,
                            String clientId, String xuid, String userProperties) {
        this.playerName = playerName;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.userType = userType;
        this.clientId = clientId;
        this.xuid = xuid;
        this.userProperties = userProperties;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getUuid() {
        return uuid;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUserType() {
        return userType;
    }

    public String getClientId() {
        return clientId;
    }

    public String getXuid() {
        return xuid;
    }

    public String getUserProperties() {
        return userProperties;
    }
}
