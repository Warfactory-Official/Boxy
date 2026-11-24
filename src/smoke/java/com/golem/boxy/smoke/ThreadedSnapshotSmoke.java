package com.golem.boxy.smoke;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import com.golem.boxy.vss.server.ColumnSnapshot;
import com.golem.boxy.vss.server.ColumnSnapshotter;
import com.golem.boxy.vss.server.LiveSnapshots;
import com.golem.boxy.vss.server.PlayerRequestState;
import com.golem.boxy.vss.server.ServerNetworking;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Threaded-host stress for live snapshots, console driven ({@code /bxs ...}); region workers rewrite hot chunks via
 * repeating command blocks while the real request pipeline and a direct {@link LiveSnapshots} capture read them.
 * Hot box x 0..15, y 0..40, z 0..15 of chunks (ox>>4, 0) is always one uniform block at tick boundaries.
 */
@Mod("boxy_snapsmoke")
public final class ThreadedSnapshotSmoke {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int[] ORIGINS = {0, 512, 1024};
    private static final String[] FILLS = {"minecraft:stone", "minecraft:glass", "minecraft:oak_planks"};
    private static final int PER_TICK = 4;

    private final AtomicLong payloads = new AtomicLong(), torn = new AtomicLong(), mismatches = new AtomicLong(),
            direct = new AtomicLong(), directFailed = new AtomicLong(), parseErrors = new AtomicLong();
    private final Map<Integer, Integer> hashes = new ConcurrentHashMap<>();
    private final Map<String, Long> directThreads = new ConcurrentHashMap<>();
    private final LiveSnapshots snapshots = LiveSnapshots.create();
    private FakePlayer player;
    private PlayerRequestState state;
    private int nextId = 1;
    private int runTicks;
    private boolean directMode;
    private volatile boolean exactMode;

