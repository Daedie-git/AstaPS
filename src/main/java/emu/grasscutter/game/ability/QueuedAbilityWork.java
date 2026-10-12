package emu.grasscutter.game.ability;

import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.World;
import emu.grasscutter.server.game.GameSession;

/** Queue-boundary validation, not a transaction against concurrent scene teardown. */
final class QueuedAbilityWork {
    private final PlayerContext dispatcher;
    private final PlayerContext owner;
    private final EntityContext source;
    private final EntityContext target;

    QueuedAbilityWork(Player player, Ability ability, GameEntity target) {
        source = new EntityContext(ability.getOwner());
        this.target = new EntityContext(target);
        // World constructs its pseudo-entity before assigning host.world. Only that known
        // construction seam may bind a null player world to the entity's concrete world.
        World startupWorld = ability.getOwner() instanceof EntityWorld ? source.world : null;
        dispatcher = new PlayerContext(player, startupWorld, false);
        owner = new PlayerContext(ability.getPlayerOwner(), startupWorld,
            ability.getPlayerOwner() != player);
    }

    boolean isCurrent() {
        return dispatcher.isCurrent() && owner.isCurrent()
            && dispatcher.world == owner.world
            && source.isCurrent(owner.world) && target.isCurrent(owner.world);
    }

    private static final class PlayerContext {
        final Player player;
        final World world;
        final Scene scene;
        final GameSession session;

        PlayerContext(Player player, World startupWorld, boolean bindScene) {
            this.player = player;
            World current = player == null ? null : player.getWorld();
            world = current == null && startupWorld != null && startupWorld.getHost() == player
                ? startupWorld : current;
            scene = player == null || !bindScene ? null : player.getScene();
            session = player == null ? null : player.getSession();
        }

        boolean isCurrent() {
            return player != null && world != null && player.getWorld() == world
                && (scene == null || player.getScene() == scene)
                && player.getSession() == session
                && (session == null || session.acceptsGameWork());
        }
    }

    private static final class EntityContext {
        final GameEntity entity;
        final World world;
        final Scene scene;
        final Object epoch;
        final boolean registered;
        final PlayerContext realOwner;

        EntityContext(GameEntity entity) {
            this.entity = entity;
            world = entity == null ? null : entity.getWorld();
            // EntityWorld's scene follows its host; its lifetime is world-scoped.
            scene = entity == null || entity instanceof EntityWorld ? null : entity.getScene();
            epoch = entity == null ? null : entity.getAbilityWorkEpoch();
            registered = scene != null && scene.getEntityById(entity.getId()) == entity;
            Player player = entity instanceof EntityTeam team ? team.getPlayer()
                : entity instanceof EntityAvatar avatar ? avatar.getPlayer() : null;
            realOwner = player == null ? null : new PlayerContext(player, null, entity instanceof EntityAvatar);
        }

        boolean isCurrent(World expectedWorld) {
            // Registration is optional at startup, but actual world/owner lifetime is not.
            return entity != null && world != null && world == expectedWorld
                && entity.getWorld() == world
                && (entity instanceof EntityWorld || entity.getScene() == scene)
                && (scene == null || scene.getScriptManager() == null
                    || !scene.getScriptManager().isDestroyed())
                && entity.getAbilityWorkEpoch() == epoch
                && (realOwner == null || (realOwner.world == expectedWorld && realOwner.isCurrent()))
                && (!registered || scene.getEntityById(entity.getId()) == entity);
        }
    }
}
