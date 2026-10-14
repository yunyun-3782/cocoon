/*
 * N00bCocoon - 新手茧
 * 低价值新人的保护壳，依赖DeathDrop价值体系
 * Copyright (c) 2025 Yunyun(云云)
 *
 * CaelLab BY-SA Code License, Version 3
 * https://www.caellab.com/license/cbs-v3
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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class N00bCocoonPlugin extends JavaPlugin {

    private static N00bCocoonPlugin instance;

    // --- 玩家数据 ---
    private File playerDataFile;
    private YamlConfiguration playerDataConfig;
    private final Map<UUID, Long> firstJoinTimes = new ConcurrentHashMap<>();

    // --- 撤销系统 ---
    private final Set<UUID> revokedPlayers = ConcurrentHashMap.newKeySet();

    // --- 状态提示去重：上次已向玩家提示过的状态 ---
    private final Map<UUID, String> notifiedStates = new ConcurrentHashMap<>();

    // --- 消退系统 ---
    // UUID -> 消退阶段总结束时间戳(ms)
    private final Map<UUID, Long> fadingEndTimes = new ConcurrentHashMap<>();
    // 消退阶段计时器任务ID
    private final Map<UUID, Integer> fadingTaskIds = new ConcurrentHashMap<>();

    // --- 配置项 ---
    private int valueThreshold = 5000;
    private int timeThresholdHours = 48;
    private double pvpDamageMultiplier = 0.5;
    private int pvpMaxDamage = 10;
    private String prefix = "&a&l[新手茧] &r";
    private final Set<String> hostileMobNames = new HashSet<>();

    // --- 消退配置 ---
    private int fadingCountdownSeconds = 120;
    private int fadingSuppressionMinutes = 30;
    private int fadingDecayMinutes = 60;

    // --- 消息 ---
    private String msgProtected;
    private String msgProtectionExpired;
    private String msgStatusProtected;
    private String msgStatusExpired;
    private String msgStatusValue;
    private String msgStatusTime;
    private String msgValueInfo;
    private String msgAdminReloaded;
    private String msgAdminCheck;

    // --- 内置物品价值表（DeathDrop不可用时的fallback） ---
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

        N00bCocoonListener listener = new N00bCocoonListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getCommand("cocoon").setExecutor(new CocoonCommand(this));

        // 启动消退检查定时器
        listener.startPeriodicFadingCheck();

        getLogger().info("N00bCocoon v" + getDescription().getVersion()
                + " | threshold=" + valueThreshold + "pts/" + timeThresholdHours + "h"
                + " | mobs=" + hostileMobNames.size()
                + " | fading=" + fadingCountdownSeconds + "s/"
                + fadingSuppressionMinutes + "min/"
                + fadingDecayMinutes + "min");
    }

    @Override
    public void onDisable() {
        for (Integer taskId : fadingTaskIds.values()) {
            getServer().getScheduler().cancelTask(taskId);
        }
        fadingTaskIds.clear();
        savePlayerData();
        getLogger().info("N00bCocoon disabled");
    }

    // ============================================================
    //  配置加载
    // ============================================================

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

        var fading = getConfig().getConfigurationSection("fading");
        if (fading != null) {
            fadingCountdownSeconds = fading.getInt("countdown-seconds", 120);
            fadingSuppressionMinutes = fading.getInt("suppression-minutes", 30);
            fadingDecayMinutes = fading.getInt("decay-minutes", 60);
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

    // ============================================================
    //  玩家数据
    // ============================================================

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
                    String ns = section.getString(uuidStr + ".notified-state", null);
                    if (ns != null) {
                        notifiedStates.put(uuid, ns);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        getLogger().info("已加载 " + firstJoinTimes.size() + " 个玩家数据");
    }

    public void savePlayerData() {
        if (playerDataConfig == null) return;
        var section = playerDataConfig.createSection("players");
        // 只遍历 firstJoinTimes 会漏掉「只被 ungive 过、却不在首次加入表里」的玩家
        Set<UUID> all = new HashSet<>(firstJoinTimes.keySet());
        all.addAll(revokedPlayers);
        all.addAll(notifiedStates.keySet());
        for (UUID uuid : all) {
            section.set(uuid.toString() + ".first-join", firstJoinTimes.getOrDefault(uuid, 0L));
            section.set(uuid.toString() + ".revoked", revokedPlayers.contains(uuid));
            String ns = notifiedStates.get(uuid);
            if (ns != null) {
                section.set(uuid.toString() + ".notified-state", ns);
            }
        }
        try {
            playerDataConfig.save(playerDataFile);
        } catch (IOException e) {
            getLogger().warning("保存playerdata.yml失败: " + e.getMessage());
        }
    }

    public long getFirstJoinTime(UUID uuid) {
        return firstJoinTimes.getOrDefault(uuid, 0L);
    }

    public void recordFirstJoin(UUID uuid) {
        if (!firstJoinTimes.containsKey(uuid)) {
            firstJoinTimes.put(uuid, System.currentTimeMillis());
            savePlayerData();
        }
    }

    // ============================================================
    //  撤销系统（give/ungive）
    // ============================================================

    public void revokePlayer(UUID uuid) {
        revokedPlayers.add(uuid);
        notifiedStates.put(uuid, "revoked");
        fadingEndTimes.remove(uuid);
        cancelFadingTask(uuid);
        savePlayerData();
    }

    public void unrevokePlayer(UUID uuid) {
        revokedPlayers.remove(uuid);
        savePlayerData();
    }

    public boolean isRevoked(UUID uuid) {
        return revokedPlayers.contains(uuid);
    }

    public String getNotifiedState(UUID uuid) {
        return notifiedStates.get(uuid);
    }

    public void setNotifiedState(UUID uuid, String state) {
        notifiedStates.put(uuid, state);
    }

    // ============================================================
    //  消退系统（Fading）
    // ============================================================

    /**
     * 启动保护消退流程（保护自然到期时调用）
     * 阶段1: 倒计时 fadingCountdownSeconds 秒，仍享受完整保护
     * 阶段2: fadingSuppressionMinutes 分钟 100% 抑制敌对生物生成
     * 阶段3: fadingDecayMinutes 分钟生成抑制线性衰减至0
     */
    public void startFading(Player player) {
        UUID uuid = player.getUniqueId();
        if (fadingEndTimes.containsKey(uuid)) return;

        long endTime = System.currentTimeMillis() + (long) fadingCountdownSeconds * 1000L;
        fadingEndTimes.put(uuid, endTime);

        player.sendMessage(colorize(prefix + "&e新手茧保护即将消退！还有 &f"
                + fadingCountdownSeconds + " &e秒，做好准备~"));

        // 阶段1结束 → 进入阶段2
        int taskId = getServer().getScheduler().runTaskLater(this, () -> {
            fadingEndTimes.remove(uuid);
            fadingTaskIds.remove(uuid);
            startSuppressionPhase(player);
        }, (long) fadingCountdownSeconds * 20L).getTaskId();

        fadingTaskIds.put(uuid, taskId);
    }

    private void startSuppressionPhase(Player player) {
        UUID uuid = player.getUniqueId();
        long suppressionMs = (long) fadingSuppressionMinutes * 60L * 1000L;
        long decayMs = (long) fadingDecayMinutes * 60L * 1000L;
        long totalPhaseEnd = System.currentTimeMillis() + suppressionMs + decayMs;

        fadingEndTimes.put(uuid, totalPhaseEnd);

        player.sendMessage(colorize(prefix + "&e新手茧保护已消退，敌对生物将在 &f"
                + (fadingSuppressionMinutes + fadingDecayMinutes)
                + " &e分钟内逐渐恢复生成"));

        // 阶段2结束 → 进入阶段3（衰减期）
        int taskId = getServer().getScheduler().runTaskLater(this, () -> {
            fadingEndTimes.put(uuid, totalPhaseEnd);
            player.sendMessage(colorize(prefix + "&e敌对生物开始逐渐恢复生成..."));
        }, (long) fadingSuppressionMinutes * 60L * 20L).getTaskId();

        fadingTaskIds.put(uuid, taskId);
    }

    public boolean isFading(UUID uuid) {
        Long end = fadingEndTimes.get(uuid);
        if (end == null) return false;
        if (System.currentTimeMillis() > end) {
            fadingEndTimes.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * 获取生成抑制比例（1.0=完全抑制，0.0=无抑制）
     */
    public double getSpawnSuppression(UUID uuid) {
        Long end = fadingEndTimes.get(uuid);
        if (end == null) return 0.0;

        long now = System.currentTimeMillis();
        long suppressionEnd = end - (long) fadingDecayMinutes * 60L * 1000L;

        // 阶段2: 100%抑制
        if (now < suppressionEnd) return 1.0;

        // 阶段3: 线性衰减
        long decayTotal = (long) fadingDecayMinutes * 60L * 1000L;
        long decayElapsed = now - suppressionEnd;
        double ratio = 1.0 - (double) decayElapsed / decayTotal;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    public boolean isFullSuppression(UUID uuid) {
        Long end = fadingEndTimes.get(uuid);
        if (end == null) return false;
        long now = System.currentTimeMillis();
        long suppressionEnd = end - (long) fadingDecayMinutes * 60L * 1000L;
        return now < suppressionEnd;
    }

    public void clearFading(UUID uuid) {
        fadingEndTimes.remove(uuid);
        cancelFadingTask(uuid);
    }

    public void cancelFadingTask(UUID uuid) {
        Integer taskId = fadingTaskIds.remove(uuid);
        if (taskId != null) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    // ============================================================
    //  物品价值计算
    // ============================================================

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
            } catch (Exception ignored) {}
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
        // getContents() 已经含盔甲和副手（41 格），再叠 armor/offhand 会把它们算两遍
        for (ItemStack item : player.getInventory().getStorageContents()) {
            total += getItemStackValue(item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            total += getItemStackValue(item);
        }
        total += getItemStackValue(player.getInventory().getItemInOffHand());
        return total;
    }

    // ============================================================
    //  保护判定
    // ============================================================

    public boolean isProtected(Player player) {
        if (player.hasPermission("n00bcocoon.bypass")) return true;
        UUID uuid = player.getUniqueId();
        if (isRevoked(uuid)) return false;
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

    // ============================================================
    //  Getters
    // ============================================================

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
    public int getFadingCountdownSeconds() { return fadingCountdownSeconds; }
    public int getFadingSuppressionMinutes() { return fadingSuppressionMinutes; }
    public int getFadingDecayMinutes() { return fadingDecayMinutes; }

    public static String colorize(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // ============================================================
    //  内置物品价值表
    // ============================================================

    private void initBuiltinValues() {
        builtinValues.put("wooden_sword", 150); builtinValues.put("stone_sword", 100);
        builtinValues.put("iron_sword", 600); builtinValues.put("golden_sword", 400);
        builtinValues.put("diamond_sword", 4000); builtinValues.put("netherite_sword", 12000);
        builtinValues.put("wooden_axe", 250); builtinValues.put("stone_axe", 150);
        builtinValues.put("iron_axe", 900); builtinValues.put("golden_axe", 600);
        builtinValues.put("diamond_axe", 6000); builtinValues.put("netherite_axe", 18000);
        builtinValues.put("wooden_pickaxe", 250); builtinValues.put("stone_pickaxe", 150);
        builtinValues.put("iron_pickaxe", 900); builtinValues.put("golden_pickaxe", 600);
        builtinValues.put("diamond_pickaxe", 6000); builtinValues.put("netherite_pickaxe", 18000);
        builtinValues.put("wooden_shovel", 100); builtinValues.put("stone_shovel", 50);
        builtinValues.put("iron_shovel", 300); builtinValues.put("golden_shovel", 200);
        builtinValues.put("diamond_shovel", 2000); builtinValues.put("netherite_shovel", 6000);
        builtinValues.put("wooden_hoe", 150); builtinValues.put("stone_hoe", 100);
        builtinValues.put("iron_hoe", 600); builtinValues.put("golden_hoe", 400);
        builtinValues.put("diamond_hoe", 4000); builtinValues.put("netherite_hoe", 12000);
        builtinValues.put("leather_helmet", 200); builtinValues.put("chainmail_helmet", 1200);
        builtinValues.put("iron_helmet", 1500); builtinValues.put("golden_helmet", 1000);
        builtinValues.put("diamond_helmet", 10000); builtinValues.put("netherite_helmet", 30000);
        builtinValues.put("turtle_helmet", 3000);
        builtinValues.put("leather_chestplate", 350); builtinValues.put("chainmail_chestplate", 2000);
        builtinValues.put("iron_chestplate", 2400); builtinValues.put("golden_chestplate", 1600);
        builtinValues.put("diamond_chestplate", 16000); builtinValues.put("netherite_chestplate", 48000);
        builtinValues.put("leather_leggings", 300); builtinValues.put("chainmail_leggings", 1800);
        builtinValues.put("iron_leggings", 2100); builtinValues.put("golden_leggings", 1400);
        builtinValues.put("diamond_leggings", 14000); builtinValues.put("netherite_leggings", 42000);
        builtinValues.put("leather_boots", 200); builtinValues.put("chainmail_boots", 1000);
        builtinValues.put("iron_boots", 1200); builtinValues.put("golden_boots", 800);
        builtinValues.put("diamond_boots", 8000); builtinValues.put("netherite_boots", 24000);
        builtinValues.put("elytra", 15000); builtinValues.put("shield", 500);
        builtinValues.put("bow", 500); builtinValues.put("crossbow", 700);
        builtinValues.put("trident", 8000); builtinValues.put("mace", 8000); builtinValues.put("arrow", 25);
        builtinValues.put("shears", 500); builtinValues.put("flint_and_steel", 500);
        builtinValues.put("fishing_rod", 250); builtinValues.put("spyglass", 1000);
        builtinValues.put("diamond", 2000); builtinValues.put("emerald", 1500);
        builtinValues.put("netherite_ingot", 6000); builtinValues.put("netherite_scrap", 2000);
        builtinValues.put("gold_ingot", 200); builtinValues.put("gold_nugget", 25);
        builtinValues.put("iron_ingot", 300); builtinValues.put("iron_nugget", 35);
        builtinValues.put("copper_ingot", 100); builtinValues.put("ancient_debris", 3000);
        builtinValues.put("ender_pearl", 500); builtinValues.put("eye_of_ender", 1000);
        builtinValues.put("blaze_rod", 500); builtinValues.put("blaze_powder", 200);
        builtinValues.put("nether_star", 25000); builtinValues.put("dragon_breath", 2500);
        builtinValues.put("enchanted_book", 5000);
        builtinValues.put("totem_of_undying", 10000); builtinValues.put("golden_apple", 5000);
        builtinValues.put("enchanted_golden_apple", 25000); builtinValues.put("shulker_box", 2000);
        builtinValues.put("ender_chest", 1000); builtinValues.put("enchanting_table", 2500);
        builtinValues.put("anvil", 1500);
        builtinValues.put("coal", 75); builtinValues.put("raw_iron", 300);
        builtinValues.put("raw_gold", 200); builtinValues.put("raw_copper", 100);
        builtinValues.put("raw_diamond", 2000); builtinValues.put("raw_netherite", 3000);
        builtinValues.put("obsidian", 2500); builtinValues.put("crying_obsidian", 1500);
        builtinValues.put("tnt", 750);
    }
}
