package io.github.foundationgames.deathrun.game.state.logic;

import io.github.foundationgames.deathrun.game.DeathRunConfig;
import io.github.foundationgames.deathrun.game.map.DeathRunMap;
import io.github.foundationgames.deathrun.game.state.DRGame;
import io.github.foundationgames.deathrun.game.state.DRPlayer;
import io.github.foundationgames.deathrun.game.state.DRTeam;
import io.github.foundationgames.deathrun.game.state.DRWaiting;
import io.github.foundationgames.deathrun.util.DRUtil;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.TranslatableText;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.GameActivity;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.util.ItemStackBuilder;

import java.util.*;

public class DRPlayerLogic implements PlayerSet {
    private final ServerWorld world;
    private final GameActivity game;
    private final DeathRunMap map;
    private final DeathRunConfig config;
    private final Map<ServerPlayerEntity, DRPlayer> players = new HashMap<>();

    public DRPlayerLogic(ServerWorld world, GameActivity game, DeathRunMap map, DeathRunConfig config) {
        this.world = world;
        this.game = game;
        this.map = map;
        this.config = config;
    }

    public Collection<DRPlayer> getPlayers() {
        return players.values();
    }

    public List<DRPlayer> getPlayers(Random random) {
        var list = new ArrayList<>(getPlayers());
        Collections.shuffle(list, random);
        return list;
    }

    public void resetWaiting(ServerPlayerEntity player) {
        var spawn = map.spawn;
        var min = spawn.min();
        var max = spawn.max();
        var x = min.getX() + world.random.nextInt(max.getX() - min.getX()) + 0.5;
        var z = min.getZ() + world.random.nextInt(max.getZ() - min.getZ()) + 0.5;
        player.teleport(world, x, min.getY(), z, 0f, 0f);

        var leaveItem = ItemStackBuilder.of(Items.MAGENTA_GLAZED_TERRACOTTA)
                .setName(new TranslatableText("item.deathrun.leave_game").styled(style -> style.withColor(0x896bff).withItalic(false))).build();
        DRItemLogic.apply("leave_game", leaveItem);

        if (!config.runnersOnly()) {
            var runnerItem = ItemStackBuilder.of(DRUtil.createRunnerHead())
                    .setName(new TranslatableText("item.deathrun.request_runner").styled(style -> style.withColor(0x6bffc1).withItalic(false))).build();
            DRItemLogic.apply("request_runner", runnerItem);

            var deathItem = ItemStackBuilder.of(DRUtil.createDeathHead())
                    .setName(new TranslatableText("item.deathrun.request_death").styled(style -> style.withColor(0x6bffc1).withItalic(false))).build();
            DRItemLogic.apply("request_death", deathItem);

            var clearItem = ItemStackBuilder.of(DRUtil.createClearHead())
                    .setName(new TranslatableText("item.deathrun.request_clear").styled(style -> style.withColor(0xff6e42).withItalic(false))).build();
            DRItemLogic.apply("request_clear", clearItem);

            player.getInventory().setStack(3, runnerItem);
            player.getInventory().setStack(4, clearItem);
            player.getInventory().setStack(5, deathItem);
        } else {
            var runnerItem = ItemStackBuilder.of(DRUtil.createRunnerHeadB())
                    .setName(new TranslatableText("item.deathrun.runners_only").styled(style -> style.withColor(0xffca38).withItalic(false))).build();
            player.getInventory().setStack(4, runnerItem);
        }
        player.getInventory().setStack(8, leaveItem);
        player.changeGameMode(GameMode.ADVENTURE);
    }

    public void resetActive(ServerPlayerEntity player) {
        var pl = get(player);

        if (pl instanceof DRGame.Player gamePlayer) {
            var spawn = gamePlayer.team == DRTeam.DEATHS ? map.deathStart : map.runnerStart;
            var min = spawn.min();
            var max = spawn.max();
            var x = min.getX() + world.random.nextInt(max.getX() - min.getX()) + 0.5;
            var z = min.getZ() + world.random.nextInt(max.getZ() - min.getZ()) + 0.5;
            player.teleport(world, x, min.getY(), z, 0f, 0f);
            pl.getPlayer().getInventory().clear();
            if (gamePlayer.team == DRTeam.RUNNERS) {
                var boostItem = ItemStackBuilder.of(Items.FEATHER)
                        .setName(new TranslatableText("item.deathrun.boost_feather").styled(style -> style.withColor(0x9ce3ff).withItalic(false))).build();
                DRItemLogic.apply("boost", boostItem);

                player.getInventory().setStack(0, boostItem);
            }
        }
        player.changeGameMode(GameMode.ADVENTURE);
    }

    public static void sortTeams(Random random, DRPlayerLogic waiting, DRPlayerLogic game) {
        var waitingPlayers = waiting.getPlayers(random);
        int maxDeaths = Math.min(3, (int)Math.ceil(waitingPlayers.size() * 0.17));
        var runners = new ArrayList<DRWaiting.Player>();
        var deaths = new ArrayList<DRWaiting.Player>();
        for (DRPlayer p : waitingPlayers) {
            if (p instanceof DRWaiting.Player player) {
                if (player.requestedTeam == DRTeam.DEATHS && deaths.size() < maxDeaths) {
                    deaths.add(player);
                } else if (player.requestedTeam == DRTeam.RUNNERS) {
                    runners.add(player);
                }
            }
        }
        for (DRPlayer p : waitingPlayers) {
            if (p instanceof DRWaiting.Player player &&
                    (!deaths.contains(player) && !runners.contains(player))
            ) {
                if (deaths.size() < maxDeaths) {
                    deaths.add(player);
                } else {
                    runners.add(player);
                }
            }
        }
        for (DRWaiting.Player player : runners) {
            game.add(new DRGame.Player(player.getPlayer(), game, DRTeam.RUNNERS));
        }
        for (DRWaiting.Player player : deaths) {
            game.add(new DRGame.Player(player.getPlayer(), game, DRTeam.DEATHS));
        }
    }

    public void tick() {
        this.getPlayers().forEach(DRPlayer::tick);
    }

    public void onLeave(ServerPlayerEntity player) {
        this.players.remove(player);
    }

    public PlayerOfferResult offerWaiting(PlayerOffer offer) {
        this.add(new DRWaiting.Player(offer.player(), this));
        return offer.accept(world, map.spawn.centerBottom())
                .and(() -> this.resetWaiting(offer.player()));
    }

    public void remove(DRPlayer player) {
        this.players.remove(player.getPlayer());
    }

    public void add(DRPlayer player) {
        this.players.put(player.getPlayer(), player);
    }

    public DRPlayer get(ServerPlayerEntity player) {
        return this.players.get(player);
    }

    @Override
    public boolean contains(UUID id) {
        var player = world.getPlayerByUuid(id);
        return player instanceof ServerPlayerEntity && players.containsKey(player);
    }

    @Override
    public @Nullable ServerPlayerEntity getEntity(UUID id) {
        var player = world.getPlayerByUuid(id);
        return player instanceof ServerPlayerEntity sPlayer ? sPlayer : null;
    }

    @Override
    public int size() {
        return players.size();
    }

    @Override
    public Iterator<ServerPlayerEntity> iterator() {
        return players.keySet().iterator();
    }
}