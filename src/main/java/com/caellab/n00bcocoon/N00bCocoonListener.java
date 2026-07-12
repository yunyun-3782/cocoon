/*
 * N00bCocoon - 新手茧
 * 事件监听器：处理生物仇恨、PvP伤害、玩家加入
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

public class N00bCocoonListener implements Listener {

    private final N00bCocoonPlugin plugin;

    // 上次通知时间，防止刷屏（5秒内不重复通知）
    private final java.util.Map<java.util.UUID, Long> lastNotifyTime = new java.util.HashMap<>();

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

        // 延迟1秒通知（等玩家完全加载）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (plugin.isProtected(player)) {
                long remaining = plugin.getProtectionRemainingMs(player.getPlayer());
                String timeStr = plugin.formatRemainingTime(remaining);
                player.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + plugin.getMsgProtected()));
                player.sendMessage(N00bCocoonPlugin.colorize(
                        "  &7保护剩余: &e" + timeStr + " &7| 价值上限: &e" + plugin.getValueThreshold() + " &7点"));
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 退出时保存数据
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.savePlayerData();
        }, 1L);
    }

    // ============================================================
    //  敌对生物仇恨：取消对新手茧玩家的锁定
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        // 目标必须是玩家
        if (!(event.getTarget() instanceof Player)) return;

        Player target = (Player) event.getTarget();

        // 玩家没有保护就不处理
        if (!plugin.isProtected(target)) return;

        // 检查攻击者是否是敌对生物
        EntityType type = event.getEntity().getType();
        if (!plugin.isHostileMob(type.name())) return;

        // 取消仇恨锁定
        event.setCancelled(true);
        event.setTarget(null);
    }

    // ============================================================
    //  伤害事件：处理生物伤害免疫 + PvP减伤
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // 受害者必须是玩家
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();

        if (!plugin.isProtected(victim)) return;

        // === 判断攻击者来源 ===
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

        // === 敌对生物伤害：完全免疫 ===
        if (isHostileMob || (attacker == null && event.getDamager() instanceof org.bukkit.entity.LivingEntity)) {
            if (plugin.isHostileMob(event.getDamager().getType().name())) {
                event.setCancelled(true);
                notifyVictim(victim, "&7敌对生物的攻击被新手茧挡下了~");
                return;
            }
        }

        // === PvP伤害：50%减伤，最高10点 ===
        if (attacker != null && attacker != victim) {
            double originalDamage = event.getFinalDamage();
            double reducedDamage = originalDamage * plugin.getPvpDamageMultiplier();

            // 限制最高10点
            if (reducedDamage > plugin.getPvpMaxDamage()) {
                reducedDamage = plugin.getPvpMaxDamage();
            }

            event.setDamage(reducedDamage);

            // 通知双方
            notifyVictim(victim, "&7你受到新手茧保护，PvP伤害已降低至 &e" +
                    String.format("%.1f", reducedDamage) + " &7点");
            attacker.sendMessage(N00bCocoonPlugin.colorize(
                    plugin.getPrefix() + "&7该玩家受新手茧保护，伤害已降低"));
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
