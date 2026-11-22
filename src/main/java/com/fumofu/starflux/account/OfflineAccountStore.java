package com.fumofu.starflux.account;

import com.fumofu.starflux.config.LauncherData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OfflineAccountStore {
    private static final String ACCOUNTS_KEY = "offline-accounts";
    private static final String SELECTED_KEY = "selected-offline-account";

    public List<String> getAccounts() {
        String stored = LauncherData.get(ACCOUNTS_KEY, "");
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
        return LauncherData.get(SELECTED_KEY, "");
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
            LauncherData.put(SELECTED_KEY, accounts.isEmpty() ? "" : accounts.get(0));
        }
    }

    public void selectAccount(String name) {
        if (!getAccounts().contains(name)) {
            throw new IllegalArgumentException("账号不存在：" + name);
        }
        LauncherData.put(SELECTED_KEY, name);
    }

    private void saveAccounts(List<String> accounts) {
        StringBuilder stored = new StringBuilder();
        for (String name : accounts) {
            if (stored.length() > 0) {
                stored.append('\n');
            }
            stored.append(name);
        }
        LauncherData.put(ACCOUNTS_KEY, stored.toString());
    }
}
