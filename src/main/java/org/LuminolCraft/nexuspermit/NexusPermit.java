package org.LuminolCraft.nexuspermit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NexusPermit extends JavaPlugin implements CommandExecutor, Listener {

    private String baseUrl;
    private String webhookSecret;
    private HttpClient http;
    private Duration requestTimeout;
    /** /v 冷却（毫秒），0 = 关闭；仅主线程读写 */
    private long cooldownMillis;
    private final Map<UUID, Long> lastVerifyAt = new HashMap<>();

    private final Gson gson = new Gson();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        baseUrl = getConfig().getString("api-base-url", "http://localhost:8787");
        webhookSecret = getConfig().getString("webhook-secret", "");
        if (webhookSecret.isBlank()) {
            getLogger().warning("webhook-secret 未配置，所有内部请求将被 403 拒绝");
        }
        // 超时与冷却可配置（config.yml），下限兜底防止配 0/负数导致请求失效
        int connectSeconds = Math.max(1, getConfig().getInt("connect-timeout-seconds", 5));
        int requestSeconds = Math.max(1, getConfig().getInt("request-timeout-seconds", 10));
        cooldownMillis = Math.max(0, getConfig().getInt("command-cooldown-seconds", 5)) * 1000L;
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .build();
        requestTimeout = Duration.ofSeconds(requestSeconds);
        getCommand("v").setExecutor(this);
        getCommand("mcinfo").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ---------- /v 验证码核验（文档 3.1） ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /mcinfo：查看自身账号信息（纯本地，不联网）
        if (command.getName().equalsIgnoreCase("mcinfo")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("只有玩家可以使用 /mcinfo");
                return true;
            }
            sendAccountInfo(player);
            return true;
        }
        // /v 验证码核验
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以使用 /v");
            return true;
        }
        if (args.length != 1 || args[0].length() < 4 || args[0].length() > 12) {
            player.sendMessage("用法：/v <验证码>（网站绑定页面获取）");
            return true;
        }
        // 本地冷却：防止连点刷验证码，浪费后端仅有的 5 次尝试机会
        if (cooldownMillis > 0) {
            long now = System.currentTimeMillis();
            Long last = lastVerifyAt.get(player.getUniqueId());
            if (last != null && now - last < cooldownMillis) {
                long remain = (cooldownMillis - (now - last) + 999) / 1000;
                player.sendMessage("操作过于频繁，请 " + remain + " 秒后再试");
                return true;
            }
            lastVerifyAt.put(player.getUniqueId(), now);
        }
        // 权威身份一律取自 Player 对象，绝不采信聊天输入
        final String code = args[0];
        final String playerName = player.getName();
        final String playerUuid = player.getUniqueId().toString();

        // HTTP 不进主线程（对接指南第 4 节）
        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                verifyAsync(player.getUniqueId(), playerName, playerUuid, code));
        return true;
    }

    /** /mcinfo：展示玩家自己的权威身份（与网站绑定所用一致），并附防泄露提醒。 */
    private void sendAccountInfo(Player player) {
        player.sendMessage("=== 你的游戏账号信息 ===");
        player.sendMessage("角色名: " + player.getName());
        player.sendMessage("UUID: " + player.getUniqueId().toString());
        player.sendMessage("提示：以上信息用于网站账号绑定与核验，请勿泄露给他人，谨防账号被冒绑或盗用。");
    }

    private void verifyAsync(UUID playerId, String playerName, String playerUuid, String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("playerName", playerName);
        body.addProperty("playerUuid", playerUuid);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/internal/minecraft/verify"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Webhook-Secret", webhookSecret)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        String message;
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            message = interpretVerify(resp.statusCode(), resp.body());
        } catch (IOException e) {
            // 网络层失败可安全重试（code 未被消费），日志只记异常类名，不含 code 与 Secret
            getLogger().warning("核验请求网络异常: " + e.getClass().getSimpleName());
            message = "网络异常，请稍后再试 /v " + code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("核验请求被中断: " + e.getClass().getSimpleName());
            message = "网络异常，请稍后再试 /v " + code;
        }

        // 回主线程后再操作玩家（Bukkit API 非线程安全），并防玩家已离线
        final String msg = message;
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                p.sendMessage(msg);
            }
        });
    }

    // ---------- 进服反查绑定（文档 3.2） ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        final String name = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> lookupAsync(playerId, name));
    }

    private void lookupAsync(UUID playerId, String name) {
        // 路径参数 URL 编码：离线服可能存在特殊字符角色名（对接指南 5.4）
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/internal/minecraft/name/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .header("X-Webhook-Secret", webhookSecret)
                .GET()
                .build();

        String message = null;
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            message = interpretLookup(resp.statusCode(), resp.body());
        } catch (IOException e) {
            getLogger().warning("进服反查网络异常: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("进服反查被中断: " + e.getClass().getSimpleName());
        }

        if (message == null) {
            return; // 静默降级：其他错误不给玩家发消息
        }
        final String msg = message;
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                p.sendMessage(msg);
            }
        });
    }

    /** 200 → 按 verifiedAt 区分已绑定/未核验；404 → 未绑定引导；其他 → 静默降级。 */
    private String interpretLookup(int status, String body) {
        if (status == 404) {
            return "你的 MC 账号还未绑定网站账号：请到网站个人中心绑定 MC 账号，获取验证码后在大厅输入 /v <验证码>";
        }
        if (status != 200) {
            getLogger().warning("进服反查失败，HTTP " + status);
            return null;
        }
        JsonObject root = parseEnvelope(body);
        if (root == null || !optBool(root, "success")) {
            getLogger().warning("进服反查响应解析失败");
            return null;
        }
        JsonObject data = optObj(root, "data");
        if (data == null) {
            getLogger().warning("进服反查响应缺少 data");
            return null;
        }
        JsonElement verifiedAt = data.get("verifiedAt");
        if (verifiedAt == null || verifiedAt.isJsonNull()) {
            return "你的账号已绑定但尚未完成核验：请回网站获取验证码，在大厅输入 /v <验证码>";
        }
        return "欢迎回来，你的账号已完成绑定";
    }

    // ---------- 响应解释与文案映射（文档 2.2/2.3/3.1） ----------

    /** 先判 HTTP 状态码，再解析信封；分支只依赖稳定错误码 / reason，不解析 message 文案。 */
    private String interpretVerify(int status, String body) {
        JsonObject root = parseEnvelope(body);
        if (status != 200) {
            if (status == 429) {
                Long resetAt = optDetailsLong(root, "resetAt");
                if (resetAt != null) {
                    String time = Instant.ofEpochMilli(resetAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    return "操作过于频繁，请于 " + time + " 再试";
                }
            }
            return "绑定服务暂时不可用（" + status + "），请联系管理员";
        }
        if (root == null || !optBool(root, "success")) {
            return "绑定服务暂时不可用（" + status + "），请联系管理员";
        }
        JsonObject data = optObj(root, "data");
        if (data == null) {
            return "核验失败，请稍后再试";
        }
        if (optBool(data, "verified")) {
            return "绑定成功！";
        }
        String reason = optString(data, "reason");
        return switch (reason == null ? "" : reason) {
            case "code_invalid"      -> "验证码无效或已过期，请回网站重新获取";
            case "too_many_attempts" -> "尝试次数过多，请回网站重新发起绑定";
            case "player_mismatch"   -> "当前玩家与发起绑定的账号不一致";
            case "already_bound"     -> "该游戏账号已被其他网站用户绑定";
            default                  -> "核验失败，请稍后再试";
        };
    }

    // ---------- Gson 安全取值（解析异常不外抛，按失败文案兜底） ----------

    private JsonObject parseEnvelope(String body) {
        try {
            JsonElement el = gson.fromJson(body, JsonElement.class);
            return el instanceof JsonObject obj ? obj : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static boolean optBool(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsBoolean();
    }

    private static JsonObject optObj(JsonObject obj, String key) {
        JsonElement el = obj == null ? null : obj.get(key);
        return el instanceof JsonObject o ? o : null;
    }

    /** 读取 error.details.resetAt（429 限流可重试时间，epoch 毫秒）。 */
    private static Long optDetailsLong(JsonObject root, String key) {
        JsonObject error = optObj(root, "error");
        JsonObject details = optObj(error, "details");
        if (details == null) {
            return null;
        }
        JsonElement el = details.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
