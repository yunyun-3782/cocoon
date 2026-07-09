/*
 * N00bCocoon - 新手茧
 * 命令处理器：/cocoon status|reload|check|ungive|give
 * Copyright (c) 2025 Yunyun(云云)
 * Source: https://github.com/yunyun-3782/cocoon
 *
 * CaelLab BY-SA Code License
 * https://caellab.com/license/bysa-code
 */
package com.caellab.n00bcocoon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class CocoonCommand implements CommandExecutor, TabCompleter {

    private final N00bCocoonPlugin plugin;

    public CocoonCommand(N00bCocoonPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // === /cocoon (无参数) ===
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("此命令只能由玩家执行");
                return true;
            }
            showStatus((Player) sender, sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            // === /cocoon status ===
            case "status": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("此命令只能由玩家执行");
                    return true;
                }
                showStatus((Player) sender, sender);
                return true;
            }

            // === /cocoon reload ===
            case "reload": {
                if (!sender.hasPermission("n00bcocoon.admin")) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c你没有权限执行此命令"));
                    return true;
                }
                plugin.loadConfig();
                sender.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + plugin.getMsgAdminReloaded()));
                return true;
            }

            // === /cocoon check <player> ===
            case "check": {
                if (!sender.hasPermission("n00bcocoon.admin")) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c你没有权限执行此命令"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&7用法: /cocoon check <玩家名>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c玩家 " + args[1] + " 不在线"));
                    return true;
                }
                showStatus(target, sender);
                return true;
            }

            // === /cocoon ungive <player>（手动结束保护） ===
            case "ungive": {
                if (!sender.hasPermission("n00bcocoon.admin")) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c你没有权限执行此命令"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&7用法: /cocoon ungive <玩家名>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c玩家 " + args[1] + " 不在线，已标记撤销，上线后弹出"));
                    // 不在线也可以撤销，等上线再弹Title
                    // 先查UUID（通过离线玩家方式）
                    @SuppressWarnings("deprecation")
                    OfflinePlayerFetcher offlineTarget = new OfflinePlayerFetcher(args[1]);
                    if (offlineTarget.getUniqueId() != null) {
                        plugin.revokePlayer(offlineTarget.getUniqueId());
                    } else {
                        sender.sendMessage(N00bCocoonPlugin.colorize(
                                plugin.getPrefix() + "&c找不到玩家 " + args[1]));
                    }
                    return true;
                }

                // 在线 → 直接撤销，弹Title
                plugin.revokePlayer(target.getUniqueId());
                sender.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&7已撤销 &e" + target.getName() + " &7的新手茧保护"));
                return true;
            }

            // === /cocoon give <player>（手动恢复保护） ===
            case "give": {
                if (!sender.hasPermission("n00bcocoon.admin")) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c你没有权限执行此命令"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&7用法: /cocoon give <玩家名>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(N00bCocoonPlugin.colorize(
                            plugin.getPrefix() + "&c玩家 " + args[1] + " 不在线"));
                    return true;
                }

                plugin.unrevokePlayer(target.getUniqueId());
                sender.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&7已恢复 &e" + target.getName() + " &7的新手茧保护"));
                return true;
            }

            default: {
                sender.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&7用法: /cocoon [status|reload|check|ungive|give]"));
                return true;
            }
        }
    }

    // ================================================================
    //  显示保护状态
    // ================================================================

    private void showStatus(Player target, CommandSender viewer) {
        boolean protected_ = plugin.isProtected(target);
        int value = plugin.getTotalValue(target);
        long remaining = plugin.getProtectionRemainingMs(target);
        String timeStr = plugin.formatRemainingTime(remaining);

        viewer.sendMessage(N00bCocoonPlugin.colorize("&6&m--- &e新手茧 &6&m---"));

        if (viewer != target) {
            viewer.sendMessage(N00bCocoonPlugin.colorize(
                    "&7玩家: &f" + target.getName()));
        }

        if (protected_) {
            viewer.sendMessage(N00bCocoonPlugin.colorize(plugin.getMsgStatusProtected()));
        } else {
            viewer.sendMessage(N00bCocoonPlugin.colorize(plugin.getMsgStatusExpired()));
        }

        String valueMsg = plugin.getMsgStatusValue().replace("%value%", String.valueOf(value));
        viewer.sendMessage(N00bCocoonPlugin.colorize(valueMsg));
        viewer.sendMessage(N00bCocoonPlugin.colorize(
                "&7价值阈值: &e" + plugin.getValueThreshold() + " &7点"));

        String timeMsg = plugin.getMsgStatusTime().replace("%time%", timeStr);
        viewer.sendMessage(N00bCocoonPlugin.colorize(timeMsg));
        viewer.sendMessage(N00bCocoonPlugin.colorize(
                "&7时间阈值: &e" + plugin.getTimeThresholdHours() + " &7小时"));

        if (plugin.isRevoked(target.getUniqueId())) {
            viewer.sendMessage(N00bCocoonPlugin.colorize("&7撤销状态: &c已被管理员撤销"));
        }

        viewer.sendMessage(N00bCocoonPlugin.colorize("&6&m----------------------"));
    }

    // ================================================================
    //  Tab补全
    // ================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            addIfPrefix(completions, "status", partial);
            addIfPrefix(completions, "reload", partial);
            addIfPrefix(completions, "check", partial);
            addIfPrefix(completions, "ungive", partial);
            addIfPrefix(completions, "give", partial);
            // 如果没有admin权限，过滤admin子命令
            if (!sender.hasPermission("n00bcocoon.admin")) {
                completions.removeIf(c -> !c.equals("status"));
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("check") || sub.equals("ungive") || sub.equals("give")) {
                if (sender.hasPermission("n00bcocoon.admin") || sub.equals("check")) {
                    String partial = args[1].toLowerCase();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(partial)) {
                            completions.add(p.getName());
                        }
                    }
                }
            }
        }

        return completions;
    }

    private void addIfPrefix(List<String> list, String option, String prefix) {
        if (option.startsWith(prefix)) {
            list.add(option);
        }
    }

    // ================================================================
    //  离线玩家UUID查找（通过playerdata或离线查询）
    // ================================================================

    /**
     * 简易离线玩家查找，优先从playerdata里查，其次用Bukkit.getOfflinePlayer
     */
    private static class OfflinePlayerFetcher {
        private final java.util.UUID uniqueId;

        OfflinePlayerFetcher(String name) {
            // 尝试从playerdata.yml查找（遍历比较麻烦，直接用Bukkit的deprecated方法）
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline != null && offline.hasPlayedBefore()) {
                this.uniqueId = offline.getUniqueId();
            } else {
                this.uniqueId = null;
            }
        }

        java.util.UUID getUniqueId() {
            return uniqueId;
        }
    }
}
