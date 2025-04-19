package com.codella.skymasters.commands;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Arena;
import com.codella.skymasters.game.GameState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.stream.Collectors;

public class SkywarsCommand implements CommandExecutor, TabCompleter {

    private final SkyMasters plugin;

    public SkywarsCommand(SkyMasters plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        // --- Player Commands ---
        if (subCommand.equals("join")) {
            handleJoin(sender, args);
            return true;
        }
        if (subCommand.equals("leave")) {
            handleLeave(sender);
            return true;
        }
        if (subCommand.equals("list")) {
            handleList(sender);
            return true;
        }

        // --- Admin Commands ---
        if (!sender.hasPermission("skymasters.admin")) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("no-permission"));
            return true;
        }

        switch (subCommand) {
            case "setup":
                handleSetup(sender, args);
                break;
            case "finishsetup":
                handleFinishSetup(sender);
                 break;
             case "cancelsetup":
                handleCancelSetup(sender);
                break;
            case "setlobby":
                handleSetLobby(sender);
                break;
            case "setspectatorspawn":
                 handleSetSpectatorSpawn(sender);
                 break;
             case "setcenter":
                handleSetCenter(sender);
                break;
            case "addspawn":
                handleAddSpawn(sender);
                break;
            case "addchest": // Implicitly handles remove via listener now
            case "removechest":
                 sender.sendMessage(plugin.getConfigManager().getMessage("prefix") + plugin.getConfigManager().getMessage("setup-use-wand-for-chest"));
                 break;
            case "enable":
                handleEnable(sender, args);
                break;
            case "disable":
                handleDisable(sender, args);
                break;
            case "create":
                 handleCreate(sender, args);
                 break;
             case "delete":
                 handleDelete(sender, args);
                 break;
             case "forcestart":
                handleForceStart(sender, args);
                break;
             case "forcestop":
                handleForceStop(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    // --- Player Command Handlers ---

    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
            return;
        }
        Player player = (Player) sender;

        if (plugin.getArenaManager().isPlayerInArena(player)) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("already-in-arena"));
            return;
        }

        Arena targetArena = null;
        if (args.length > 1) {
            targetArena = plugin.getArenaManager().getArena(args[1]);
            if (targetArena == null) {
                player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", args[1])));
                return;
            }
            if (!targetArena.isEnabled()){
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-ready", Map.of("arena", args[1])));
                 return;
            }
        } else {
            // Find the best available arena
            targetArena = plugin.getArenaManager().findAvailableArena();
            if (targetArena == null) {
                player.sendMessage(plugin.getConfigManager().getPrefixedMessage("no-available-arenas"));
                return;
            }
        }

        // Check state and capacity AFTER finding/specifying arena
        if (targetArena.getState() != GameState.WAITING && targetArena.getState() != GameState.STARTING) {
             if (targetArena.getState() == GameState.IN_GAME && plugin.getConfigManager().isSpectatorsAllowed()) {
                 // Join as spectator
                 if (targetArena.addSpectator(player)) {
                    // Message sent by addSpectator
                 } else {
                    player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-spectate", Map.of("arena", targetArena.getName())));
                 }
                 return; // Added as spectator or failed, stop here
             } else {
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-in-game", Map.of("arena", targetArena.getName())));
                 return;
             }
        }

        if (targetArena.getPlayers().size() >= plugin.getConfigManager().getMaxPlayersPerArena()) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-full", Map.of("arena", targetArena.getName())));
             return;
        }

        plugin.getArenaManager().addPlayerToArena(player, targetArena);
        // Join message is sent from Arena.addPlayer()
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
            return;
        }
        Player player = (Player) sender;
        if (!plugin.getArenaManager().isPlayerInArena(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("not-in-arena"));
            return;
        }
        plugin.getArenaManager().removePlayerFromArena(player);
        // Leave message is sent from Arena.removePlayer()
    }

    private void handleList(CommandSender sender) {
        sender.sendMessage(plugin.getConfigManager().getMessage("prefix") + plugin.getConfigManager().getMessage("arena-list-header"));
        boolean anyAvailable = false;
        for (Arena arena : plugin.getArenaManager().getAllArenas()) {
            if(arena.isEnabled()){
                String status;
                switch (arena.getState()) {
                    case WAITING: status = "&aWaiting"; break;
                    case STARTING: status = "&eStarting"; break;
                    case IN_GAME: status = "&cIn Game"; break;
                    case ENDING: status = "&6Ending"; break;
                    case REGENERATING: status = "&dRegenerating"; break;
                    default: status = "&7Offline"; break;
                }
                 // Using direct string formatting and translateAlternateColorCodes
                 sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', String.format("&e- %s %s &7(%d/%d)",
                         arena.getName(), status, arena.getPlayers().size(), plugin.getConfigManager().getMaxPlayersPerArena())));
                anyAvailable = true;
            }
        }
        if (!anyAvailable) {
            sender.sendMessage(plugin.getConfigManager().getMessage("prefix") + plugin.getConfigManager().getMessage("no-enabled-arenas"));
        }
    }


    // --- Admin Command Handlers ---

    private void handleSetup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
            return;
        }
        Player player = (Player) sender;
        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw setup <arena_name>")));
            return;
        }
        String arenaName = args[1];
        plugin.getSetupManager().startSetup(player, arenaName);
    }

     private void handleFinishSetup(CommandSender sender) {
         if (!(sender instanceof Player)) {
             sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
             return;
         }
         plugin.getSetupManager().finishSetup((Player) sender);
     }

     private void handleCancelSetup(CommandSender sender) {
         if (!(sender instanceof Player)) {
             sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
             return;
         }
         plugin.getSetupManager().endSetup((Player) sender);
     }


    private void handleSetLobby(CommandSender sender) {
         if (!(sender instanceof Player)) {
             sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
             return;
         }
         Player player = (Player) sender;
         if (!plugin.getSetupManager().isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         plugin.getSetupManager().setLobbySpawn(player);
     }

      private void handleSetSpectatorSpawn(CommandSender sender) {
         if (!(sender instanceof Player)) {
             sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
             return;
         }
         Player player = (Player) sender;
         if (!plugin.getSetupManager().isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         plugin.getSetupManager().setSpectatorSpawn(player);
     }

     private void handleSetCenter(CommandSender sender) {
          if (!(sender instanceof Player)) {
              sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
              return;
          }
          Player player = (Player) sender;
          if (!plugin.getSetupManager().isInSetupMode(player)) {
              player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
              return;
          }
          plugin.getSetupManager().setCenter(player);
     }

     private void handleAddSpawn(CommandSender sender) {
         if (!(sender instanceof Player)) {
             sender.sendMessage(plugin.getConfigManager().getMessage("player-only-command"));
             return;
         }
         Player player = (Player) sender;
          if (!plugin.getSetupManager().isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         plugin.getSetupManager().addSpawnPoint(player);
     }

     private void handleEnable(CommandSender sender, String[] args) {
         if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw enable <arena_name>")));
             return;
         }
         String arenaName = args[1];
         Arena arena = plugin.getArenaManager().getArena(arenaName);
         if (arena == null) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", arenaName)));
             return;
         }
         if (!arena.isFullySetup()) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-fully-setup", Map.of("arena", arenaName)));
             return;
         }
          if (arena.isEnabled()) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-already-enabled", Map.of("arena", arenaName)));
             return;
         }

         plugin.getArenaManager().enableArena(arenaName);
         sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-enabled", Map.of("arena", arenaName)));
     }

     private void handleDisable(CommandSender sender, String[] args) {
        if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw disable <arena_name>")));
             return;
         }
         String arenaName = args[1];
         Arena arena = plugin.getArenaManager().getArena(arenaName);
          if (arena == null) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", arenaName)));
             return;
         }
         if (!arena.isEnabled()) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-already-disabled", Map.of("arena", arenaName)));
             return;
         }

         plugin.getArenaManager().disableArena(arenaName);
         sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-disabled", Map.of("arena", arenaName)));
     }

    private void handleCreate(CommandSender sender, String[] args) {
         if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw create <arena_name>")));
             return;
         }
         String arenaName = args[1];
         if (plugin.getArenaManager().getArena(arenaName) != null) {
              sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-already-exists", Map.of("arena", arenaName)));
              return;
         }
         plugin.getArenaManager().createArena(arenaName);
         sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-created-for-setup", Map.of("arena", arenaName)));
    }

    private void handleDelete(CommandSender sender, String[] args) {
         if (args.length < 2) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw delete <arena_name>")));
             return;
         }
         String arenaName = args[1];
          Arena arena = plugin.getArenaManager().getArena(arenaName);
          if (arena == null) {
             sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", arenaName)));
             return;
         }
         // Check if the sender (if player) is currently setting up this arena
         if (sender instanceof Player) {
              Player player = (Player) sender;
              if (plugin.getSetupManager().getSession(player) != null && plugin.getSetupManager().getSession(player).getArenaName().equalsIgnoreCase(arenaName)) {
                   sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-delete-while-setup"));
                   return;
              }
         }


         plugin.getArenaManager().deleteArena(arenaName);
          sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-deleted", Map.of("arena", arenaName)));
    }


    private void handleForceStart(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw forcestart <arena_name>")));
            return;
        }
        String arenaName = args[1];
        Arena arena = plugin.getArenaManager().getArena(arenaName);
         if (arena == null) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", arenaName)));
            return;
        }
        if (arena.getState() != GameState.WAITING && arena.getState() != GameState.STARTING) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-forcestart-state", Map.of("arena", arenaName)));
            return;
        }
        if (arena.getPlayers().size() < 1) { // Need at least one player to even start
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-forcestart-empty", Map.of("arena", arenaName)));
            return;
        }

        sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("force-start", Map.of("arena", arenaName)));
        arena.startGame(true); // Pass true to indicate force start
    }

    private void handleForceStop(CommandSender sender, String[] args) {
          if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("invalid-arguments", Map.of("usage", "/sw forcestop <arena_name>")));
            return;
        }
        String arenaName = args[1];
        Arena arena = plugin.getArenaManager().getArena(arenaName);
          if (arena == null) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", arenaName)));
            return;
        }
         if (arena.getState() == GameState.WAITING || arena.getState() == GameState.DISABLED || arena.getState() == GameState.REGENERATING) {
            sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-forcestop-state", Map.of("arena", arenaName)));
            return;
        }

         sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("force-stop", Map.of("arena", arenaName)));
        arena.stopGame(true); // Force stop the game
    }

    private void handleReload(CommandSender sender) {
        sender.sendMessage(plugin.getConfigManager().getMessage("prefix") + plugin.getConfigManager().getMessage("reload-start"));
        plugin.getConfigManager().reloadConfigs(); // This handles reloading everything
        sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("reload-complete"));
    }


    private void sendHelp(CommandSender sender) {
        // Use messages from config file
        sender.sendMessage(plugin.getConfigManager().getPrefixedMessage("help-header"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-join"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-leave"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-list"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-kit-list"));
        sender.sendMessage(plugin.getConfigManager().getMessage("help-kit-select"));


        if (sender.hasPermission("skymasters.admin")) {
             sender.sendMessage(plugin.getConfigManager().getMessage("help-admin-header"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-create"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-delete"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-setup"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-addspawn"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-setlobby"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-setspectatorspawn"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-setcenter"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-finishsetup"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-cancelsetup"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-enable"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-disable"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-forcestart"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-forcestop"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-reload"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-kit-create"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-kit-delete"));
             sender.sendMessage(plugin.getConfigManager().getMessage("help-wand-chest-info")); // Added info about wand/chest
        }
    }

    // --- Tab Completer ---

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        final List<String> completions = new ArrayList<>();
        final List<String> options = new ArrayList<>();

        if (args.length == 1) {
            options.add("join");
            options.add("leave");
            options.add("list");
            if (sender.hasPermission("skymasters.admin")) {
                options.add("setup");
                options.add("finishsetup");
                options.add("cancelsetup");
                options.add("setlobby");
                options.add("setspectatorspawn");
                 options.add("setcenter");
                options.add("addspawn");
                // Removed "addchest"/"removechest" from subcommands, wand handles it
                options.add("enable");
                options.add("disable");
                options.add("create");
                options.add("delete");
                options.add("forcestart");
                options.add("forcestop");
                options.add("reload");
            }
            StringUtil.copyPartialMatches(args[0], options, completions);
        } else if (args.length == 2) {
             String subCommand = args[0].toLowerCase();
             List<String> arenaNames = plugin.getArenaManager().getAllArenas()
                                           .stream().map(Arena::getName).collect(Collectors.toList());

            switch (subCommand) {
                case "join":
                case "forcestart":
                case "forcestop":
                     List<String> joinableArenas = plugin.getArenaManager().getAllArenas().stream()
                         .filter(a -> a.isEnabled() && (a.getState() == GameState.WAITING || a.getState() == GameState.STARTING || (a.getState() == GameState.IN_GAME && plugin.getConfigManager().isSpectatorsAllowed())))
                         .map(Arena::getName)
                         .collect(Collectors.toList());
                     if (subCommand.equals("join")) {
                        options.addAll(joinableArenas);
                     } else {
                         // Suggest any arena for admin force commands
                         options.addAll(arenaNames);
                     }
                    break;
                case "setup":
                case "delete":
                case "create": // Suggest existing for setup/delete, but allow new for create
                     options.addAll(arenaNames);
                     // Allow typing a new name for 'create' - handled by partial match
                     break;
                case "enable":
                      List<String> disabledArenas = plugin.getArenaManager().getAllArenas().stream()
                         .filter(a -> !a.isEnabled() && a.isFullySetup()) // Only suggest fully setup but disabled
                         .map(Arena::getName)
                         .collect(Collectors.toList());
                      options.addAll(disabledArenas);
                    break;
                case "disable":
                     List<String> enabledArenas = plugin.getArenaManager().getAllArenas().stream()
                         .filter(Arena::isEnabled)
                         .map(Arena::getName)
                         .collect(Collectors.toList());
                     options.addAll(enabledArenas);
                    break;
                // No suggestions for setlobby, addspawn etc. as they don't take arena name arg
            }
             StringUtil.copyPartialMatches(args[1], options, completions);
        }

        Collections.sort(completions);
        return completions;
    }
}