package com.fumofu.starflux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;

public class OfflineAccountStore {
    private static final String ACCOUNTS_KEY = "offline-accounts";
    private static final String SELECTED_KEY = "selected-offline-account";
    private final Preferences preferences = Preferences.userNodeForPackage(OfflineAccountStore.class);

    public List<String> getAccounts() {
        String stored = preferences.get(ACCOUNTS_KEY, "");
        if (stored.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> accounts = new ArrayList<String>();
        for (String name : stored.split("\\n")) {
            if (!name.isEmpty()) {
                accounts.add(name);
            }
        }
        return accounts;
    }

    public String getSelectedAccount() {
        return preferences.get(SELECTED_KEY, "");
    }

    public void addAccount(String name) {
        List<String> accounts = new ArrayList<String>(getAccounts());
        if (!accounts.contains(name)) {
            accounts.add(name);
            saveAccounts(accounts);
        }
        selectAccount(name);
    }

    public void removeAccount(String name) {
        List<String> accounts = new ArrayList<String>(getAccounts());
        accounts.remove(name);
        saveAccounts(accounts);
        if (name.equals(getSelectedAccount())) {
            preferences.put(SELECTED_KEY, accounts.isEmpty() ? "" : accounts.get(0));
        }
    }

    public void selectAccount(String name) {
        if (!getAccounts().contains(name)) {
            throw new IllegalArgumentException("账号不存在：" + name);
        }
        preferences.put(SELECTED_KEY, name);
    }

    private void saveAccounts(List<String> accounts) {
        StringBuilder stored = new StringBuilder();
        for (String name : accounts) {
            if (stored.length() > 0) {
                stored.append('\n');
            }
            stored.append(name);
        }
        preferences.put(ACCOUNTS_KEY, stored.toString());
    }
}
