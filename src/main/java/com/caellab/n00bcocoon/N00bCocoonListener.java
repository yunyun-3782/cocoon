/*
 * N00bCocoon - 新手茧
 * 事件监听器：生物仇恨、PvP伤害、玩家加入/退出、消退系统、生物生成抑制
 */
package com.caellab.n00bcocoon;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

public class N00bCocoonListener implements Listener {

    private final N00bCocoonPlugin plugin;

    // 防刷屏通知：上次通知时间（5秒内不重复）
    private final java.util.Map<UUID, Long> lastNotifyTime = new java.util.HashMap<>();

    // 消退状态追踪：上次保护刚到期的玩家（用于触发startFading）
    private final java.util.Set<UUID> fadingTriggered = new java.util.HashSet<>();

    public N00bCocoonListener(N00bCocoonPlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  玩家加入：记录首次加入 + 通知保护状态
    // ============================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.recordFirstJoin(player.getUniqueId());

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            UUID uuid = player.getUniqueId();
            String state;
            if (plugin.isProtected(player)) {
                state = "protected";
            } else if (plugin.isRevoked(uuid)) {
                state = "revoked";
            } else {
                state = "expired";
            }
            // 只在状态变化时提示一次，避免每次登录重复刷屏
            if (state.equals(plugin.getNotifiedState(uuid))) return;
            plugin.setNotifiedState(uuid, state);

            if (state.equals("protected")) {
                long remaining = plugin.getProtectionRemainingMs(player);
                String timeStr = plugin.formatRemainingTime(remaining);
                player.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + plugin.getMsgProtected()));
                player.sendMessage(N00bCocoonPlugin.colorize(
                        "  &7保护剩余: &e" + timeStr + " &7| 价值上限: &e" + plugin.getValueThreshold() + " &7点"));
            } else if (state.equals("revoked")) {
                player.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&c你的新手茧保护已被管理员撤销"));
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // 下线立即清掉消退状态：只取消推进任务不删结束时间的话，重上线会卡在阶段1再也不推进
        plugin.clearFading(uuid);
        fadingTriggered.remove(uuid);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.savePlayerData();
        }, 1L);
    }

    // ============================================================
    //  敌对生物仇恨：取消对新手茧玩家的锁定
    // ============================================================

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

    // ============================================================
    //  伤害事件：生物伤害免疫 + PvP减伤
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();

        // 保护期间或消退阶段1（倒计时期间）都享受保护
        boolean protectedOrFading = plugin.isProtected(victim)
                || (plugin.isFading(victim.getUniqueId()) && !plugin.isFullSuppression(victim.getUniqueId()));
        // 如果是完全抑制阶段也享受PvP减伤（消退阶段2/3）
        if (!protectedOrFading && !plugin.isFading(victim.getUniqueId())) return;

        Player attacker = null;
        boolean hostileSource = false;

        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile) {
            // 弹射物得看 shooter 的类型：箭矢自身的类型是 ARROW，不能拿它判敌对生物
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                attacker = (Player) shooter;
            } else if (shooter instanceof LivingEntity) {
                hostileSource = plugin.isHostileMob(((LivingEntity) shooter).getType().name());
            }
        } else if (damager instanceof LivingEntity) {
            hostileSource = plugin.isHostileMob(damager.getType().name());
        }

        // 敌对生物伤害（近战或远程投射物）：完全免疫
        if (hostileSource) {
            event.setCancelled(true);
            notifyVictim(victim, "&7敌对生物的攻击被新手茧挡下了~");
            return;
        }

        // PvP伤害：按倍率减伤并封顶；改的是基础伤害，护甲在其后再算，所以最终值以 getFinalDamage 为准
        if (attacker != null && attacker != victim) {
            double reducedDamage = event.getDamage(DamageModifier.BASE) * plugin.getPvpDamageMultiplier();
            if (reducedDamage > plugin.getPvpMaxDamage()) {
                reducedDamage = plugin.getPvpMaxDamage();
            }
            event.setDamage(DamageModifier.BASE, reducedDamage);
            notifyVictim(victim, "&7你受到新手茧保护，PvP伤害已降低至 &e"
                    + String.format("%.1f", event.getFinalDamage()) + " &7点");
            attacker.sendMessage(N00bCocoonPlugin.colorize(
                    plugin.getPrefix() + "&7该玩家受新手茧保护，伤害已降低"));
        }
    }

    // ============================================================
    //  保护到期检测 + 触发消退系统
    //  在每个tick检查是否刚过期（通过定时任务驱动）
    // ============================================================

    /**
     * 由 Plugin 的 tick 或 PlayerMoveEvent 驱动的保护到期检查
     * 这里用 PlayerJoinEvent 的延迟任务 + 每60秒定时扫描在线玩家
     */
    public void startPeriodicFadingCheck() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                // 已被撤销的不处理
                if (plugin.isRevoked(uuid)) continue;
                // 已在消退中的不重复触发
                if (plugin.isFading(uuid)) continue;
                // 还在保护期的不处理
                if (plugin.isProtected(player)) {
                    fadingTriggered.remove(uuid);
                    continue;
                }
                // 刚过期（之前在保护期，现在不在了）→ 触发消退
                if (!fadingTriggered.contains(uuid)) {
                    fadingTriggered.add(uuid);
                    plugin.startFading(player);
                }
            }
        }, 20L * 60L, 20L * 60L); // 每60秒扫描一次
    }

    // ============================================================
    //  生物生成抑制：消退阶段抑制敌对生物生成
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // 只处理敌对生物
        if (!plugin.isHostileMob(event.getEntityType().name())) return;

        // 遍历在线玩家，检查是否有消退中的玩家
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!plugin.isFading(uuid)) continue;

            // 计算抑制比例
            double suppression = plugin.getSpawnSuppression(uuid);
            if (suppression <= 0.0) continue;

            // 检查距离：以玩家为中心，按抑制比例缩小范围
            // 100%抑制 = 128格范围，线性衰减
            // 跨世界不能比较距离，直接跳过该玩家
            Location spawnLoc = event.getLocation();
            if (spawnLoc.getWorld() != player.getWorld()) continue;
            double maxDistance = 128.0 * suppression;
            if (spawnLoc.distanceSquared(player.getLocation()) > maxDistance * maxDistance) continue;

            // 概率取消生成
            if (Math.random() < suppression) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ============================================================
    //  防刷屏通知
    // ============================================================

    private void notifyVictim(Player player, String msg) {
        long now = System.currentTimeMillis();
        Long last = lastNotifyTime.get(player.getUniqueId());
        if (last != null && (now - last) < 5000) return;
        lastNotifyTime.put(player.getUniqueId(), now);
        player.sendMessage(N00bCocoonPlugin.colorize(plugin.getPrefix() + msg));
    }
}
