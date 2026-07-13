/*
 * N00bCocoon - 新手茧
 * 命令处理器：/cocoon status|reload|check|ungive|give
 * Copyright (c) 2025 Yunyun(云云)
 *
 * CaelLab BY-SA Code License
 * https://caellab.com/license/bysa-code
 */
package com.caellab.n00bcocoon;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("此命令只能由玩家执行");
                return true;
            }
            showStatus((Player) sender, sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("此命令只能由玩家执行");
                    return true;
                }
                showStatus((Player) sender, sender);
                return true;
            }

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
                    // 离线玩家也支持撤销
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                    if (offline.hasPlayedBefore()) {
                        plugin.revokePlayer(offline.getUniqueId());
                        sender.sendMessage(N00bCocoonPlugin.colorize(
                                plugin.getPrefix() + "&7已撤销 &e" + args[1] + " &7的新手茧保护（离线标记）"));
                    } else {
                        sender.sendMessage(N00bCocoonPlugin.colorize(
                                plugin.getPrefix() + "&c找不到玩家 " + args[1]));
                    }
                    return true;
                }
                plugin.revokePlayer(target.getUniqueId());
                target.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&c你的新手茧保护已被管理员撤销"));
                sender.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&7已撤销 &e" + target.getName() + " &7的新手茧保护"));
                return true;
            }

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
                    // 离线玩家也支持恢复
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                    if (offline.hasPlayedBefore()) {
                        plugin.unrevokePlayer(offline.getUniqueId());
                        sender.sendMessage(N00bCocoonPlugin.colorize(
                                plugin.getPrefix() + "&7已恢复 &e" + args[1] + " &7的新手茧保护"));
                    } else {
                        sender.sendMessage(N00bCocoonPlugin.colorize(
                                plugin.getPrefix() + "&c找不到玩家 " + args[1]));
                    }
                    return true;
                }
                plugin.unrevokePlayer(target.getUniqueId());
                target.sendMessage(N00bCocoonPlugin.colorize(
                        plugin.getPrefix() + "&a你的新手茧保护已被管理员恢复"));
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
}
