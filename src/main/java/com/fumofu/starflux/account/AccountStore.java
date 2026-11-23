package com.fumofu.starflux.account;

import com.fumofu.starflux.config.LauncherData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AccountStore {
    private static final String ACCOUNTS_KEY = "accounts";
    private static final String SELECTED_KEY = "selected-account";
    private final Map<String, MinecraftSession> sessions = new LinkedHashMap<>();

    public List<Account> getAccounts() {
        String stored = LauncherData.get(ACCOUNTS_KEY, "");
        if (stored.isEmpty()) {
            return importOfflineAccounts();
        }
        JsonArray values;
        try {
            values = JsonParser.parseString(stored).getAsJsonArray();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("启动器账号数据格式无效", exception);
        }
        List<Account> accounts = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject item = value.getAsJsonObject();
            accounts.add(new Account(
                    item.get("id").getAsString(),
                    item.get("name").getAsString(),
                    AccountType.valueOf(item.get("type").getAsString()),
                    item.has("serverUrl") ? item.get("serverUrl").getAsString() : ""));
        }
        return accounts;
    }

    public String getSelectedId() {
        return LauncherData.get(SELECTED_KEY, "");
    }

    public MinecraftSession getSession(String accountId) {
        return sessions.get(accountId);
    }

    public void add(Account account, MinecraftSession session) {
        List<Account> accounts = new ArrayList<>(getAccounts());
        accounts.removeIf(existing -> existing.id.equals(account.id));
        accounts.add(account);
        sessions.put(account.id, session);
        saveAccounts(accounts);
        select(account.id);
    }

    public void remove(String accountId) {
        List<Account> accounts = new ArrayList<>(getAccounts());
        accounts.removeIf(account -> account.id.equals(accountId));
        sessions.remove(accountId);
        saveAccounts(accounts);
        if (accountId.equals(getSelectedId())) {
            LauncherData.put(SELECTED_KEY, accounts.isEmpty() ? "" : accounts.get(0).id);
        }
    }

    public void select(String accountId) {
        if (getAccounts().stream().noneMatch(account -> account.id.equals(accountId))) {
            throw new IllegalArgumentException("账号不存在");
        }
        LauncherData.put(SELECTED_KEY, accountId);
    }

    public Account getSelectedAccount() {
        String id = getSelectedId();
        return getAccounts().stream().filter(account -> account.id.equals(id)).findFirst().orElse(null);
    }

    private List<Account> importOfflineAccounts() {
        String stored = LauncherData.get("offline-accounts", "");
        if (stored.isEmpty()) {
            return Collections.emptyList();
        }
        List<Account> accounts = new ArrayList<>();
        String selectedName = LauncherData.get("selected-offline-account", "");
        for (String name : stored.split("\\n")) {
            if (!name.isEmpty()) {
                accounts.add(new Account(UUID.randomUUID().toString(), name, AccountType.OFFLINE, ""));
            }
        }
        saveAccounts(accounts);
        for (Account account : accounts) {
            if (account.name.equals(selectedName)) {
                LauncherData.put(SELECTED_KEY, account.id);
                break;
            }
        }
        return accounts;
    }

    private void saveAccounts(List<Account> accounts) {
        JsonArray values = new JsonArray();
        for (Account account : accounts) {
            JsonObject item = new JsonObject();
            item.addProperty("id", account.id);
            item.addProperty("name", account.name);
            item.addProperty("type", account.type.name());
            item.addProperty("serverUrl", account.serverUrl);
            values.add(item);
        }
        LauncherData.put(ACCOUNTS_KEY, values.toString());
    }

    public static final class Account {
        private final String id;
        private final String name;
        private final AccountType type;
        private final String serverUrl;

        public Account(String id, String name, AccountType type, String serverUrl) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.serverUrl = serverUrl;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public AccountType getType() {
            return type;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        @Override
        public String toString() {
            return name + " · " + type.getDisplayName();
        }
    }
}
