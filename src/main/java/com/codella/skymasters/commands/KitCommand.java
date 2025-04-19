package com.codella.skymasters.commands;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Arena;
import com.codella.skymasters.game.GameState;
import com.codella.skymasters.game.Kit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.stream.Collectors;

public class KitCommand implements CommandExecutor, TabCompleter {

    private final SkyMasters plugin;

    public KitCommand(SkyMasters plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Default action: list available kits for the player
            handleList(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "list":
                handleList(sender);
                break;
            case "select":
                handleSelect(sender, args);
                break;
            case "create":
                handleCreate(sender, args);
                break;
            case "delete":
                handleDelete(sender, args);
                break;
            // case "gui": // Optional: Add GUI later
            // handleGui(sender);
            // break;
            default:
                 sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/kit <list|select|create|delete> [args]")));
                 break;
        }

        return true;
    }

    private void handleList(CommandSender sender) {
         if (!(sender instanceof Player)) {
            // List all kits for console or non-players? Or restrict? Restrict for now.
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
            return;
         }
         Player player = (Player) sender;
         List<Kit> availableKits = plugin.getKitManager().getAvailableKits(player);

         if (availableKits.isEmpty()) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-none-available"));
             return;
         }

         player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-list-header"));
         for (Kit kit : availableKits) {
              // Use specific message key for list item
             player.sendMessage(plugin.getConfigManager().getMessage("kit-list-item-available", Map.of("kit", kit.getName())));
         }
         // Optional: Add pagination if many kits exist
    }

    private void handleSelect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
            return;
        }
        Player player = (Player) sender;
        if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/kit select <kit_name>")));
            return;
        }

        Arena arena = plugin.getArenaManager().getPlayerArena(player);
        if (arena == null || (arena.getState() != GameState.WAITING && arena.getState() != GameState.STARTING)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-select-wrong-state"));
            return;
        }

        String kitName = args[1];
        Kit kit = plugin.getKitManager().getKit(kitName);

        if (kit == null) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-not-found", Map.of("kit", kitName)));
            return;
        }

         if (!plugin.getKitManager().canUseKit(player, kit)) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("no-kit-permission", Map.of("kit", kit.getName())));
            return;
         }

         // Apply the kit (store selection for later application or apply immediately if allowed)
         arena.setSelectedKit(player.getUniqueId(), kit);
         player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-selected", Map.of("kit", kit.getName())));

        // Optionally apply immediately if in lobby state AND allowed by config?
        // plugin.getKitManager().giveKit(player, kit); // Or do this when game starts
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
            return;
        }
        if (!sender.hasPermission("skymasters.admin")) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("no-permission"));
             return;
        }
        Player player = (Player) sender;
        if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/kit create <kit_name>")));
            return;
        }

        String kitName = args[1];
         // Basic sanitization? Avoid special chars maybe? For now, allow most names.
         // Let's prevent purely numeric names as they can be confusing.
        if (kitName.matches("\\d+")) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-name-numeric"));
             return;
        }


        if (plugin.getKitManager().kitExists(kitName)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-already-exists", Map.of("kit", kitName)));
            return;
        }

        PlayerInventory inv = player.getInventory();
        ItemStack[] mainContents = inv.getContents(); // Includes hotbar + main inventory
        ItemStack[] armorContents = inv.getArmorContents();

        boolean isEmpty = true;
         for (ItemStack item : mainContents) { if (item != null && item.getType() != Material.AIR) { isEmpty = false; break; } }
         for (ItemStack item : armorContents) { if (item != null && item.getType() != Material.AIR) { isEmpty = false; break; } }
         if (isEmpty) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-create-empty-inventory"));
            return;
         }


        // Extract only non-null/non-air items for saving
        List<ItemStack> itemsList = new ArrayList<>();
         for (ItemStack item : mainContents) {
             if (item != null && item.getType() != Material.AIR) {
                 itemsList.add(item.clone()); // Clone to avoid issues with player modifying inventory later
             }
         }
          // Armor cloning might not be strictly necessary if using the direct array, but cloning is safer.
         ItemStack[] clonedArmor = new ItemStack[armorContents.length];
         for (int i = 0; i < armorContents.length; i++) {
             if (armorContents[i] != null && armorContents[i].getType() != Material.AIR) {
                 clonedArmor[i] = armorContents[i].clone();
             }
         }


        String permission = "skymasters.kit." + kitName.toLowerCase();
        String description = "Kit created by " + player.getName(); // Default description

         if (plugin.getKitManager().createKit(kitName, description, permission, itemsList.toArray(new ItemStack[0]), clonedArmor)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-created", Map.of("kit", kitName)));
         } else {
             // Should not happen if exists check passed, but good practice
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-create-failed", Map.of("kit", kitName)));
         }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("skymasters.admin")) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("no-permission"));
             return;
        }
         if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/kit delete <kit_name>")));
            return;
        }

        String kitName = args[1];
        if (!plugin.getKitManager().kitExists(kitName)) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-not-found", Map.of("kit", kitName)));
            return;
        }

         if (plugin.getKitManager().deleteKit(kitName)) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-deleted", Map.of("kit", kitName)));
        } else {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-delete-failed", Map.of("kit", kitName)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        final List<String> completions = new ArrayList<>();
        final List<String> options = new ArrayList<>();

         if (args.length == 1) {
             options.add("list");
             options.add("select");
            if (sender.hasPermission("skymasters.admin")) {
                 options.add("create");
                 options.add("delete");
            }
            StringUtil.copyPartialMatches(args[0], options, completions);
         } else if (args.length == 2) {
             String subCommand = args[0].toLowerCase();
             switch (subCommand) {
                 case "select":
                     if (sender instanceof Player) {
                        plugin.getKitManager().getAvailableKits((Player) sender).stream()
                                .map(Kit::getName)
                                .forEach(options::add);
                     }
                     break;
                case "delete":
                    if (sender.hasPermission("skymasters.admin")) {
                        plugin.getKitManager().getAllKits().stream()
                                .map(Kit::getName)
                                .forEach(options::add);
                    }
                    break;
                case "create":
                    // No suggestions needed, player types a new name.
                    break;
             }
            StringUtil.copyPartialMatches(args[1], options, completions);
         }

         Collections.sort(completions);
        return completions;
    }
}