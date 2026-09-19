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
import org.bukkit.event.player.PlayerQuitEvent;
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
        // YAML 键存在但值为空时 getString 返回 null（默认值只在键缺失时生效），需防御
        baseUrl = getConfig().getString("api-base-url", "http://localhost:8787");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8787";
            getLogger().warning("api-base-url 未配置，已回退为默认值");
        }
        webhookSecret = getConfig().getString("webhook-secret", "");
        if (webhookSecret == null) {
            webhookSecret = "";
        }
        if (webhookSecret.isBlank()) {
            getLogger().warning("webhook-secret 未配置，所有内部请求将被 403 拒绝");
        }
        // 启动时校验地址并去掉尾部斜杠，避免异步线程里 URI.create 抛未捕获异常
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // scheme 必须显式为 http/https：URI.create("api.example.com") 不抛异常但 scheme 为 null，
        // 真正 build 请求时才抛 IllegalArgumentException（在异步任务中静默失败，玩家零反馈）
        String scheme = null;
        try {
            scheme = URI.create(baseUrl).getScheme();
        } catch (IllegalArgumentException e) {
            // 走下方统一的停用分支
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            getLogger().severe("api-base-url 不是合法地址（缺少 http:// 或 https:// 前缀，或含非法字符），插件停用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 超时与冷却可配置（config.yml），下限兜底防止配 0/负数导致请求失效
        int connectSeconds = Math.max(1, getConfig().getInt("connect-timeout-seconds", 5));
        int requestSeconds = Math.max(1, getConfig().getInt("request-timeout-seconds", 10));
        // /v 冷却默认 6 秒，低于后端 12 次/分钟限流上限；0 = 关闭（此时完全依赖后端 429 + resetAt 兜底）
        cooldownMillis = Math.max(0, getConfig().getInt("command-cooldown-seconds", 6)) * 1000L;
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .build();
        requestTimeout = Duration.ofSeconds(requestSeconds);
        getCommand("v").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // 释放 HttpClient 线程资源，避免 /reload 反复启停时线程滞留
        if (http != null) {
            http.close();
        }
    }

    // ---------- /v 验证码核验（文档 3.1） ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
        Bukkit.getScheduler().runTaskAsynchronously(this,
                () -> verifyAsync(player.getUniqueId(), playerName, playerUuid, code));
        return true;
    }

    private void verifyAsync(UUID playerId, String playerName, String playerUuid, String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("playerName", playerName);
        body.addProperty("playerUuid", playerUuid);

        String message;
        try {
            // build 移入 try：地址异常兜底（正常情况已被启动校验拦截），避免异步任务静默失败
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/internal/minecraft/verify"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Secret", webhookSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            message = interpretVerify(resp.statusCode(), resp.body());
        } catch (IllegalArgumentException e) {
            getLogger().severe("核验请求构建失败，服务地址配置错误: " + e.getClass().getSimpleName());
            message = "服务地址配置错误，请联系管理员";
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

    /** 玩家退出时清理 /v 冷却记录，冷却 Map 不随历史玩家数增长。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastVerifyAt.remove(event.getPlayer().getUniqueId());
    }

    private void lookupAsync(UUID playerId, String name) {
        // 路径参数 URL 编码：离线服可能存在特殊字符角色名（对接指南 5.4）
        String message = null;
        try {
            // build 移入 try：地址异常兜底（正常情况已被启动校验拦截），避免异步任务静默失败
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/internal/minecraft/name/"
                            + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(requestTimeout)
                    .header("X-Webhook-Secret", webhookSecret)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            message = interpretLookup(resp.statusCode(), resp.body());
        } catch (IllegalArgumentException e) {
            getLogger().severe("进服反查请求构建失败，服务地址配置错误: " + e.getClass().getSimpleName());
            message = "服务地址配置错误，请联系管理员";
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

    /** 200 → 按 verifiedAt 区分已绑定/未核验；404 未绑定与其他错误 → 静默降级。 */
    private String interpretLookup(int status, String body) {
        if (status == 404) {
            // 未绑定属正常状态，每次进服都提示会打扰玩家，静默跳过
            return null;
        }
        if (status != 200) {
            // 补取信封 requestId 便于与后端对账（成功/失败信封都有该字段）
            JsonObject root = parseEnvelope(body);
            String requestId = root == null ? null : optString(root, "requestId");
            getLogger().warning("进服反查失败，HTTP " + status
                    + (requestId == null ? "" : "，requestId=" + requestId));
            return null;
        }
        JsonObject root = parseEnvelope(body);
        if (root == null || !optBool(root, "success")) {
            getLogger().warning("进服反查响应解析失败");
            return null;
        }
        JsonObject data = optObj(root, "data");
        if (data == null) {
            String requestId = optString(root, "requestId");
            getLogger().warning("进服反查响应缺少 data"
                    + (requestId == null ? "" : "，requestId=" + requestId));
            return null;
        }
        JsonElement verifiedAt = data.get("verifiedAt");
        if (verifiedAt == null || verifiedAt.isJsonNull()) {
            return "您的账号已绑定但尚未完成核验：请回网站获取验证码，在大厅输入 /v <验证码>";
        }
        return "欢迎回来，您的账号已完成绑定";
    }

    // ---------- 响应解释与文案映射（文档 2.2/2.3/3.1） ----------

    /** 先判 HTTP 状态码，再解析信封；分支只依赖稳定错误码 / reason，不解析 message 文案。 */
    private String interpretVerify(int status, String body) {
        JsonObject root = parseEnvelope(body);
        if (status != 200) {
            // 只记状态码便于排障（如 Secret 配错的 403），不含响应体与 Secret；追加信封 requestId 便于对账
            String requestId = root == null ? null : optString(root, "requestId");
            getLogger().warning("核验请求失败，HTTP " + status
                    + (requestId == null ? "" : "，requestId=" + requestId));
            if (status == 429) {
                Long resetAt = optDetailsLong(root, "resetAt");
                if (resetAt != null) {
                    String time = Instant.ofEpochMilli(resetAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    return "操作过于频繁，请于 " + time + " 再试";
                }
            }
            if (status == 400) {
                // VALIDATION_ERROR：请求参数不被后端接受，与「服务不可用」区分开便于定位配置问题
                return "请求参数不被后端接受（检查配置与玩家名格式）";
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
            case "code_invalid" -> "验证码无效或已过期，请回网站重新获取";
            case "too_many_attempts" -> "尝试次数过多，请回网站重新发起绑定";
            case "player_mismatch" -> "当前玩家与发起绑定的账号不一致";
            case "already_bound" -> "该游戏账号已被其他网站用户绑定";
            default -> "核验失败，请稍后再试";
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
