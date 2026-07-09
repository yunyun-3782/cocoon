/*
 * N00bCocoon - 新手茧
 * 事件监听器：生物仇恨、PvP伤害、认证后通知
 * Copyright (c) 2025 Yunyun(云云)
 * Source: https://github.com/yunyun-3782/cocoon
 *
 * CaelLab BY-SA Code License
 * https://caellab.com/license/bysa-code
 */
package com.caellab.n00bcocoon;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class N00bCocoonListener implements Listener {

    private final N00bCocoonPlugin plugin;

    // 防刷屏：每个玩家5秒内只收到一次生物挡伤提示
    private final Map<UUID, Long> lastMobBlockTime = new HashMap<>();
    // 防刷屏：PvP减伤提示
    private final Map<UUID, Long> lastPvpNotifyTime = new HashMap<>();

    public N00bCocoonListener(N00bCocoonPlugin plugin) {
        this.plugin = plugin;
    }

    // ================================================================
    //  玩家加入：等认证后才发通知
    // ================================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 记录首次加入时间
        plugin.recordFirstJoin(uuid);

        // 启动认证检测器，等认证通过后再发通知
        plugin.startAuthChecker(player, () -> {
            // 认证通过，发通知
            plugin.sendPostAuthNotifications(player);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // 取消该玩家的认证检测器
        plugin.cancelAuthChecker(uuid);

        // 延迟保存数据（防止并发问题）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.savePlayerData();
        }, 1L);
    }

    // ================================================================
    //  敌对生物仇恨：取消对新手茧玩家的锁定
    // ================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player)) return;

        Player target = (Player) event.getTarget();
        if (!plugin.isProtected(target)) return;

        EntityType type = event.getEntity().getType();
        if (!plugin.isHostileMob(type.name())) return;

        event.setCancelled(true);
        event.setTarget(null);
    }

    // ================================================================
    //  伤害事件：生物伤害免疫 + PvP减伤
    // ================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        if (!plugin.isProtected(victim)) return;

        // 判断攻击者
        Player attacker = null;
        boolean isHostileMob = false;

        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) event.getDamager();
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Player) {
                attacker = (Player) shooter;
            } else if (event.getDamager() instanceof org.bukkit.entity.LivingEntity) {
                isHostileMob = plugin.isHostileMob(event.getDamager().getType().name());
            }
        } else if (event.getDamager() instanceof org.bukkit.entity.LivingEntity) {
            isHostileMob = plugin.isHostileMob(event.getDamager().getType().name());
        }

        // === 敌对生物伤害 → 完全免疫 ===
        if (isHostileMob || (attacker == null && event.getDamager() instanceof org.bukkit.entity.LivingEntity)) {
            if (plugin.isHostileMob(event.getDamager().getType().name())) {
                event.setCancelled(true);
                notifyThrottled(victim, lastMobBlockTime,
                        "&7敌对生物的攻击被新手茧挡下了~", 5000);
                return;
            }
        }

        // === PvP伤害 → 50%减伤，上限10 ===
        if (attacker != null && attacker != victim) {
            double originalDamage = event.getFinalDamage();
            double reducedDamage = originalDamage * plugin.getPvpDamageMultiplier();

            if (reducedDamage > plugin.getPvpMaxDamage()) {
                reducedDamage = plugin.getPvpMaxDamage();
            }

            event.setDamage(reducedDamage);

            notifyThrottled(victim, lastPvpNotifyTime,
                    plugin.getPrefix() + "&7你受到新手茧保护，PvP伤害已降低至 &e" +
                            String.format("%.1f", reducedDamage) + " &7点", 5000);

            attacker.sendMessage(N00bCocoonPlugin.colorize(
                    plugin.getPrefix() + "&7该玩家受新手茧保护，伤害已降低"));
        }
    }

    // ================================================================
    //  防刷屏工具
    // ================================================================

    private void notifyThrottled(Player player, Map<UUID, Long> timeMap, String msg, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = timeMap.get(player.getUniqueId());
        if (last != null && (now - last) < cooldownMs) return;
        timeMap.put(player.getUniqueId(), now);
        player.sendMessage(N00bCocoonPlugin.colorize(msg));
    }
}