    public ThreadedSnapshotSmoke() {
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("bxs").requires(s -> s.hasPermission(2));
        root.then(Commands.literal("setup").executes(c -> run(c.getSource().getServer(), this::setup)));
        root.then(Commands.literal("run").then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                .executes(c -> {
                    runTicks = IntegerArgumentType.getInteger(c, "ticks");
                    directMode = true;
                    return 1;
                })));
        root.then(Commands.literal("cold").then(Commands.argument("n", IntegerArgumentType.integer(1))
                .executes(c -> run(c.getSource().getServer(), s -> cold(s, IntegerArgumentType.getInteger(c, "n"))))));
        root.then(Commands.literal("gen").then(Commands.argument("n", IntegerArgumentType.integer(1))
                .executes(c -> run(c.getSource().getServer(), s -> gen(s, IntegerArgumentType.getInteger(c, "n"))))));
        root.then(Commands.literal("quiesce").executes(c -> run(c.getSource().getServer(), this::quiesce)));
        root.then(Commands.literal("verify").executes(c -> {
            exactMode = true;
            runTicks = 40;
            directMode = true;
            return 1;
        }));
        root.then(Commands.literal("report").executes(c -> run(c.getSource().getServer(), this::report)));
        event.getDispatcher().register(root);
    }

    private interface Op {
        void run(MinecraftServer server) throws Exception;
    }

    private static int run(MinecraftServer server, Op op) {
        try {
            op.run(server);
            return 1;
        } catch (Throwable t) {
            LOG.error("BXS command failed", t);
            return 0;
        }
    }

    private void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private void setup(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        for (int ox : ORIGINS) {
            for (int i = 0; i < FILLS.length; i++) {
                command(server, "setblock " + (ox + 1 + i) + " 80 1 minecraft:repeating_command_block{auto:1b,Command:'fill "
                        + ox + " 0 0 " + (ox + 15) + " 40 15 " + FILLS[i] + "'}");
            }
        }
        var profile = new GameProfile(UUID.nameUUIDFromBytes("boxy-snapsmoke".getBytes()), "[bxs]");
        player = FakePlayerFactory.get(level, profile);
        player.connection = new Capture(server, player);
        Field byUuid = PlayerList.class.getDeclaredField("playersByUUID");
        byUuid.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, net.minecraft.server.level.ServerPlayer> map = (Map<UUID, net.minecraft.server.level.ServerPlayer>) byUuid.get(server.getPlayerList());
        map.put(player.getUUID(), player);
        state = ServerNetworking.getRequestService().registerPlayer(player, 1);
        LOG.info("BXS setup done player={}", player.getUUID());
    }

    private void quiesce(MinecraftServer server) {
        for (int ox : ORIGINS) {
            for (int i = 0; i < FILLS.length; i++) {
                command(server, "setblock " + (ox + 1 + i) + " 80 1 minecraft:air");
            }
        }
    }

    private void cold(MinecraftServer server, int n) {
        var reader = ServerNetworking.getRequestService().getDiskReader();
        for (int i = 0; i < n; i++) {
            reader.submitReadDirect(player.getUUID(), nextId++, server.overworld(), 300 + i, 300, nextId, true);
        }
        LOG.info("BXS cold submitted {}", n);
    }

    private void gen(MinecraftServer server, int n) {
        var service = ServerNetworking.getRequestService().getGenerationService();
        int accepted = 0;
        for (int i = 0; i < n; i++) {
            if (service.submitGeneration(player.getUUID(), nextId++, server.overworld(), 400 + i, 400, nextId)) accepted++;
        }
        LOG.info("BXS gen submitted {} accepted {}", n, accepted);
    }

    private void report(MinecraftServer server) {
        var service = ServerNetworking.getRequestService();
        LOG.info("BXS REPORT payloads={} torn={} mismatches={} directMismatches={} parseErrors={} direct={} directFailed={} directThreads={} hashes={} "
                        + "dirtyReloads={} gen=[{}] deferred={}",
                payloads.get(), torn.get(), mismatches.get(), directMismatches.get(), parseErrors.get(), direct.get(), directFailed.get(), directThreads, hashes,
                service.getDirtyReloadCompletedCount(), service.getGenerationService().getDiagnostics(), snapshots.deferred());
    }

    private void tick(ServerTickEvent.Post event) {
        if (runTicks <= 0 || state == null) return;
        runTicks--;
        ServerLevel level = event.getServer().overworld();
        for (int ox : ORIGINS) {
            int cx = ox >> 4;
            for (int i = 0; i < PER_TICK; i++) {
                state.addRequest(nextId++, PositionUtil.packPosition(cx, 0), 0L);
            }
            if (directMode) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, 0);
                if (chunk != null) {
                    snapshots.capture(event.getServer().getTickCount(), level, cx, 0,
                            () -> ColumnSnapshotter.snapshot(level, chunk, cx, 0), new LiveSnapshots.Sink<ColumnSnapshot>() {
                                @Override
                                public void accept(ColumnSnapshot snapshot) {
                                    direct.incrementAndGet();
                                    directThreads.merge(Thread.currentThread().getName().replaceAll("[0-9]+", "#"), 1L, Long::sum);
                                    byte[] bytes = ColumnSnapshotter.serialize(snapshot);
                                    if (exactMode) hashes.put(cx, java.util.Arrays.hashCode(bytes));
                                    check(level, bytes, exactMode ? chunk : null, directMismatches);
                                }

                                @Override
                                public void fail(Throwable cause) {
                                    directFailed.incrementAndGet();
                                    LOG.error("BXS direct capture failed", cause);
                                }
                            });
                }
            }
        }
        if (runTicks == 0) {
            exactMode = false;
            LOG.info("BXS run finished");
        }
    }

    private final AtomicLong directMismatches = new AtomicLong();

    private void check(ServerLevel level, byte[] bytes, LevelChunk exact) {
        check(level, bytes, exact, mismatches);
    }

    private void check(ServerLevel level, byte[] bytes, LevelChunk exact, AtomicLong mismatchCounter) {
        try {
            var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                int sectionY = buf.readByte();
                LevelChunkSection section = new LevelChunkSection(biomes);
                section.read(buf);
                if (buf.readBoolean()) buf.skipBytes(2048);
                if (buf.readBoolean()) buf.skipBytes(2048);
                if (exact != null) {
                    LevelChunkSection live = exact.getSection(exact.getSectionIndexFromSectionY(sectionY));
                    if (sectionY >= 0 && sectionY <= 5 && !sameBlocks(section, live)) {
                        if (mismatchCounter.incrementAndGet() <= 3) {
                            LOG.error("BXS mismatch vs live grid chunk {},{} section {} snapshot={} live={}", exact.getPos().x, exact.getPos().z,
                                    sectionY, section.getBlockState(0, 0, 0), live.getBlockState(0, 0, 0));
                        }
                    }
                } else if (sectionY == 0 || sectionY == 1) {
                    Map<BlockState, Integer> distinct = new java.util.LinkedHashMap<>();
                    section.getStates().count((s, n) -> { if (n > 0) distinct.merge(s, n, Integer::sum); });
                    boolean known = distinct.size() == 1 && distinct.keySet().stream().allMatch(s -> s.is(net.minecraft.world.level.block.Blocks.STONE)
                            || s.is(net.minecraft.world.level.block.Blocks.GLASS) || s.is(net.minecraft.world.level.block.Blocks.OAK_PLANKS));
                    if (!known) {
                        if (torn.incrementAndGet() <= 6) {
                            LOG.error("BXS TORN section {} distinct={} blockCount={}", sectionY, distinct, section.hasOnlyAir());
                        }
                    }
                }
            }
            if (buf.isReadable()) throw new IllegalStateException("trailing bytes " + buf.readableBytes());
        } catch (Throwable t) {
            parseErrors.incrementAndGet();
            LOG.error("BXS parse failed", t);
        }
    }

    private static boolean sameBlocks(LevelChunkSection a, LevelChunkSection b) {
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            if (a.getBlockState(x, y, z) != b.getBlockState(x, y, z)) return false;
        }
        return true;
    }

    private final class Capture extends ServerGamePacketListenerImpl {
        private static final Connection DUMMY = new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void setListenerForServerboundHandshake(PacketListener listener) {}
        };

        Capture(MinecraftServer server, FakePlayer fake) {
            super(server, DUMMY, fake, CommonListenerCookie.createInitial(fake.getGameProfile(), false));
        }

        @Override
        public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof VoxelColumnS2CPayload column) {
                payloads.incrementAndGet();
                ServerLevel level = player.serverLevel();
                LevelChunk live = exactMode ? level.getChunkSource().getChunkNow(column.chunkX(), column.chunkZ()) : null;
                if (exactMode && live == null) return;
                check(level, column.decompressedSections(), live);
            }
        }

        @Override
        public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<?> type) {
            return true;
        }

        @Override
        public boolean hasChannel(net.minecraft.resources.ResourceLocation id) {
            return true;
        }

        @Override
        public void tick() {}

        @Override
        public void resetPosition() {}
    }
}
