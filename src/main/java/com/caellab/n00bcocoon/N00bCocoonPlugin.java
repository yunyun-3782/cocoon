/*
 * N00bCocoon - 新手茧
 * 低价值新人的保护壳，依赖DeathDrop价值体系
 * Copyright (c) 2025 Yunyun(云云)
 * Source: https://github.com/yunyun-3782/cocoon
 *
 * CaelLab BY-SA Code License
 * https://caellab.com/license/bysa-code
 */
package com.caellab.n00bcocoon;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class N00bCocoonPlugin extends JavaPlugin {

    private static N00bCocoonPlugin instance;

    // === 玩家数据 ===
    private File playerDataFile;
    private YamlConfiguration playerDataConfig;
    private final Map<UUID, Long> firstJoinTimes = new ConcurrentHashMap<>();
    private final Set<UUID> revokedPlayers = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> pendingExpireMessages = Collections.synchronizedSet(new HashSet<>());

    // === 保护状态跟踪（用于检测状态变化弹Title） ===
    private final Map<UUID, Boolean> lastProtectionStatus = new ConcurrentHashMap<>();

    // === 认证检测器（防止玩家离线后任务还跑） ===
    private final Map<UUID, BukkitRunnable> authCheckers = new ConcurrentHashMap<>();

    // === 配置项 ===
    private int valueThreshold = 5000;
    private int timeThresholdHours = 48;
    private double pvpDamageMultiplier = 0.5;
    private int pvpMaxDamage = 10;
    private String prefix = "&a&l[新手茧] &r";
    private final Set<String> hostileMobNames = new HashSet<>();

    // === 消息 ===
    private String msgProtected;
    private String msgProtectionExpired;
    private String msgStatusProtected;
    private String msgStatusExpired;
    private String msgStatusValue;
    private String msgStatusTime;
    private String msgValueInfo;
    private String msgAdminReloaded;
    private String msgAdminCheck;

    // === 认证权限（兼容各种登录插件） ===
    private static final List<String> AUTH_PERMISSIONS = Arrays.asList(
        "caellab.authenticated",   // CaelLabAuth
        "authme.login",            // AuthMe
        "nlogin.authenticated",    // NLogin
        "auth.authenticated",      // 通用
        " essentials.signs.use",   // Essentials（部分服用来判断已登录）
        "luckperms.*"              // 如果没有任何登录插件，直接放行
    );

    // === 内置物品价值表（DeathDrop fallback） ===
    private final Map<String, Integer> builtinValues = new HashMap<>();

    public static N00bCocoonPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadConfig();
        initBuiltinValues();
        loadPlayerData();

        getServer().getPluginManager().registerEvents(new N00bCocoonListener(this), this);
        getCommand("cocoon").setExecutor(new CocoonCommand(this));

        // 启动保护状态巡检（每60秒，检测是否刚好过期）
        startProtectionCheckTask();

        getLogger().info("N00bCocoon v" + getDescription().getVersion()
                + " | threshold=" + valueThreshold + "pts/" + timeThresholdHours + "h"
                + " | mobs=" + hostileMobNames.size());
    }

    @Override
    public void onDisable() {
        // 取消所有认证检测器
        for (BukkitRunnable task : authCheckers.values()) {
            task.cancel();
        }
        authCheckers.clear();
        savePlayerData();
        getLogger().info("N00bCocoon disabled");
    }

    // ================================================================
    //  保护状态巡检任务（每60秒跑一次，检测刚好过期的情况）
    // ================================================================

    private void startProtectionCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    boolean currentStatus = isProtected(player);
                    Boolean lastStatus = lastProtectionStatus.get(player.getUniqueId());

                    // 状态从「保护中」变为「无保护」→ 弹Title
                    if (lastStatus != null && lastStatus && !currentStatus) {
                        sendExpireTitle(player);
                    }

                    lastProtectionStatus.put(player.getUniqueId(), currentStatus);
                }
            }
        }.runTaskTimer(this, 1200L, 1200L); // 60秒
    }

    // ================================================================
    //  认证检测（轮询权限，检测到就回调）
    // ================================================================

    /**
     * 启动认证检测器，每1秒检查一次，直到玩家认证成功或超时
     *
     * @param player   玩家
     * @param callback 认证成功后的回调
     */
    public void startAuthChecker(Player player, Runnable callback) {
        UUID uuid = player.getUniqueId();

        // 取消旧的检测器（如果存在）
        cancelAuthChecker(uuid);

        BukkitRunnable task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;

                // 玩家下线 → 取消
                if (!player.isOnline()) {
                    cancel();
                    authCheckers.remove(uuid);
                    return;
                }

                // 超时15秒 → 放行（兜底，防止登录插件没装或没权限节点）
                if (ticks > 15) {
                    cancel();
                    authCheckers.remove(uuid);
                    callback.run();
                    return;
                }

                // 检测认证权限
                if (isAuthenticated(player)) {
                    cancel();
                    authCheckers.remove(uuid);
                    callback.run();
                }
            }
        };

        authCheckers.put(uuid, task);
        task.runTaskTimer(this, 20L, 20L); // 每1秒
    }

    /**
     * 取消玩家的认证检测器
     */
    public void cancelAuthChecker(UUID uuid) {
        BukkitRunnable old = authCheckers.remove(uuid);
        if (old != null) {
            old.cancel();
        }
    }

    /**
     * 检查玩家是否已认证（遍历所有已知登录插件权限节点）
     */
    public boolean isAuthenticated(Player player) {
        for (String perm : AUTH_PERMISSIONS) {
            if (player.hasPermission(perm)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================
    //  通知方法
    // ================================================================

    /**
     * 认证通过后调用：检查是否有待弹Title + 发送保护通知
     */
    public void sendPostAuthNotifications(Player player) {
        UUID uuid = player.getUniqueId();

        // 1. 先检查有没有排队的过期Title
        if (pendingExpireMessages.contains(uuid)) {
            // 延迟1秒再弹，等玩家完全加载
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        sendExpireTitle(player);
                    }
                }
            }.runTaskLater(N00bCocoonPlugin.getInstance(), 20L);
            pendingExpireMessages.remove(uuid);
            savePlayerData();
        }

        // 2. 如果当前还受保护 → 发送保护通知
        if (isProtected(player)) {
            long remaining = getProtectionRemainingMs(player);
            String timeStr = formatRemainingTime(remaining);

            // 聊天消息
            player.sendMessage(colorize(getPrefix() + msgProtected));
            player.sendMessage(colorize(
                    "  &7保护剩余: &e" + timeStr + " &7| 价值上限: &e" + valueThreshold + " &7点"));

            // Title（延迟1秒弹，视觉效果更好）
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.sendTitle(
                                colorize("&a&l§m--- &r&a新手茧 &a&l§m---"),
                                colorize("&7保护剩余: &e" + timeStr + " &7| 价值上限: &e" + valueThreshold + " &7点"),
                                10, 50, 15
                        );
                    }
                }
            }.runTaskLater(N00bCocoonPlugin.getInstance(), 30L); // 1.5秒
        }

        // 3. 记录当前保护状态
        lastProtectionStatus.put(uuid, isProtected(player));
    }

    /**
     * 发送保护过期Title（全屏弹出）
     */
    public void sendExpireTitle(Player player) {
        player.sendTitle(
                colorize("&c&l§m--- &r&c新手茧保护 &c&l§m---"),
                colorize("&7保护已消退，小心行事！"),
                10, 60, 20
        );
        player.sendMessage(colorize(prefix + msgProtectionExpired));
    }

    // ================================================================
    //  撤销 / 恢复保护
    // ================================================================

    /**
     * 撤销玩家新手保护（/cocoon ungive）
     */
    public void revokePlayer(UUID uuid) {
        revokedPlayers.add(uuid);
        savePlayerData();

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            sendExpireTitle(player);
            lastProtectionStatus.put(uuid, false);
        } else {
            // 不在线 → 排队等上线再弹
            pendingExpireMessages.add(uuid);
            savePlayerData();
        }
    }

    /**
     * 恢复玩家新手保护（/cocoon give）
     */
    public void unrevokePlayer(UUID uuid) {
        revokedPlayers.remove(uuid);
        pendingExpireMessages.remove(uuid);
        // 重置首次加入时间为现在，让保护重新生效
        firstJoinTimes.put(uuid, System.currentTimeMillis());
        lastProtectionStatus.put(uuid, true);
        savePlayerData();

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            long remaining = getProtectionRemainingMs(player);
            String timeStr = formatRemainingTime(remaining);
            player.sendMessage(colorize(prefix + "&a新手茧保护已恢复！"));
            player.sendTitle(
                    colorize("&a&l§m--- &r&a新手茧 &a&l§m---"),
                    colorize("&7保护剩余: &e" + timeStr + " &7| 价值上限: &e" + valueThreshold + " &7点"),
                    10, 50, 15
            );
        }
    }

    public boolean isRevoked(UUID uuid) {
        return revokedPlayers.contains(uuid);
    }

    // ================================================================
    //  玩家数据（首次加入时间 / 撤销 / 待弹消息）
    // ================================================================

    private void loadPlayerData() {
        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        if (!playerDataFile.exists()) {
            getDataFolder().mkdirs();
            try {
                playerDataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("无法创建playerdata.yml: " + e.getMessage());
            }
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);

        var section = playerDataConfig.getConfigurationSection("players");
        if (section != null) {
            for (String uuidStr : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    long time = section.getLong(uuidStr + ".first-join", 0);
                    if (time > 0) {
                        firstJoinTimes.put(uuid, time);
                    }
                    if (section.getBoolean(uuidStr + ".revoked", false)) {
                        revokedPlayers.add(uuid);
                    }
                    if (section.getBoolean(uuidStr + ".pending-expire", false)) {
                        pendingExpireMessages.add(uuid);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        getLogger().info("已加载 " + firstJoinTimes.size() + " 个玩家数据");
    }

    public void savePlayerData() {
        if (playerDataConfig == null) return;
        var section = playerDataConfig.createSection("players");
        for (var entry : firstJoinTimes.entrySet()) {
            UUID uuid = entry.getKey();
            String path = uuid.toString();
            section.set(path + ".first-join", entry.getValue());
            section.set(path + ".revoked", revokedPlayers.contains(uuid));
            section.set(path + ".pending-expire", pendingExpireMessages.contains(uuid));
        }
        try {
            playerDataConfig.save(playerDataFile);
        } catch (IOException e) {
            getLogger().warning("保存playerdata.yml失败: " + e.getMessage());
        }
    }

    // ================================================================
    //  保护判定
    // ================================================================

    public long getFirstJoinTime(UUID uuid) {
        return firstJoinTimes.computeIfAbsent(uuid, k -> System.currentTimeMillis());
    }

    public void recordFirstJoin(UUID uuid) {
        if (!firstJoinTimes.containsKey(uuid)) {
            firstJoinTimes.put(uuid, System.currentTimeMillis());
            savePlayerData();
        }
    }

    public boolean isProtected(Player player) {
        if (player.hasPermission("n00bcocoon.bypass")) return true;
        if (isRevoked(player.getUniqueId())) return false;

        UUID uuid = player.getUniqueId();
        long firstJoin = getFirstJoinTime(uuid);
        long now = System.currentTimeMillis();
        long thresholdMs = (long) timeThresholdHours * 3600L * 1000L;

        boolean withinTime = (now - firstJoin) < thresholdMs;
        boolean belowValue = getTotalValue(player) < valueThreshold;

        return withinTime && belowValue;
    }

    public long getProtectionRemainingMs(Player player) {
        UUID uuid = player.getUniqueId();
        long firstJoin = getFirstJoinTime(uuid);
        long now = System.currentTimeMillis();
        long thresholdMs = (long) timeThresholdHours * 3600L * 1000L;
        long remaining = thresholdMs - (now - firstJoin);
        return Math.max(0, remaining);
    }

    public String formatRemainingTime(long ms) {
        if (ms <= 0) return "已过期";
        long hours = ms / 3600000;
        long minutes = (ms % 3600000) / 60000;
        long seconds = (ms % 60000) / 1000;
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        if (minutes > 0) return minutes + "分钟" + seconds + "秒";
        return seconds + "秒";
    }

    public boolean isHostileMob(String entityTypeName) {
        return hostileMobNames.contains(entityTypeName.toUpperCase());
    }

    // ================================================================
    //  物品价值计算（与DeathDrop共用逻辑）
    // ================================================================

    public int getItemStackValue(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0;

        String materialName = item.getType().name();

        if (item.getType() == Material.ENCHANTED_BOOK) {
            return getMaterialValue("enchanted_book");
        }

        int baseValue = getMaterialValue(materialName);

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasEnchants()) {
                int totalLevel = 0;
                for (var entry : meta.getEnchants().entrySet()) {
                    totalLevel += entry.getValue();
                }
                int enchantBonus = baseValue * totalLevel / 10;
                return baseValue + enchantBonus;
            }
        }
        return baseValue;
    }

    public int getMaterialValue(String materialName) {
        var dd = Bukkit.getPluginManager().getPlugin("DeathDrop");
        if (dd != null && dd.isEnabled()) {
            try {
                var method = dd.getClass().getMethod("getItemValue", String.class);
                Object result = method.invoke(dd, materialName);
                if (result instanceof Integer) {
                    return (int) result;
                }
            } catch (Exception ignored) {
            }
        }

        String key = materialName.toLowerCase();
        if (builtinValues.containsKey(key)) return builtinValues.get(key);
        if (key.startsWith("minecraft:")) {
            key = key.substring(10);
            if (builtinValues.containsKey(key)) return builtinValues.get(key);
        }
        return 50;
    }

    public int getTotalValue(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            total += getItemStackValue(item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            total += getItemStackValue(item);
        }
        total += getItemStackValue(player.getInventory().getItemInOffHand());
        return total;
    }

    // ================================================================
    //  配置加载
    // ================================================================

    public void loadConfig() {
        reloadConfig();

        prefix = colorize(getConfig().getString("prefix", "&a&l[新手茧] &r"));

        var cond = getConfig().getConfigurationSection("conditions");
        if (cond != null) {
            valueThreshold = cond.getInt("value-threshold", 5000);
            timeThresholdHours = cond.getInt("time-threshold-hours", 48);
        }

        var eff = getConfig().getConfigurationSection("effects");
        if (eff != null) {
            pvpDamageMultiplier = eff.getDouble("pvp-damage-multiplier", 0.5);
            pvpMaxDamage = eff.getInt("pvp-max-damage", 10);
        }

        hostileMobNames.clear();
        List<String> mobList = getConfig().getStringList("hostile-mobs");
        for (String m : mobList) {
            hostileMobNames.add(m.toUpperCase());
        }

        var msg = getConfig().getConfigurationSection("messages");
        if (msg != null) {
            msgProtected = msg.getString("protected", "");
            msgProtectionExpired = msg.getString("protection-expired", "");
            msgStatusProtected = msg.getString("status-protected", "");
            msgStatusExpired = msg.getString("status-expired", "");
            msgStatusValue = msg.getString("status-value", "");
            msgStatusTime = msg.getString("status-time", "");
            msgValueInfo = msg.getString("value-info", "");
            msgAdminReloaded = msg.getString("admin-reloaded", "");
            msgAdminCheck = msg.getString("admin-check", "");
        }
    }

    // ================================================================
    //  Getters
    // ================================================================

    public String getPrefix() { return prefix; }
    public String getMsgProtected() { return msgProtected; }
    public String getMsgProtectionExpired() { return msgProtectionExpired; }
    public String getMsgStatusProtected() { return msgStatusProtected; }
    public String getMsgStatusExpired() { return msgStatusExpired; }
    public String getMsgStatusValue() { return msgStatusValue; }
    public String getMsgStatusTime() { return msgStatusTime; }
    public String getMsgValueInfo() { return msgValueInfo; }
    public String getMsgAdminReloaded() { return msgAdminReloaded; }
    public String getMsgAdminCheck() { return msgAdminCheck; }
    public int getValueThreshold() { return valueThreshold; }
    public int getTimeThresholdHours() { return timeThresholdHours; }
    public double getPvpDamageMultiplier() { return pvpDamageMultiplier; }
    public int getPvpMaxDamage() { return pvpMaxDamage; }

    public static String colorize(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // ================================================================
    //  内置物品价值表（DeathDrop fallback）
    // ================================================================

    private void initBuiltinValues() {
        // 剑
        builtinValues.put("wooden_sword", 150);
        builtinValues.put("stone_sword", 100);
        builtinValues.put("iron_sword", 600);
        builtinValues.put("golden_sword", 400);
        builtinValues.put("diamond_sword", 4000);
        builtinValues.put("netherite_sword", 12000);
        // 斧头
        builtinValues.put("wooden_axe", 250);
        builtinValues.put("stone_axe", 150);
        builtinValues.put("iron_axe", 900);
        builtinValues.put("golden_axe", 600);
        builtinValues.put("diamond_axe", 6000);
        builtinValues.put("netherite_axe", 18000);
        // 镐
        builtinValues.put("wooden_pickaxe", 250);
        builtinValues.put("stone_pickaxe", 150);
        builtinValues.put("iron_pickaxe", 900);
        builtinValues.put("golden_pickaxe", 600);
        builtinValues.put("diamond_pickaxe", 6000);
        builtinValues.put("netherite_pickaxe", 18000);
        // 铲
        builtinValues.put("wooden_shovel", 100);
        builtinValues.put("stone_shovel", 50);
        builtinValues.put("iron_shovel", 300);
        builtinValues.put("golden_shovel", 200);
        builtinValues.put("diamond_shovel", 2000);
        builtinValues.put("netherite_shovel", 6000);
        // 锄
        builtinValues.put("wooden_hoe", 150);
        builtinValues.put("stone_hoe", 100);
        builtinValues.put("iron_hoe", 600);
        builtinValues.put("golden_hoe", 400);
        builtinValues.put("diamond_hoe", 4000);
        builtinValues.put("netherite_hoe", 12000);
        // 头盔
        builtinValues.put("leather_helmet", 200);
        builtinValues.put("chainmail_helmet", 1200);
        builtinValues.put("iron_helmet", 1500);
        builtinValues.put("golden_helmet", 1000);
        builtinValues.put("diamond_helmet", 10000);
        builtinValues.put("netherite_helmet", 30000);
        builtinValues.put("turtle_helmet", 3000);
        // 胸甲
        builtinValues.put("leather_chestplate", 350);
        builtinValues.put("chainmail_chestplate", 2000);
        builtinValues.put("iron_chestplate", 2400);
        builtinValues.put("golden_chestplate", 1600);
        builtinValues.put("diamond_chestplate", 16000);
        builtinValues.put("netherite_chestplate", 48000);
        // 护腿
        builtinValues.put("leather_leggings", 300);
        builtinValues.put("chainmail_leggings", 1800);
        builtinValues.put("iron_leggings", 2100);
        builtinValues.put("golden_leggings", 1400);
        builtinValues.put("diamond_leggings", 14000);
        builtinValues.put("netherite_leggings", 42000);
        // 靴子
        builtinValues.put("leather_boots", 200);
        builtinValues.put("chainmail_boots", 1000);
        builtinValues.put("iron_boots", 1200);
        builtinValues.put("golden_boots", 800);
        builtinValues.put("diamond_boots", 8000);
        builtinValues.put("netherite_boots", 24000);
        // 特殊装备
        builtinValues.put("elytra", 15000);
        builtinValues.put("shield", 500);
        // 远程武器
        builtinValues.put("bow", 500);
        builtinValues.put("crossbow", 700);
        builtinValues.put("trident", 8000);
        builtinValues.put("mace", 8000);
        builtinValues.put("arrow", 25);
        // 工具
        builtinValues.put("shears", 500);
        builtinValues.put("flint_and_steel", 500);
        builtinValues.put("fishing_rod", 250);
        builtinValues.put("spyglass", 1000);
        // 贵重材料
        builtinValues.put("diamond", 2000);
        builtinValues.put("emerald", 1500);
        builtinValues.put("netherite_ingot", 6000);
        builtinValues.put("netherite_scrap", 2000);
        builtinValues.put("gold_ingot", 200);
        builtinValues.put("gold_nugget", 25);
        builtinValues.put("iron_ingot", 300);
        builtinValues.put("iron_nugget", 35);
        builtinValues.put("copper_ingot", 100);
        builtinValues.put("ancient_debris", 3000);
        builtinValues.put("ender_pearl", 500);
        builtinValues.put("eye_of_ender", 1000);
        builtinValues.put("blaze_rod", 500);
        builtinValues.put("blaze_powder", 200);
        builtinValues.put("nether_star", 25000);
        builtinValues.put("dragon_breath", 2500);
        // 附魔书
        builtinValues.put("enchanted_book", 5000);
        // 高价值物品
        builtinValues.put("totem_of_undying", 10000);
        builtinValues.put("golden_apple", 5000);
        builtinValues.put("enchanted_golden_apple", 25000);
        builtinValues.put("shulker_box", 2000);
        builtinValues.put("ender_chest", 1000);
        builtinValues.put("enchanting_table", 2500);
        builtinValues.put("anvil", 1500);
        // 矿石
        builtinValues.put("coal", 75);
        builtinValues.put("raw_iron", 300);
        builtinValues.put("raw_gold", 200);
        builtinValues.put("raw_copper", 100);
        builtinValues.put("raw_diamond", 2000);
        builtinValues.put("raw_netherite", 3000);
        // 方块
        builtinValues.put("obsidian", 2500);
        builtinValues.put("crying_obsidian", 1500);
        builtinValues.put("tnt", 750);
    }
}
