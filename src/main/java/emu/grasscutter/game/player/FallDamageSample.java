package emu.grasscutter.game.player;

import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.world.Scene;

/** One short-lived landing sample owned by a session, never by the shared packet handler. */
public record FallDamageSample(EntityAvatar entity, Scene scene, float speed, long atMillis) {}
