package com.codella.skymasters.game;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.listeners.PlayerListener; // For PlayerJoinArenaEvent
import com.codella.skymasters.utils.LocationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Arena {

    private final SkyMasters plugin;
    private final String name;
    private boolean enabled; // Whether the arena can be joined/used

    // --- Core State & Players ---
    private GameState state = GameState.DISABLED; // Start disabled until enabled/loaded
    private final List<UUID> players = new CopyOnWriteArrayList<>(); // Players currently playing/waiting
    private final List<UUID> spectators = new CopyOnWriteArrayList<>(); // Players spectating
    private final Map<UUID, Kit> selectedKits = new ConcurrentHashMap<>(); // Player UUID -> Selected Kit
    private final Map<UUID, Long> invincibilityTimers = new ConcurrentHashMap<>(); // Player UUID -> Time invincibility ends

    // --- Configuration ---
    private Location lobbySpawn;
    private Location spectatorSpawn;
    private List<Location> playerSpawns;
    private List<Location> chestLocations;
    private Location corner1; // Arena bounds
    private Location corner2; // Arena bounds
    private Location center; // Arena center (optional feature use)
    private World world; // Cached world for performance

     // --- Regeneration Data ---
    private Map<Location, BlockData> originalBlockData; // For FULL regeneration
    private final Set<Location> playerPlacedBlocks = ConcurrentHashMap.newKeySet(); // For PARTIAL regeneration

    // --- Game Task Scheduling ---
    private BukkitTask countdownTask = null;
    private BukkitTask gameTimerTask = null;
    private BukkitTask invincibilityTask = null;
    private BukkitTask regenerationTask = null; // Task handling the regeneration process
    private int countdownSeconds;
    private int gameTimeRemaining;


    public Arena(SkyMasters plugin, String name, boolean enabled, Location lobbySpawn, Location spectatorSpawn,
                 List<Location> playerSpawns, List<Location> chestLocations,
                 Location corner1, Location corner2, Location center) {
        this.plugin = plugin;
        this.name = name;
        this.enabled = enabled;
        this.lobbySpawn = lobbySpawn;
        this.spectatorSpawn = spectatorSpawn;
        this.playerSpawns = playerSpawns != null ? new ArrayList<>(playerSpawns) : new ArrayList<>();
        this.chestLocations = chestLocations != null ? new ArrayList<>(chestLocations) : new ArrayList<>();
        this.corner1 = corner1;
        this.corner2 = corner2;
        this.center = center;

        // Initial state based on enabled status and config presence
         if (enabled && isFullySetup()) {
             this.state = GameState.WAITING;
             // Attempt to load/verify block data if using FULL regen mode
             if("FULL".equals(plugin.getConfigManager().getRegenerationMode())){
                 // Try loading pre-saved block data from config? Done in ArenaManager load.
                 // Or, maybe save on enable? ArenaManager handles initial save on enable.
             }
         } else {
             this.state = GameState.DISABLED; // Stay disabled if not enabled or not fully setup
         }


         if (lobbySpawn != null) {
             this.world = lobbySpawn.getWorld();
         } else if (corner1 != null) {
             this.world = corner1.getWorld(); // Fallback to bounds world
         }
         // If world is still null, there's a config issue. Handled in manager load.

    }

    // --- Player Management ---

    public boolean addPlayer(Player player) {
         if (!enabled || state == GameState.DISABLED || state == GameState.REGENERATING) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-ready", Map.of("arena", name)));
             return false;
         }
        if (state != GameState.WAITING && state != GameState.STARTING) {
            // Allow joining as spectator if game is in progress
             if (state == GameState.IN_GAME && plugin.getConfigManager().isSpectatorsAllowed()) {
                return addSpectator(player);
             }
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-in-game", Map.of("arena", name)));
            return false;
        }
        if (players.size() >= plugin.getConfigManager().getMaxPlayersPerArena()) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-full", Map.of("arena", name)));
            return false;
        }

        players.add(player.getUniqueId());
        player.teleport(lobbySpawn);
        resetPlayerState(player, GameMode.ADVENTURE); // Use Adventure in lobby

         // Broadcast join message
        String current = String.valueOf(players.size());
        String max = String.valueOf(plugin.getConfigManager().getMaxPlayersPerArena());
        broadcastMessage("join-arena-broadcast", Map.of("player", player.getName(), "current", current, "max", max));
        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("join-arena", Map.of("arena", name, "current", current, "max", max)));


        // Call custom event (if implemented)
         Bukkit.getPluginManager().callEvent(new PlayerListener.PlayerJoinArenaEvent(player, this));

        // Check if game can start
        checkStartCountdown();
        return true;
    }

    public void removePlayer(Player player) {
         boolean wasPlayer = players.remove(player.getUniqueId());
         boolean wasSpectator = spectators.remove(player.getUniqueId());
         invincibilityTimers.remove(player.getUniqueId()); // Remove invincibility timer if they leave
         Kit selected = selectedKits.remove(player.getUniqueId()); // Remove kit selection


         // Only reset state and teleport if they were actually removed (not double-called)
         if (wasPlayer || wasSpectator) {
              resetPlayerState(player, Bukkit.getDefaultGameMode()); // Reset to server default
             // Teleport player out - needs a global lobby location or previous location management
              // Simple approach: Teleport to world spawn? Needs config. For now, just reset state.
              // player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); // Example: teleport to main world spawn

             // Send leave message only if they were an active player during game/lobby
             if (wasPlayer && (state == GameState.WAITING || state == GameState.STARTING || state == GameState.IN_GAME)) {
                 String current = String.valueOf(players.size());
                 String max = String.valueOf(plugin.getConfigManager().getMaxPlayersPerArena());
                 broadcastMessage("leave-arena-broadcast", Map.of("player", player.getName(), "current", current, "max", max));
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("leave-arena", Map.of("arena", name))); // Notify the player themselves
             } else if (wasSpectator) {
                  player.sendMessage(plugin.getConfigManager().getPrefixedMessage("stop-spectating", Map.of("arena", name)));
             }
         }


         // If a player leaves during countdown/game, check conditions
         if (state == GameState.STARTING) {
            if (players.size() < plugin.getConfigManager().getMinPlayersToStart()) {
                cancelCountdown("countdown-cancelled");
            }
         } else if (state == GameState.IN_GAME) {
            checkWinCondition(); // Check if their departure results in a win
         }
    }

    public boolean addSpectator(Player player) {
        if (!plugin.getConfigManager().isSpectatorsAllowed() || state == GameState.DISABLED || state == GameState.REGENERATING) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-spectate-disabled", Map.of("arena", name)));
            return false;
        }
         if (isPlayer(player)) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-spectate-playing"));
            return false;
         }

         spectators.add(player.getUniqueId());
         resetPlayerState(player, GameMode.SPECTATOR);
         player.teleport(spectatorSpawn != null ? spectatorSpawn : (lobbySpawn != null ? lobbySpawn : player.getWorld().getSpawnLocation())); // Best available spawn

        // Apply spectator settings
        float specSpeed = (float) plugin.getConfigManager().getSpectatorSpeed();
        player.setFlySpeed(specSpeed); // Spectators fly
        player.setAllowFlight(true);
        player.setFlying(true);

        if(plugin.getConfigManager().getSpectatorNightVision()){
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
        }

        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("now-spectating"));
        // Hide spectator from living players
        hidePlayerFromOthers(player);
        return true;
    }

    public void removeSpectator(Player player, boolean force) {
         boolean removed = spectators.remove(player.getUniqueId());
         if(removed || force) { // Force removal even if not in list (e.g., on disable)
            resetPlayerState(player, Bukkit.getDefaultGameMode());
            // player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); // Teleport out
            // Show player again if they were hidden
            showPlayerToOthers(player);
             if (player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                 player.removePotionEffect(PotionEffectType.NIGHT_VISION);
             }
              // Only send message if they were actually removed (not just forced)
             if (removed) {
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("stop-spectating", Map.of("arena", name)));
             }
         }
    }

     private void hidePlayerFromOthers(Player spectator) {
        getOnlinePlayers().forEach(other -> {
            if (!other.equals(spectator)) {
                 other.hidePlayer(plugin, spectator);
            }
        });
        getOnlineSpectators().forEach(otherSpec -> {
            if (!otherSpec.equals(spectator)) {
                // Allow spectators to see each other? Or hide them too? Let them see each other for now.
                // spectator.hidePlayer(plugin, otherSpec);
                // otherSpec.hidePlayer(plugin, spectator);
            }
        });
    }

    private void showPlayerToOthers(Player spectator) {
         plugin.getServer().getOnlinePlayers().forEach(onlinePlayer -> {
              onlinePlayer.showPlayer(plugin, spectator);
              // Don't force show spectator to other players? Let them see normally.
              // spectator.showPlayer(plugin, onlinePlayer);
         });
    }


    // --- Game Logic ---

    private void checkStartCountdown() {
        if (state == GameState.WAITING && players.size() >= plugin.getConfigManager().getMinPlayersToStart()) {
            startCountdown();
        }
    }

    private void startCountdown() {
        if (state != GameState.WAITING) return; // Only start from waiting
        state = GameState.STARTING;
        countdownSeconds = plugin.getConfigManager().getLobbyCountdownSeconds();
        broadcastMessage("countdown-starting");

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.STARTING) { // Check if state changed (e.g. cancelled)
                    cancel();
                    return;
                }

                if (countdownSeconds <= 0) {
                    cancel();
                    startGame(false); // Start game normally
                    return;
                }

                 // Send title/action bar countdown messages
                 String timeStr = String.valueOf(countdownSeconds);
                 // Use action bar message key
                 updateActionBars(); // Update action bars, which will show the countdown message

                 if (plugin.getConfigManager().showCountdownTitle() && (countdownSeconds <= 5 || countdownSeconds % 5 == 0)) { // Show title at 5, 4, 3, 2, 1 and every 5 secs before
                      Title title = Title.title(
                             Component.text(countdownSeconds, NamedTextColor.AQUA), // Title text
                             Component.empty(), // Subtitle text (empty)
                             Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500)) // Fade in, stay, fade out
                      );
                      sendTitleToAll(title);
                 }

                // Play sound effect (optional)
                 if (countdownSeconds <= 3) {
                     playSoundToAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f + (0.1f * (3 - countdownSeconds))); // Rising pitch
                 } else if (countdownSeconds <= 5 || countdownSeconds % 5 == 0) {
                     playSoundToAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                 }


                countdownSeconds--;
            }
        }.runTaskTimer(plugin, 0L, 20L); // Run immediately, then every second (20 ticks)
    }

    private void cancelCountdown(String reasonMessageKey) {
        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
        }
        countdownTask = null;
        state = GameState.WAITING;
        broadcastMessage(reasonMessageKey);
         // Clear titles if countdown cancelled abruptly
         sendTitleToAll(Title.title(Component.empty(), Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(100), Duration.ZERO)));
         updateActionBars(); // Update action bars to show waiting message
    }

    public void startGame(boolean forced) {
        if (!forced && state != GameState.STARTING) return; // Normal start must come from STARTING
         if(countdownTask != null) countdownTask.cancel(); // Ensure countdown stops if forced
         countdownTask = null;

        // Double check player count if not forced (could dip below min during final tick)
        if (!forced && players.size() < plugin.getConfigManager().getMinPlayersToStart()) {
            cancelCountdown("countdown-cancelled"); // Not enough players suddenly
            return;
        }
        if (players.size() < 1) {
            plugin.getLogger().warning("Attempted to start arena '" + name + "' with 0 players. Aborting.");
            resetArena(false); // Reset to waiting state without regen
            return;
        }


        state = GameState.IN_GAME;
        broadcastMessage("game-starting");
         if (plugin.getConfigManager().showStartTitle()) {
             Title startTitle = Title.title(
                     Component.text("GO!", NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.BOLD),
                     Component.text("Fight!", NamedTextColor.YELLOW),
                      Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
             );
             sendTitleToAll(startTitle);
         }
         playSoundToAll(Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);


        // --- Teleport players to spawns and prepare them ---
        List<Location> availableSpawns = new ArrayList<>(playerSpawns);
        Collections.shuffle(availableSpawns); // Randomize spawn order
        int spawnIndex = 0;

        Iterator<UUID> playerIterator = players.iterator(); // Use iterator for safe access on concurrent list
         while (playerIterator.hasNext()) {
            UUID uuid = playerIterator.next();
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                // Player logged off just before start, remove them silently
                 players.remove(uuid);
                 selectedKits.remove(uuid); // Also remove kit selection if they logged off
                continue; // Skip to next player
            }

             if (spawnIndex >= availableSpawns.size()) {
                 plugin.getLogger().warning("Not enough spawn points in arena '" + name + "' for " + players.size() + " players! Using lobby spawn as fallback.");
                 p.teleport(lobbySpawn); // Fallback, though ideally this shouldn't happen
             } else {
                 p.teleport(availableSpawns.get(spawnIndex));
                 spawnIndex++;
             }

             // Reset state, apply kit, start invincibility
             resetPlayerState(p, GameMode.SURVIVAL); // Set to survival for game
             p.setHealth(p.getMaxHealth());
             p.setFoodLevel(20);
             p.setExp(0);
             p.setLevel(0);

            // Apply selected kit
            Kit kit = selectedKits.getOrDefault(uuid, null);
            if (kit != null) {
                plugin.getKitManager().giveKit(p, kit);
                 // Send message here or rely on selection message? Maybe confirm kit received?
                 // p.sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-received", Map.of("kit", kit.getName())));
            } else {
                 // Give default kit if no selection and auto-equip is off but maybe wanted as fallback?
                 // Or just leave them kitless. Current logic: leave kitless if none selected.
            }

             // Apply Invincibility
             startInvincibility(p);
        }

         // Refill chests initially? Assume chests are pre-filled by map makers or setup process. Refill now just in case.
         refillChests();
         // Start game timer and chest refill timers
         startGameTimers();
         // Clear placed block tracking for partial regen at start
         playerPlacedBlocks.clear();
    }

    private void startInvincibility(Player player) {
        int durationSeconds = plugin.getConfigManager().getStartInvincibilitySeconds();
        if (durationSeconds <= 0) return;

        long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        invincibilityTimers.put(player.getUniqueId(), endTime);
        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("invincibility-start", Map.of("time", String.valueOf(durationSeconds))));

        // Schedule a task to remove invincibility later
        if (invincibilityTask == null || invincibilityTask.isCancelled()) { // Only start one task
             invincibilityTask = new BukkitRunnable() {
                 @Override
                 public void run() {
                      if(state != GameState.IN_GAME && state != GameState.ENDING) { cancel(); invincibilityTask = null; return;} // Stop if game ended/ending

                      long now = System.currentTimeMillis();
                     boolean anyLeft = false;
                     // Use iterator for safe removal
                     Iterator<Map.Entry<UUID, Long>> iterator = invincibilityTimers.entrySet().iterator();
                     while(iterator.hasNext()){
                         Map.Entry<UUID, Long> entry = iterator.next();
                          if (now >= entry.getValue()) {
                             Player p = Bukkit.getPlayer(entry.getKey());
                             if (p != null && p.isOnline()) {
                                 p.sendMessage(plugin.getConfigManager().getPrefixedMessage("invincibility-end"));
                             }
                             iterator.remove(); // Remove expired timer using iterator
                          } else {
                             anyLeft = true; // Mark if any timers are still active
                          }
                      }

                      if(!anyLeft){ // No more timers left, cancel this task
                         cancel();
                         invincibilityTask = null;
                      }
                 }
             }.runTaskTimer(plugin, 20L, 20L); // Check every second
         }
    }

     public boolean hasInvincibility(Player player) {
        Long endTime = invincibilityTimers.get(player.getUniqueId());
        return endTime != null && System.currentTimeMillis() < endTime;
     }

    private void startGameTimers() {
        // --- Game End Timer ---
         gameTimeRemaining = plugin.getConfigManager().getGameTimeLimitSeconds();
         if (gameTimeRemaining > 0) {
            gameTimerTask = new BukkitRunnable() {
                @Override
                public void run() {
                     if (state != GameState.IN_GAME) { cancel(); gameTimerTask = null; return; }

                     if (gameTimeRemaining <= 0) {
                         // Time limit reached - end in a draw
                         endGame(null); // Pass null for winner = draw
                         cancel();
                         gameTimerTask = null;
                         return;
                     }

                     // Update action bar?
                     updateActionBars();

                     // Announce time remaining at intervals? (e.g., 5 min, 1 min, 30s)
                     // TODO: Implement time announcements if desired

                     gameTimeRemaining--;
                }
            }.runTaskTimer(plugin, 0L, 20L);
         }

        // --- Chest Refill Timers ---
        // TODO: Implement chest refill logic and scheduling based on config times
    }


    public void handlePlayerDeath(Player deceased, Player killer) {
         if (state != GameState.IN_GAME) return;

         // Remove player from active list
         players.remove(deceased.getUniqueId());
         invincibilityTimers.remove(deceased.getUniqueId()); // Clear invincibility on death
         selectedKits.remove(deceased.getUniqueId()); // Clear kit selection


         // Send death message
         Map<String, String> placeholders = new HashMap<>();
         placeholders.put("player", deceased.getName()); // For generic eliminated message
         if (killer != null && !killer.equals(deceased)) { // Check killer is not self
            placeholders.put("victim", deceased.getName());
             placeholders.put("killer", killer.getName());
             broadcastMessage("player-eliminated-by-player", placeholders);
             // Add kill score/stats if implementing stats system
         } else {
             broadcastMessage("player-eliminated", placeholders);
         }

        // Make player a spectator if enabled
        if (plugin.getConfigManager().isSpectatorsAllowed()) {
            // Delay slightly before making spectator to ensure respawn happens first
            new BukkitRunnable() {
                @Override
                 public void run() {
                    if(deceased.isOnline()){ // Check again if they are still online
                        addSpectator(deceased); // Handles teleporting and setting gamemode
                    }
                 }
            }.runTaskLater(plugin, 2L); // 2 ticks delay

        } else {
             // If spectators disabled, remove them fully from arena context (will be teleported by removePlayer)
             removePlayer(deceased);
        }

        // Check win condition
        checkWinCondition();
    }

    private void checkWinCondition() {
        if (state != GameState.IN_GAME) return;

        if (players.size() <= 1) {
             Player winner = null;
             if (players.size() == 1) {
                 UUID winnerUUID = players.get(0); // The last one remaining
                 winner = Bukkit.getPlayer(winnerUUID);
             }
             endGame(winner); // End the game, pass winner (or null if somehow 0 players left)
         }
    }

     private void endGame(Player winner) {
         if (state != GameState.IN_GAME && state != GameState.STARTING) return; // Prevent double execution, allow ending from starting state if forced
         state = GameState.ENDING;

        // Cancel game tasks
        if (countdownTask != null) countdownTask.cancel(); countdownTask = null; // Also cancel countdown if somehow ending from starting
        if (gameTimerTask != null) gameTimerTask.cancel(); gameTimerTask = null;
        if (invincibilityTask != null) invincibilityTask.cancel(); invincibilityTask = null;
        // Cancel any other game-related tasks (like chest refills)

         // Announce Winner / Draw
         if (winner != null && winner.isOnline()) {
             broadcastMessage("game-won", Map.of("player", winner.getName(), "arena", name));
             if (plugin.getConfigManager().showWinnerTitle()) {
                 Title winTitle = Title.title(
                         Component.text(winner.getName() + " Wins!", NamedTextColor.GOLD, net.kyori.adventure.text.format.TextDecoration.BOLD),
                         Component.empty(),
                         Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))
                 );
                 sendTitleToAll(winTitle); // Send to winner and spectators
                  winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f); // Sound for winner
             }
             // Keep winner in the game slightly longer? Or teleport immediately? Teleport with delay.
         } else {
              broadcastMessage("game-draw", Map.of("arena", name)); // Add arena placeholder
              if (plugin.getConfigManager().showWinnerTitle()) { // Show draw title
                  Title drawTitle = Title.title(
                         Component.text("Draw!", NamedTextColor.YELLOW),
                          Component.text("Arena: " + name, NamedTextColor.GRAY), // Add subtitle with arena name
                         Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))
                 );
                 sendTitleToAll(drawTitle);
              }
         }
        // Clear action bars
         getOnlinePlayersAndSpectators().forEach(p -> p.sendActionBar(Component.empty()));


        // Delay before reset/teleporting players out
        new BukkitRunnable() {
            @Override
            public void run() {
                 if(state == GameState.ENDING) { // Check if state is still ending (wasn't force stopped)
                    resetArena(true); // Trigger regeneration and reset
                 }
            }
        }.runTaskLater(plugin, plugin.getConfigManager().getEndGameDelaySeconds() * 20L);
    }

    // Stop game forcefully (e.g., command, disable, reload)
    public void stopGame(boolean force) {
         if(state == GameState.WAITING || state == GameState.DISABLED || state == GameState.REGENERATING) return; // Nothing to stop

          GameState previousState = state; // Keep track of state before stopping

         // Cancel tasks
         if (countdownTask != null) countdownTask.cancel(); countdownTask = null;
         if (gameTimerTask != null) gameTimerTask.cancel(); gameTimerTask = null;
         if (invincibilityTask != null) invincibilityTask.cancel(); invincibilityTask = null;
         if (regenerationTask != null) regenerationTask.cancel(); regenerationTask = null; // Cancel regen if stopping during it


          state = GameState.ENDING; // Temporarily set to ending to allow cleanup

          // Use a copy to avoid ConcurrentModificationException while removing
         List<Player> currentPlayers = new ArrayList<>(getOnlinePlayers());
         List<Player> currentSpectators = new ArrayList<>(getOnlineSpectators());


          currentPlayers.forEach(this::removePlayer);
          currentSpectators.forEach(p -> removeSpectator(p, true));


          // Ensure internal lists are definitely cleared
          players.clear();
          spectators.clear();
          selectedKits.clear();
          invincibilityTimers.clear();
          playerPlacedBlocks.clear(); // Clear placed blocks on stop too

         if (force) {
              plugin.getLogger().info("Force stopped arena: " + name);
              // On force stop, transition directly to WAITING if enabled, or DISABLED if not
              state = isEnabled() ? GameState.WAITING : GameState.DISABLED;
              // Optionally broadcast a force stop message?
               // broadcastMessage("arena-force-stopped", Map.of("arena", name));
         } else {
              // Normal stop flow (e.g., from endGame timeout) should lead to resetArena
               if(previousState != GameState.ENDING) { // Only trigger reset if not already called by endGame
                   resetArena(true); // Regenerate after normal stop sequence (unless it was already ending)
               }
         }
          // Clear action bars if they were showing something
          getOnlinePlayersAndSpectators().forEach(p -> p.sendActionBar(Component.empty()));
    }

    // --- Regeneration ---

    private void resetArena(boolean regenerate) {
          // Players/Spectators should already be handled by the calling method (endGame/stopGame)
         // but ensure lists are clear just in case.
         players.clear();
         spectators.clear();
         selectedKits.clear();
         invincibilityTimers.clear();


         if (regenerate && !"NONE".equals(plugin.getConfigManager().getRegenerationMode())) {
             startRegeneration();
         } else {
              // If not regenerating (or mode is NONE), just set state to waiting/disabled
              state = isEnabled() ? GameState.WAITING : GameState.DISABLED;
              if (state == GameState.WAITING) {
                 broadcastMessage("arena-ready", Map.of("arena", name));
                 updateActionBars(); // Update action bar for waiting state
              }
         }
    }

    private void startRegeneration() {
        if (state == GameState.REGENERATING) return; // Already regenerating
        state = GameState.REGENERATING;
        broadcastMessage("arena-regenerating", Map.of("arena", name));
         plugin.getLogger().info("Starting regeneration for arena: " + name + " (Mode: " + plugin.getConfigManager().getRegenerationMode() + ")");

        if (regenerationTask != null) {
            regenerationTask.cancel(); // Cancel previous task if somehow stuck
        }

        regenerationTask = new BukkitRunnable() {
             @Override
             public void run() {
                 boolean success = false;
                 String regenMode = plugin.getConfigManager().getRegenerationMode();

                 try {
                      if ("FULL".equals(regenMode)) {
                          success = regenerateFull();
                      } else if ("PARTIAL".equals(regenMode)) {
                          success = regeneratePartial();
                      } else { // Should have been caught earlier, but handle NONE case
                          plugin.getLogger().info("Regeneration mode is NONE for '" + name + "'. Resetting chests only.");
                          refillChests(); // Still refill chests even if not regenerating blocks
                          success = true; // Treat as success as no block regen was intended
                      }

                      if (success) {
                           plugin.getLogger().info("Regeneration successful for arena: " + name);
                           state = isEnabled() ? GameState.WAITING : GameState.DISABLED; // Set state based on enabled status
                            if (state == GameState.WAITING) {
                               broadcastMessage("arena-ready", Map.of("arena", name));
                            }
                      } else {
                          plugin.getLogger().severe("Regeneration FAILED for arena: " + name + ". Arena remains disabled.");
                           setEnabled(false); // Explicitly disable if regeneration fails
                           plugin.getArenaManager().saveArena(Arena.this); // Save disabled state
                           state = GameState.DISABLED; // Ensure state is disabled
                           // Maybe broadcast an admin warning?
                      }

                 } catch (Exception e) {
                      plugin.getLogger().log(Level.SEVERE, "Error during regeneration for arena: " + name, e);
                      setEnabled(false); // Disable on error
                      plugin.getArenaManager().saveArena(Arena.this); // Save disabled state
                      state = GameState.DISABLED; // Ensure state is disabled
                 } finally {
                     regenerationTask = null; // Ensure task variable is cleared
                      if (state == GameState.WAITING) {
                         updateActionBars(); // Update action bars after regeneration finishes
                      }
                 }
             }
         }.runTaskLater(plugin, 1L); // Run on next tick to allow player kicks to fully process
    }

    // Method for FULL regeneration using saved block data
     private boolean regenerateFull() {
         if (world == null || corner1 == null || corner2 == null) {
              plugin.getLogger().severe("Cannot perform FULL regeneration for '" + name + "': World or bounds not loaded.");
              return false;
          }
         if (originalBlockData == null || originalBlockData.isEmpty()) {
              plugin.getLogger().warning("Cannot perform FULL regeneration for '" + name + "': No original block data saved/loaded. Was initial state saved correctly?");
             // Attempt partial regen as fallback? Or just fail? Fail for now.
              return false;
          }

         plugin.getLogger().info("Performing FULL regeneration for " + name + " using " + originalBlockData.size() + " saved blocks...");

         long startTime = System.currentTimeMillis();

         // Iterate through saved block data and set blocks back
         // THIS WILL LAG ON LARGE ARENAS! Use chunks or FAWE if performance is needed.
         for (Map.Entry<Location, BlockData> entry : originalBlockData.entrySet()) {
             Location loc = entry.getKey();
             BlockData data = entry.getValue();
             // Check if the location's world matches the arena world
              if (loc.getWorld() != null && !loc.getWorld().equals(this.world)) continue;

             world.getBlockAt(loc).setBlockData(data, false); // Set block data without physics updates for speed
         }

         // Reset player-placed block tracking (should be empty anyway after full regen)
         playerPlacedBlocks.clear();
         // Refill chests after restoring structure
         refillChests();

         long duration = System.currentTimeMillis() - startTime;
         plugin.getLogger().info("FULL regeneration for " + name + " completed in " + duration + " ms.");
         return true;
     }


      // Method for PARTIAL regeneration (clearing player blocks, refilling chests)
     private boolean regeneratePartial() {
          if (world == null || corner1 == null || corner2 == null) {
              plugin.getLogger().severe("Cannot perform PARTIAL regeneration for '" + name + "': World or bounds not loaded.");
              return false;
          }

          plugin.getLogger().info("Performing PARTIAL regeneration for " + name + ". Clearing " + playerPlacedBlocks.size() + " player-placed blocks...");
          long startTime = System.currentTimeMillis();

          // Use a copy to iterate while potentially modifying? Not needed here.
         Set<Location> blocksToRemove = new HashSet<>(playerPlacedBlocks);
         playerPlacedBlocks.clear(); // Clear original set immediately

         // Iterate through tracked player-placed blocks
         for (Location loc : blocksToRemove) {
             if (!loc.getWorld().equals(this.world)) continue; // Check world
             // Check if it's within bounds? Should be, but safety.
             if (isWithinBounds(loc)) {
                Block block = world.getBlockAt(loc);
                // Set block back to air if it was player placed
                block.setType(Material.AIR, false); // Set to air without physics
             }
         }


         // Refill chests
         refillChests();

         long duration = System.currentTimeMillis() - startTime;
         plugin.getLogger().info("PARTIAL regeneration for " + name + " completed in " + duration + " ms.");
         return true;
     }


     // --- Chest Refilling ---
     public void refillChests() {
         plugin.getLogger().info("Refilling chests for arena: " + name);
         if (chestLocations == null || chestLocations.isEmpty()) {
             plugin.getLogger().warning("No chest locations defined for arena '" + name + "'. Skipping refill.");
             return;
         }

         for (Location loc : chestLocations) {
             if (loc.getWorld() == null || !loc.getWorld().equals(this.world)) continue; // Check world validity
             Block block = world.getBlockAt(loc);
              // Ensure chunk is loaded before accessing block state
             if (!block.getChunk().isLoaded()) {
                 block.getChunk().load(); // Load the chunk if it's not
             }

             if (block.getState() instanceof Chest) { // Check it's still a chest
                 Chest chest = (Chest) block.getState();
                 Inventory inv = chest.getBlockInventory(); // Get the block's inventory
                 inv.clear(); // Clear existing items

                 // Fill with loot - Requires a Loot Table system!
                 // Placeholder: Fill with some basic items
                 fillChestWithPlaceholderLoot(inv);

                 chest.update(); // Update the chest state
             } else {
                  plugin.getLogger().warning("Block at configured chest location " + LocationUtil.serializeLocation(loc) + " in arena '" + name + "' is not a Chest!");
             }
         }
          if (!players.isEmpty() || !spectators.isEmpty()) { // Only play sound if someone is potentially there
             playSoundToAll(Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f); // Sound effect for refill
             broadcastMessage("chest-refilled"); // Announce refill
          }
     }


     // Very basic placeholder loot - REPLACE WITH A REAL LOOT TABLE SYSTEM
     private void fillChestWithPlaceholderLoot(Inventory inventory) {
         Random random = new Random();
         int itemsToPlace = 3 + random.nextInt(4); // Place 3-6 items

         List<ItemStack> possibleLoot = Arrays.asList(
                 new ItemStack(Material.STONE_SWORD),
                 new ItemStack(Material.IRON_SWORD),
                 new ItemStack(Material.BOW),
                 new ItemStack(Material.ARROW, 8 + random.nextInt(9)), // 8-16 arrows
                 new ItemStack(Material.COOKED_BEEF, 2 + random.nextInt(3)), // 2-4 food
                 new ItemStack(Material.GOLDEN_APPLE),
                 new ItemStack(Material.OAK_PLANKS, 16 + random.nextInt(17)), // 16-32 blocks
                 new ItemStack(Material.COBBLESTONE, 16 + random.nextInt(17)),
                 new ItemStack(Material.WATER_BUCKET),
                 new ItemStack(Material.LAVA_BUCKET), // Maybe rarer?
                 new ItemStack(Material.IRON_HELMET),
                 new ItemStack(Material.IRON_CHESTPLATE),
                 new ItemStack(Material.IRON_LEGGINGS),
                 new ItemStack(Material.IRON_BOOTS)
         );

         for (int i = 0; i < itemsToPlace; i++) {
             int slot;
             int attempts = 0;
             do {
                 slot = random.nextInt(inventory.getSize());
                 attempts++;
             } while (inventory.getItem(slot) != null && attempts < inventory.getSize() * 2); // Find an empty slot, limit attempts

              if (inventory.getItem(slot) == null) { // Place item if slot is still empty
                 ItemStack lootItem = possibleLoot.get(random.nextInt(possibleLoot.size())).clone();
                 inventory.setItem(slot, lootItem);
              }
         }
     }


     // Save the initial state of all blocks within the bounds (for FULL regen)
     // WARNING: THIS IS RESOURCE INTENSIVE AND CAN CREATE HUGE CONFIG FILES!
    public void saveInitialState() {
         if (!"FULL".equals(plugin.getConfigManager().getRegenerationMode())) {
              originalBlockData = null; // Clear data if not using FULL mode
             return;
         }
         if (world == null || corner1 == null || corner2 == null) {
              plugin.getLogger().severe("Cannot save initial state for '" + name + "': World or bounds not defined.");
              return;
          }

          plugin.getLogger().info("Saving initial block state for FULL regeneration in arena: " + name + "...");
          long startTime = System.currentTimeMillis();

          originalBlockData = new ConcurrentHashMap<>(); // Use concurrent map

          int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
          int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
          int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
          int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
          int maxY = Math.max(corner1.getBlockY(), corner2.getBlockY());
          int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

          int blockCount = 0;
          // Ensure chunks covering the region are loaded before iterating? Risky/laggy. Assume available.
          for (int x = minX; x <= maxX; x++) {
              for (int y = minY; y <= maxY; y++) {
                  for (int z = minZ; z <= maxZ; z++) {
                       // Ensure world is still valid inside the loop?
                      if (world == null) {
                           plugin.getLogger().severe("World became null during initial state save for arena: " + name);
                           originalBlockData = null; // Invalidate saved data
                           return; // Abort saving
                      }
                      Location loc = new Location(world, x, y, z);
                      Block block = world.getBlockAt(loc);
                      // Only save non-air blocks? Saves space, but requires restoring air too. Save all for now.
                      originalBlockData.put(loc.clone(), block.getBlockData().clone()); // Clone location and data
                      blockCount++;
                  }
              }
          }

          long duration = System.currentTimeMillis() - startTime;
          plugin.getLogger().info("Saved state of " + blockCount + " blocks for arena '" + name + "' in " + duration + " ms.");

         // Let ArenaManager handle saving the file after this returns.
    }


    // --- Utility Methods ---

    public void broadcastMessage(String key) {
        broadcastMessage(key, Collections.emptyMap());
    }

    public void broadcastMessage(String key, Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getPrefixedMessage(key, placeholders);
        getOnlinePlayersAndSpectators().forEach(p -> p.sendMessage(message));
    }

     public void sendActionbarOrChatToAll(String message) {
        if (plugin.getConfigManager().showActionBarMessages()) {
             Component component = Component.text(ChatColor.translateAlternateColorCodes('&', message));
             getOnlinePlayersAndSpectators().forEach(p -> p.sendActionBar(component));
        } else {
            // Fallback to chat if action bar is disabled - use Prefixed message for chat fallback
            String chatMessage = plugin.getConfigManager().getMessage("prefix") + ChatColor.translateAlternateColorCodes('&', message);
             getOnlinePlayersAndSpectators().forEach(p -> p.sendMessage(chatMessage));
        }
    }

    public void sendTitleToAll(Title title) {
         getOnlinePlayersAndSpectators().forEach(p -> p.showTitle(title));
    }

    public void playSoundToAll(Sound sound, float volume, float pitch) {
         getOnlinePlayersAndSpectators().forEach(p -> p.playSound(p.getLocation(), sound, volume, pitch));
    }


    private void resetPlayerState(Player player, GameMode gameMode) {
        player.setGameMode(gameMode);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(5.0f); // Default saturation
        player.setExp(0);
        player.setLevel(0);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]); // Ensure armor is cleared with empty array
        player.setFireTicks(0);
        // Clear active potions safely
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setAllowFlight(gameMode == GameMode.SPECTATOR || gameMode == GameMode.CREATIVE);
         player.setFlying(gameMode == GameMode.SPECTATOR); // Only force flying for spectators immediately
         player.setFlySpeed(0.1f); // Reset fly speed (spectator sets its own)
         player.setWalkSpeed(0.2f); // Reset walk speed
    }

    public List<Player> getOnlinePlayers() {
         List<Player> online = new ArrayList<>();
         // Use iterator to safely handle potential mid-iteration removal elsewhere (though CopyOnWriteArrayList helps)
         Iterator<UUID> iterator = players.iterator();
         while(iterator.hasNext()){
             UUID uuid = iterator.next();
             Player p = Bukkit.getPlayer(uuid);
             if (p != null && p.isOnline()) {
                 online.add(p);
             } else {
                 // Player is offline or invalid, remove them from the list
                 iterator.remove();
                 selectedKits.remove(uuid); // Clean up associated data
                 invincibilityTimers.remove(uuid);
                 plugin.getArenaManager().removePlayerFromArena(p); // Ensure manager map is also cleared
             }
         }
         return online;
     }

    public List<Player> getOnlineSpectators() {
         List<Player> online = new ArrayList<>();
          Iterator<UUID> iterator = spectators.iterator();
         while(iterator.hasNext()){
             UUID uuid = iterator.next();
             Player p = Bukkit.getPlayer(uuid);
             if (p != null && p.isOnline()) {
                 online.add(p);
             } else {
                 iterator.remove(); // Clean up offline spectator
                 plugin.getArenaManager().removePlayerFromArena(p); // Spectators are also in the playerArenas map
             }
         }
         return online;
     }

      public List<Player> getOnlinePlayersAndSpectators() {
        List<Player> online = new ArrayList<>();
        online.addAll(getOnlinePlayers()); // This now handles cleanup internally
        online.addAll(getOnlineSpectators()); // This now handles cleanup internally
        return online;
    }


    public boolean isWithinBounds(Location loc) {
        if (world == null || corner1 == null || corner2 == null || loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int maxY = Math.max(corner1.getBlockY(), corner2.getBlockY());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        return loc.getX() >= minX && loc.getX() < maxX + 1 && // Include maxX block boundary
               loc.getY() >= minY && loc.getY() < maxY + 1 && // Include maxY block boundary
               loc.getZ() >= minZ && loc.getZ() < maxZ + 1;   // Include maxZ block boundary
    }

    public boolean isFullySetup() {
         return name != null && !name.isEmpty() &&
                lobbySpawn != null && spectatorSpawn != null &&
                playerSpawns != null && !playerSpawns.isEmpty() && // Need at least one spawn? Min players usually needed.
                corner1 != null && corner2 != null &&
                world != null;
                // Chests and Center are optional
     }


    public void updateActionBars() {
         if (!plugin.getConfigManager().showActionBarMessages()) return;

         String actionBarMessage = "";
         Map<String, String> placeholders = new HashMap<>();
         placeholders.put("arena", name);

         switch (state) {
            case WAITING:
                 int needed = Math.max(0, plugin.getConfigManager().getMinPlayersToStart() - players.size());
                 placeholders.put("needed", String.valueOf(needed));
                 placeholders.put("current", String.valueOf(players.size()));
                 placeholders.put("max", String.valueOf(plugin.getConfigManager().getMaxPlayersPerArena()));
                 actionBarMessage = plugin.getConfigManager().getMessage("actionbar-waiting", placeholders);
                 break;
            case STARTING:
                 placeholders.put("time", String.valueOf(countdownSeconds));
                 actionBarMessage = plugin.getConfigManager().getMessage("actionbar-starting", placeholders);
                 break;
             case IN_GAME:
                  placeholders.put("players", String.valueOf(players.size()));
                  placeholders.put("time", formatTime(gameTimeRemaining));
                  // TODO: Add next event placeholder if implementing refills/events
                  actionBarMessage = plugin.getConfigManager().getMessage("actionbar-ingame", placeholders);
                 break;
             case ENDING:
                // Show nothing or a generic "Game Over" message?
                 actionBarMessage = plugin.getConfigManager().getMessage("actionbar-ending", placeholders);
                 break;
             case REGENERATING:
                 actionBarMessage = plugin.getConfigManager().getMessage("actionbar-regenerating", placeholders);
                break;
             case DISABLED:
                  actionBarMessage = plugin.getConfigManager().getMessage("actionbar-disabled", placeholders);
                  break;
        }

         if (!actionBarMessage.isEmpty()) {
            Component component = Component.text(ChatColor.translateAlternateColorCodes('&', actionBarMessage));
            getOnlinePlayersAndSpectators().forEach(p -> p.sendActionBar(component));
         } else {
            // Send empty component to clear bar if no message applies
             getOnlinePlayersAndSpectators().forEach(p -> p.sendActionBar(Component.empty()));
         }
    }

    private String formatTime(int totalSeconds) {
         if (totalSeconds < 0) totalSeconds = 0;
         int minutes = totalSeconds / 60;
         int seconds = totalSeconds % 60;
         return String.format("%02d:%02d", minutes, seconds);
    }


     // --- Getters ---
    public String getName() { return name; }
    public GameState getState() { return state; }
    public List<UUID> getPlayers() { return players; } // Returns concurrent list, be careful if iterating/modifying outside synchronized block
    public List<UUID> getSpectators() { return spectators; } // Returns concurrent list
    public boolean isEnabled() { return enabled; }
    public Location getLobbySpawn() { return lobbySpawn; }
    public Location getSpectatorSpawn() { return spectatorSpawn; }
    public List<Location> getPlayerSpawns() { return playerSpawns; }
    public List<Location> getChestLocations() { return chestLocations; }
    public Location getCorner1() { return corner1; }
    public Location getCorner2() { return corner2; }
     public Location getCenter() { return center; }
     public World getWorld() { return world; }
    public Map<Location, BlockData> getOriginalBlockData() { return originalBlockData; } // Mainly for saving

    // --- Setters (used by setup mostly) ---
    public void setEnabled(boolean enabled) {
         boolean changed = this.enabled != enabled;
         this.enabled = enabled;
         if (changed) { // Only act if state actually changed
             if (!enabled && this.state != GameState.DISABLED) {
                 stopGame(true); // Force stop game if disabling
                 this.state = GameState.DISABLED;
                  updateActionBars();
             } else if (enabled && this.state == GameState.DISABLED && isFullySetup()) {
                 this.state = GameState.WAITING; // Transition to waiting if enabled and ready
                 updateActionBars();
             }
         }
    }
    public void setLobbySpawn(Location lobbySpawn) { this.lobbySpawn = lobbySpawn; if(world == null && lobbySpawn != null) world = lobbySpawn.getWorld(); }
    public void setSpectatorSpawn(Location spectatorSpawn) { this.spectatorSpawn = spectatorSpawn; }
    public void setPlayerSpawns(List<Location> playerSpawns) { this.playerSpawns = playerSpawns; }
    public void setChestLocations(List<Location> chestLocations) { this.chestLocations = chestLocations; }
    public void setCorner1(Location corner1) { this.corner1 = corner1; if(world == null && corner1 != null) world = corner1.getWorld(); }
    public void setCorner2(Location corner2) { this.corner2 = corner2; if(world == null && corner2 != null) world = corner2.getWorld(); }
     public void setCenter(Location center) { this.center = center; }
     public void setOriginalBlockData(Map<Location, BlockData> data) { this.originalBlockData = data; }


     // State check helpers
     public boolean isPlayer(Player player) { return players.contains(player.getUniqueId()); }
     public boolean isSpectator(Player player) { return spectators.contains(player.getUniqueId()); }

    public void setSelectedKit(UUID uniqueId, Kit kit) {
        if (state == GameState.WAITING || state == GameState.STARTING) {
             selectedKits.put(uniqueId, kit);
        }
    }

     // Method to add a block location for partial regeneration tracking
     public void addPlayerPlacedBlock(Location location) {
         // Only track if using partial regen and the block is within the defined bounds
        if ("PARTIAL".equals(plugin.getConfigManager().getRegenerationMode()) && isWithinBounds(location)) {
            // Don't track blocks from the ignore list (like TNT?) - config does not list TNT though.
             // Set<Material> ignored = plugin.getConfigManager().getIgnoredPartialRegenMaterials(); // Add config option if needed
             // if (!ignored.contains(location.getBlock().getType())) {
                 playerPlacedBlocks.add(location.getBlock().getLocation()); // Store block location (int coords)
             //}
        }
    }
}