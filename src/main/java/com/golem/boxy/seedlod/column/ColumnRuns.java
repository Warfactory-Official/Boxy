package com.golem.boxy.seedlod.column;

import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block column as contiguous vertical runs, bottom first. Run {@code i} covers {@code [bottom(i), top(i))}
 * where {@code bottom(0)} is the column floor and {@code bottom(i) = top(i - 1)}. Everything at or above the
 * last run's top is open air. Light is Voxy's packed byte: sky light in the low nibble, block light in the high.
 */
public final class ColumnRuns {
   public static final int OPEN_SKY = 0x0F;
   public static final int UNLIT = 0;
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private final int floor;
   private int count;
   private int[] tops = new int[8];
   private BlockState[] states = new BlockState[8];
   private Holder<Biome>[] biomes = newBiomeArray(8);
   private byte[] lights = new byte[8];

   public ColumnRuns(int floor) {
      this.floor = floor;
   }

   @SuppressWarnings("unchecked")
   private static Holder<Biome>[] newBiomeArray(int size) {
      return (Holder<Biome>[])new Holder[size];
   }

   public void clear() {
      count = 0;
   }

   public int count() {
      return count;
   }

   public int floor() {
      return floor;
   }

   public int bottom(int run) {
      return run == 0 ? floor : tops[run - 1];
   }

   public int top(int run) {
      return tops[run];
   }

   public BlockState state(int run) {
      return states[run];
   }

   public Holder<Biome> biome(int run) {
      return biomes[run];
   }

   public int light(int run) {
      return lights[run] & 0xFF;
   }

   /** One above the highest non-air block, or the floor for an empty column. */
   public int height() {
      for (int run = count - 1; run >= 0; run--) {
         if (!states[run].isAir()) {
            return tops[run];
         }
      }
      return floor;
   }

   /** Extends the column upward to {@code top}; merges with the previous run when nothing differs. */
   public void append(int top, BlockState state, Holder<Biome> biome, int light) {
      int bottom = count == 0 ? floor : tops[count - 1];
      if (top <= bottom) {
         return;
      }
      if (count > 0 && states[count - 1] == state && biomes[count - 1] == biome && lights[count - 1] == (byte)light) {
         tops[count - 1] = top;
         return;
      }
      grow(count + 1);
      tops[count] = top;
      states[count] = state;
      biomes[count] = biome;
      lights[count] = (byte)light;
      count++;
   }

   public BlockState stateAt(int y) {
      if (y < floor) {
         return AIR;
      }
      for (int run = 0; run < count; run++) {
         if (y < tops[run]) {
            return states[run];
         }
      }
      return AIR;
   }

   /** Overwrites one block, splitting the run it falls in. Writes above the column extend it with air. */
   public void set(int y, BlockState state, Holder<Biome> biome, int light) {
      if (y < floor) {
         return;
      }
      int end = count == 0 ? floor : tops[count - 1];
      if (y >= end) {
         append(y, AIR, biome, OPEN_SKY);
         append(y + 1, state, biome, light);
         return;
      }
      int run = 0;
      while (tops[run] <= y) {
         run++;
      }
      if (states[run] == state) {
         return;
      }
      int bottom = bottom(run);
      int top = tops[run];
      BlockState oldState = states[run];
      Holder<Biome> oldBiome = biomes[run];
      byte oldLight = lights[run];
      // Replace run with up to three: [bottom, y) old, [y, y+1) new, [y+1, top) old.
      int pieces = (y > bottom ? 1 : 0) + 1 + (y + 1 < top ? 1 : 0);
      grow(count + pieces - 1);
      int tail = count - run - 1;
      System.arraycopy(tops, run + 1, tops, run + pieces, tail);
      System.arraycopy(states, run + 1, states, run + pieces, tail);
      System.arraycopy(biomes, run + 1, biomes, run + pieces, tail);
      System.arraycopy(lights, run + 1, lights, run + pieces, tail);
      int at = run;
      if (y > bottom) {
         tops[at] = y;
         states[at] = oldState;
         biomes[at] = oldBiome;
         lights[at] = oldLight;
         at++;
      }
      tops[at] = y + 1;
      states[at] = state;
      biomes[at] = biome;
      lights[at] = (byte)light;
      at++;
      if (y + 1 < top) {
         tops[at] = top;
         states[at] = oldState;
         biomes[at] = oldBiome;
         lights[at] = oldLight;
      }
      count += pieces - 1;
   }

   public ColumnRuns copy() {
      ColumnRuns copy = new ColumnRuns(floor);
      copy.grow(count);
      System.arraycopy(tops, 0, copy.tops, 0, count);
      System.arraycopy(states, 0, copy.states, 0, count);
      System.arraycopy(biomes, 0, copy.biomes, 0, count);
      System.arraycopy(lights, 0, copy.lights, 0, count);
      copy.count = count;
      return copy;
   }

   private void grow(int needed) {
      if (needed > tops.length) {
         int size = Math.max(needed, tops.length * 2);
         tops = Arrays.copyOf(tops, size);
         states = Arrays.copyOf(states, size);
         biomes = Arrays.copyOf(biomes, size);
         lights = Arrays.copyOf(lights, size);
      }
   }
}
