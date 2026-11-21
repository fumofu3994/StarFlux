package com.fumofu.starflux;

import java.io.IOException;
import java.net.URL;

public enum MirrorSource {
    MOJANG("Mojang（官方）"),
    BMCLAPI("BMCLAPI");

    private static final String BMCLAPI_HOST = "bmclapi2.bangbang93.com";
    private final String displayName;

    MirrorSource(String displayName) {
        this.displayName = displayName;
    }

    public String resolveUrl(String originalUrl) throws IOException {
        URL url = new URL(originalUrl);
        if (this == MOJANG) {
            return originalUrl;
        }

        String host = url.getHost().toLowerCase();
        String path = url.getPath();
        if ("resources.download.minecraft.net".equals(host)) {
            path = "/assets" + path;
        } else if ("libraries.minecraft.net".equals(host)) {
            path = "/maven" + path;
        } else if (!"piston-meta.mojang.com".equals(host)
                && !"piston-data.mojang.com".equals(host)
                && !"launcher.mojang.com".equals(host)
                && !BMCLAPI_HOST.equals(host)) {
            return originalUrl;
        }

        try {
            return new URL("https", BMCLAPI_HOST, path + queryAndFragment(url)).toExternalForm();
        } catch (IOException exception) {
            throw new IOException("无法生成 BMCLAPI 下载地址：" + originalUrl, exception);
        }
    }

    private static String queryAndFragment(URL url) {
        StringBuilder suffix = new StringBuilder();
        if (url.getQuery() != null) {
            suffix.append('?').append(url.getQuery());
        }
        if (url.getRef() != null) {
            suffix.append('#').append(url.getRef());
        }
        return suffix.toString();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
