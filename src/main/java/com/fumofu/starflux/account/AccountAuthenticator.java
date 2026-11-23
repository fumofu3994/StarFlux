package com.fumofu.starflux.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

public final class AccountAuthenticator {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public MinecraftSession authenticateYggdrasil(String serverUrl, String username, char[] password)
            throws IOException, InterruptedException {
        URI base = validateServerUrl(serverUrl);
        JsonObject metadata = requestJson("GET", base.resolve("api/yggdrasil"), null, null);
        if (!metadata.has("meta")) {
            throw new IOException("该地址不是兼容 Authlib-Injector 的认证服务器");
        }

        JsonObject payload = new JsonObject();
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        payload.add("agent", agent);
        payload.addProperty("username", username);
        payload.addProperty("password", new String(password));
        payload.addProperty("requestUser", true);
        payload.addProperty("clientToken", java.util.UUID.randomUUID().toString());
        JsonObject response = requestJson("POST", base.resolve("authserver/authenticate"), payload, null);
        JsonObject profile = response.has("selectedProfile") && response.get("selectedProfile").isJsonObject()
                ? response.getAsJsonObject("selectedProfile") : null;
        if (profile == null) {
            throw new IOException("认证服务器未返回已选择的 Minecraft 角色");
        }
        String id = requiredString(profile, "id");
        String name = requiredString(profile, "name");
        String token = requiredString(response, "accessToken");
        String properties = response.has("user") && response.getAsJsonObject("user").has("properties")
                ? response.getAsJsonObject("user").get("properties").toString() : "{}";
        return new MinecraftSession(name, normalizeUuid(id), token, "mojang", "", "", properties);
    }

    public MinecraftSession authenticateMicrosoft(String clientId, Consumer<DeviceCode> deviceCodeListener)
            throws IOException, InterruptedException {
        if (clientId == null || !clientId.trim().matches("[A-Za-z0-9-]{20,80}")) {
            throw new IOException("请先填写有效的 Microsoft OAuth 客户端 ID");
        }
        String encodedClientId = URLEncoder.encode(clientId.trim(), StandardCharsets.UTF_8);
        String scope = URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);
        JsonObject device = requestJson("POST",
                URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"),
                null, "client_id=" + encodedClientId + "&scope=" + scope);
        String deviceCode = requiredString(device, "device_code");
        String userCode = requiredString(device, "user_code");
        String verificationUri = device.has("verification_uri")
                ? device.get("verification_uri").getAsString() : "https://microsoft.com/devicelogin";
        long expiresAt = System.currentTimeMillis() + device.get("expires_in").getAsLong() * 1000L;
        int interval = device.has("interval") ? device.get("interval").getAsInt() : 5;
        deviceCodeListener.accept(new DeviceCode(userCode, verificationUri));

        JsonObject oauth = null;
        while (System.currentTimeMillis() < expiresAt) {
            Thread.sleep(Math.max(1, interval) * 1000L);
            JsonObject response = requestJson("POST",
                    URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"),
                    null, "grant_type=" + URLEncoder.encode(
                            "urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                            + "&client_id=" + encodedClientId
                            + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8));
            if (response.has("access_token")) {
                oauth = response;
                break;
            }
            String error = response.has("error") ? response.get("error").getAsString() : "";
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }
            if ("authorization_declined".equals(error) || "expired_token".equals(error)) {
                throw new IOException("Microsoft 登录已取消或验证码已过期");
            }
            throw new IOException(response.has("error_description")
                    ? response.get("error_description").getAsString() : "Microsoft OAuth 登录失败：" + error);
        }
        if (oauth == null) {
            throw new IOException("Microsoft 登录验证码已过期，请重新登录");
        }

        String microsoftToken = requiredString(oauth, "access_token");
        JsonObject xbox = requestJson("POST",
                URI.create("https://user.auth.xboxlive.com/user/authenticate"),
                xboxUserRequest(microsoftToken), null);
        String xboxToken = requiredString(xbox, "Token");
        JsonObject xstsRequest = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xboxToken);
        properties.add("UserTokens", userTokens);
        xstsRequest.add("Properties", properties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        JsonObject xsts = requestJson("POST",
                URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"), xstsRequest, null);
        String xstsToken = requiredString(xsts, "Token");
        String userHash = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();
        JsonObject minecraftRequest = new JsonObject();
        minecraftRequest.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject minecraftAuth = requestJson("POST",
                URI.create("https://api.minecraftservices.com/authentication/login_with_xbox"),
                minecraftRequest, null);
        String minecraftToken = requiredString(minecraftAuth, "access_token");

        JsonObject entitlements = requestJson("GET",
                URI.create("https://api.minecraftservices.com/entitlements/mcstore"),
                null, null, minecraftToken);
        JsonArray items = entitlements.getAsJsonArray("items");
        if (items == null || items.isEmpty()) {
            throw new IOException("此 Microsoft 账号没有 Minecraft: Java Edition 的游戏授权");
        }
        JsonObject profile = requestJson("GET",
                URI.create("https://api.minecraftservices.com/minecraft/profile"),
                null, null, minecraftToken);
        String uuid = normalizeUuid(requiredString(profile, "id"));
        String name = requiredString(profile, "name");
        return new MinecraftSession(name, uuid, minecraftToken, "msa", clientId.trim(),
                userHash, profile.has("skins") ? profile.get("skins").toString() : "{}");
    }

    private static JsonObject xboxUserRequest(String microsoftToken) {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + microsoftToken);
        JsonObject request = new JsonObject();
        request.add("Properties", properties);
        request.addProperty("RelyingParty", "http://auth.xboxlive.com");
        request.addProperty("TokenType", "JWT");
        return request;
    }

    private static URI validateServerUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean localHttp = "http".equalsIgnoreCase(scheme) && host != null
                    && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1"));
            if (!("https".equalsIgnoreCase(scheme) || localHttp) || host == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("认证服务器必须使用 HTTPS（本机 localhost 可使用 HTTP）");
            }
            String text = uri.toString();
            return URI.create(text.endsWith("/") ? text : text + "/");
        } catch (IllegalArgumentException exception) {
            throw new IOException("认证服务器地址无效", exception);
        }
    }

    private static JsonObject requestJson(String method, URI uri, JsonObject json, String form)
            throws IOException, InterruptedException {
        return requestJson(method, uri, json, form, null);
    }

    private static JsonObject requestJson(String method, URI uri, JsonObject json, String form, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (form != null) {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(form));
        } else if (json != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.toString()));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HTTP.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("认证服务返回了无法识别的响应（HTTP " + response.statusCode() + "）", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = body.has("error") ? body.get("error").getAsString() : "";
            if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                return body;
            }
            String message = body.has("errorMessage") ? body.get("errorMessage").getAsString()
                    : body.has("error_description") ? body.get("error_description").getAsString()
                    : body.has("error") ? body.get("error").getAsString()
                    : "HTTP " + response.statusCode();
            throw new IOException("登录失败：" + message);
        }
        return body;
    }

    private static String requiredString(JsonObject value, String key) throws IOException {
        JsonElement element = value.get(key);
        if (element == null || element.isJsonNull() || element.getAsString().isBlank()) {
            throw new IOException("认证服务响应缺少 " + key);
        }
        return element.getAsString();
    }

    private static String normalizeUuid(String id) throws IOException {
        String uuid = id.replace("-", "");
        if (!uuid.matches("[0-9a-fA-F]{32}")) {
            throw new IOException("认证服务返回了无效的玩家 UUID");
        }
        return uuid;
    }

    public static boolean openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
        return false;
    }

    public record DeviceCode(String userCode, String verificationUri) {
    }
}
