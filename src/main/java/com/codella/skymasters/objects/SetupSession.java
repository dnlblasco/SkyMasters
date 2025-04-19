package com.codella.skymasters.objects;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

// Holds temporary data for a player configuring an arena
public class SetupSession {

    private final String arenaName;
    private Location pos1;
    private Location pos2;
    private Location lobbySpawn;
    private Location spectatorSpawn;
    private Location center;
    private final List<Location> playerSpawns = new ArrayList<>();
    private final List<Location> chestLocations = new ArrayList<>();


    public SetupSession(String arenaName) {
        this.arenaName = arenaName;
    }

    // --- Getters ---
    public String getArenaName() { return arenaName; }
    public Location getPos1() { return pos1; }
    public Location getPos2() { return pos2; }
    public Location getLobbySpawn() { return lobbySpawn; }
    public Location getSpectatorSpawn() { return spectatorSpawn; }
    public Location getCenter() { return center; }
    public List<Location> getPlayerSpawns() { return playerSpawns; }
    public List<Location> getChestLocations() { return chestLocations; }

    // --- Setters ---
    public void setPos1(Location pos1) { this.pos1 = pos1; }
    public void setPos2(Location pos2) { this.pos2 = pos2; }
    public void setLobbySpawn(Location lobbySpawn) { this.lobbySpawn = lobbySpawn; }
    public void setSpectatorSpawn(Location spectatorSpawn) { this.spectatorSpawn = spectatorSpawn; }
    public void setCenter(Location center) { this.center = center; }

    // Note: Spawns and Chests are added/removed directly via the List getters.
}