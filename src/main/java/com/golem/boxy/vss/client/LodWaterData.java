package com.golem.boxy.vss.client;

import java.util.ArrayList;
import java.util.List;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Reads Voxy's stored voxels to explain dark LOD water. Voxy meshes a fluid face with the light of the voxel in front
 * of it (RenderDataFactory: {@code lighter} inside a section, {@code neighboringFaces} across an edge). Across an edge
 * that voxel comes from the neighbouring section, which Voxy reads with {@code acquire()}: a section that was never
 * stored comes back as zeros, i.e. air with light 0. Both views below report which case each LOD level is in.
 */
public final class LodWaterData {
    static final int LEVELS = 5;
    /** Sky light below this on a water surface's lighting voxel is called dark. */
    static final int DARK_SKY = 8;

    private LodWaterData() {
    }

    /** One line per LOD level: the first voxel the view ray hits at that level and the light its facing side gets. */
    static List<String> probe(WorldEngine engine, Vec3 eye, Vec3 dir, int maxBlocks) {
        List<String> lines = new ArrayList<>();
        for (int lvl = 0; lvl < LEVELS; lvl++) {
            lines.add(probeLevel(engine, lvl, eye, dir, maxBlocks));
        }
        return lines;
    }

    private static String probeLevel(WorldEngine engine, int lvl, Vec3 eye, Vec3 dir, int maxBlocks) {
        double scale = 1.0 / (1 << lvl);
        double px = eye.x * scale, py = eye.y * scale, pz = eye.z * scale;
        int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double dx = dir.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.x);
        double dy = dir.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.y);
        double dz = dir.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.z);
        double tx = dir.x == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? x + 1 - px : px - x) * dx;
        double ty = dir.y == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? y + 1 - py : py - y) * dy;
        double tz = dir.z == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? z + 1 - pz : pz - z) * dz;
        double maxT = maxBlocks * scale;
        int prevX = x, prevY = y, prevZ = z;
        long prevId = 0;
        try (Sections sections = new Sections(engine, lvl)) {
            for (double t = 0; t <= maxT; ) {
                long id = sections.voxel(x, y, z);
                if (!Mapper.isAir(id)) {
                    return describe(engine, lvl, x, y, z, id, prevX, prevY, prevZ, prevId);
                }
                prevX = x;
                prevY = y;
                prevZ = z;
                prevId = id;
                if (tx <= ty && tx <= tz) {
                    t = tx;
                    tx += dx;
                    x += sx;
                } else if (ty <= tz) {
                    t = ty;
                    ty += dy;
                    y += sy;
                } else {
                    t = tz;
                    tz += dz;
                    z += sz;
                }
            }
        }
        return "L" + lvl + ": nothing stored within " + maxBlocks + " blocks";
    }

    private static String describe(WorldEngine engine, int lvl, int x, int y, int z, long id,
                                   int prevX, int prevY, int prevZ, long prevId) {
        BlockState state = state(engine, id);
        boolean water = state.getFluidState().is(FluidTags.WATER);
        int faceLight = Mapper.getLightId(prevId);
        boolean crossSection = (x >> 5) != (prevX >> 5) || (y >> 5) != (prevY >> 5) || (z >> 5) != (prevZ >> 5);
        String where = "same section";
        if (crossSection) {
            WorldSection neighbour = engine.acquireIfExists(lvl, prevX >> 5, prevY >> 5, prevZ >> 5);
            if (neighbour == null) {
                where = "neighbour section NOT STORED";
            } else {
                neighbour.release();
                where = "neighbour section stored";
            }
        }
        boolean dark = (faceLight & 0xF) < DARK_SKY;
        return String.format("L%d%s %s @%d,%d,%d self sky%d blk%d | lit from %s: sky%d blk%d, %s",
                lvl, water && dark ? " DARK" : "", BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(),
                x << lvl, y << lvl, z << lvl, Mapper.getLightId(id) & 0xF, Mapper.getLightId(id) >> 4,
                side(prevX - x, prevY - y, prevZ - z), faceLight & 0xF, faceLight >> 4, where);
    }

    private static String side(int dx, int dy, int dz) {
        return dx != 0 ? (dx > 0 ? "+X" : "-X") : dy != 0 ? (dy > 0 ? "+Y" : "-Y") : (dz > 0 ? "+Z" : "-Z");
    }

    /** Water surfaces counted by {@link #audit} at one LOD level, and how many are lit dark, by cause. */
    public record AuditRow(int level, long tops, long edgeTops, long darkInside, long darkMissing, long darkStored) {
        public long dark() {
            return darkInside + darkMissing + darkStored;
        }

        @Override
        public String toString() {
            return String.format("L%d (%d-block voxels): %d water tops, %d dark (%.1f%%) | at a section top: %d of %d"
                            + " (above NOT STORED %d, stored dark %d) | inside a section: %d",
                    level, 1 << level, tops, dark(), tops == 0 ? 0.0 : 100.0 * dark() / tops,
                    darkMissing + darkStored, edgeTops, darkMissing, darkStored, darkInside);
        }
    }

    /**
     * Per level, every water voxel with air directly above it (the surface Voxy draws from above) whose block-space
     * box overlaps the square around ({@code blockX}, {@code blockZ}) and {@code [minY, maxY)}, and how many of those
     * get dark light, split by why: lit from inside the section, or from the section above that is missing or stored
     * dark. Runs off the render thread; it loads every stored section in range.
     */
    public static List<AuditRow> audit(WorldEngine engine, int blockX, int blockZ, int radius, int minY, int maxY) {
        List<AuditRow> rows = new ArrayList<>();
        Mapper mapper = engine.getMapper();
        byte[] isWater = new byte[1 << 20]; // by Voxy block id: 0 unknown, 1 water, 2 not
        for (int lvl = 0; lvl < LEVELS; lvl++) {
            // voxel bounds of the requested box at this level, inclusive
            int vx0 = (blockX - radius) >> lvl, vx1 = (blockX + radius) >> lvl;
            int vz0 = (blockZ - radius) >> lvl, vz1 = (blockZ + radius) >> lvl;
            int vy0 = minY >> lvl, vy1 = (maxY - 1) >> lvl;
            long tops = 0, edgeTops = 0, darkInside = 0, darkMissing = 0, darkStored = 0;
            for (int sx = vx0 >> 5; sx <= vx1 >> 5; sx++) {
                for (int sz = vz0 >> 5; sz <= vz1 >> 5; sz++) {
                    for (int sy = vy0 >> 5; sy <= vy1 >> 5; sy++) {
                        WorldSection section = engine.acquireIfExists(lvl, sx, sy, sz);
                        if (section == null) {
                            continue;
                        }
                        WorldSection above = null;
                        boolean aboveLoaded = false;
                        try {
                            long[] raw = section._unsafeGetRawDataArray();
                            for (int y = Math.max(0, vy0 - (sy << 5)); y <= Math.min(31, vy1 - (sy << 5)); y++) {
                                for (int z = Math.max(0, vz0 - (sz << 5)); z <= Math.min(31, vz1 - (sz << 5)); z++) {
                                    for (int x = Math.max(0, vx0 - (sx << 5)); x <= Math.min(31, vx1 - (sx << 5)); x++) {
                                        int i = WorldSection.getIndex(x, y, z);
                                        long id = raw[i];
                                        if (Mapper.isAir(id) || !water(mapper, id, isWater)) {
                                            continue;
                                        }
                                        boolean edge = y == 31;
                                        long up;
                                        if (!edge) {
                                            up = raw[WorldSection.getIndex(x, y + 1, z)];
                                        } else {
                                            if (!aboveLoaded) {
                                                above = engine.acquireIfExists(lvl, sx, sy + 1, sz);
                                                aboveLoaded = true;
                                            }
                                            up = above == null ? 0 : above._unsafeGetRawDataArray()[WorldSection.getIndex(x, 0, z)];
                                        }
                                        if (!Mapper.isAir(up)) {
                                            continue;
                                        }
                                        tops++;
                                        if (edge) {
                                            edgeTops++;
                                        }
                                        if ((Mapper.getLightId(up) & 0xF) >= DARK_SKY) {
                                            continue;
                                        }
                                        if (!edge) {
                                            darkInside++;
                                        } else if (above == null) {
                                            darkMissing++;
                                        } else {
                                            darkStored++;
                                        }
                                    }
                                }
                            }
                        } finally {
                            section.release();
                            if (above != null) {
                                above.release();
                            }
                        }
                    }
                }
            }
            rows.add(new AuditRow(lvl, tops, edgeTops, darkInside, darkMissing, darkStored));
        }
        return rows;
    }

    private static boolean water(Mapper mapper, long id, byte[] cache) {
        int block = Mapper.getBlockId(id);
        if (block >= cache.length) {
            return isWater(mapper, block);
        }
        if (cache[block] == 0) {
            cache[block] = (byte) (isWater(mapper, block) ? 1 : 2);
        }
        return cache[block] == 1;
    }

    private static boolean isWater(Mapper mapper, int block) {
        return mapper.getBlockStateFromBlockId(block).getFluidState().is(FluidTags.WATER);
    }

    private static BlockState state(WorldEngine engine, long id) {
        return engine.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(id));
    }

    /** The section a ray is in, kept acquired until the ray leaves it. Missing sections read as air. */
    private static final class Sections implements AutoCloseable {
        private final WorldEngine engine;
        private final int lvl;
        private WorldSection current;
        private int cx, cy, cz;
        private boolean loaded;

        Sections(WorldEngine engine, int lvl) {
            this.engine = engine;
            this.lvl = lvl;
        }

        long voxel(int x, int y, int z) {
            if (!loaded || x >> 5 != cx || y >> 5 != cy || z >> 5 != cz) {
                close();
                cx = x >> 5;
                cy = y >> 5;
                cz = z >> 5;
                current = engine.acquireIfExists(lvl, cx, cy, cz);
                loaded = true;
            }
            return current == null ? 0 : current._unsafeGetRawDataArray()[WorldSection.getIndex(x & 31, y & 31, z & 31)];
        }

        @Override
        public void close() {
            if (current != null) {
                current.release();
                current = null;
            }
            loaded = false;
        }
    }
}
