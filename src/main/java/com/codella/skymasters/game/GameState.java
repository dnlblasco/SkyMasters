package com.codella.skymasters.game;

public enum GameState {
    DISABLED, // Arena is not loaded or configured properly, or explicitly disabled by admin
    WAITING, // Waiting for players to join, countdown not started
    STARTING, // Countdown has started
    IN_GAME, // Game is active, players are fighting
    ENDING, // Game has finished (winner decided or draw), waiting for reset timer
    REGENERATING // Arena map is being reset
}