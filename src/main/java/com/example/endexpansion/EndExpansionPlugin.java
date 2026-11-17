package com.example.endexpansion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

public final class EndExpansionPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("EndExpansion enabled. Structures generate in new End chunks.");
        for (World world : Bukkit.getWorlds()) addPopulator(world);
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onWorldInit(org.bukkit.event.world.WorldInitEvent event) {
                addPopulator(event.getWorld());
            }
        }, this);
    }

    private void addPopulator(World world) {
        if (world.getEnvironment() != World.Environment.THE_END) return;
        if (world.getPopulators().stream().noneMatch(p -> p instanceof EndStructuresPopulator)) {
            world.getPopulators().add(new EndStructuresPopulator(this));
        }
    }

    private static final class EndStructuresPopulator extends BlockPopulator {
        private final EndExpansionPlugin plugin;
        private EndStructuresPopulator(EndExpansionPlugin plugin) { this.plugin = plugin; }

        @Override
        public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            if (!plugin.getConfig().getBoolean("structures.enabled", true)) return;
            int x = (chunkX << 4) + 8;
            int z = (chunkZ << 4) + 8;
            int ground = findGround(region, info, x, z);
            if (ground < info.getMinHeight() + 8) return;

            // Rare mega-ruins are checked first, but are intentionally uncommon.
            if (enabled("mega-ruins") && random.nextDouble() < chance("mega-ruins", 0.002)) {
                megaRuin(region, random, x, ground + 1, z);
                return;
            }

            String[] types = {"ruins", "void-towers", "dungeons", "broken-bridges", "end-temples"};
            for (String type : types) {
                if (!enabled(type) || random.nextDouble() >= chance(type, 0)) continue;
                switch (type) {
                    case "ruins" -> ruins(region, random, x, ground + 1, z);
                    case "void-towers" -> tower(region, random, x, ground + 1, z);
                    case "dungeons" -> dungeon(region, random, x, ground - 5, z);
                    case "broken-bridges" -> bridge(region, random, x, ground + 1, z);
                    case "end-temples" -> temple(region, random, x, ground + 1, z);
                }
                return; // At most one structure per chunk.
            }
        }

        private boolean enabled(String type) {
            return plugin.getConfig().getBoolean("structures." + type + ".enabled", true);
        }
        private double chance(String type, double fallback) {
            return Math.max(0, Math.min(1, plugin.getConfig().getDouble("structures." + type + ".chance", fallback)));
        }
        private int findGround(LimitedRegion r, WorldInfo info, int x, int z) {
            for (int y = info.getMaxHeight() - 3; y > info.getMinHeight() + 8; y--) {
                if (r.isInRegion(x, y, z) && r.getType(x, y, z) == Material.END_STONE) return y;
            }
            return -1;
        }
        private boolean inside(LimitedRegion r, int x, int y, int z) {
            return r.isInRegion(x, y, z);
        }
        private void put(LimitedRegion r, int x, int y, int z, Material m) {
            if (inside(r, x, y, z)) r.setType(x, y, z, m);
        }
        private void room(LimitedRegion r, int x, int y, int z, int rx, int ry, int rz,
                          Material wall, Material floor, Material roof) {
            for (int dx=-rx; dx<=rx; dx++) for (int dz=-rz; dz<=rz; dz++) for (int dy=0; dy<=ry; dy++) {
                boolean edge = Math.abs(dx)==rx || Math.abs(dz)==rz || dy==0 || dy==ry;
                put(r, x+dx, y+dy, z+dz, edge ? (dy==0 ? floor : dy==ry ? roof : wall) : Material.AIR);
            }
        }
        private void chest(LimitedRegion r, int x, int y, int z, Random random, String kind) {
            if (!inside(r, x, y, z)) return;
            put(r, x, y, z, Material.CHEST);
            BlockState state = r.getBlockState(x, y, z);
            if (state instanceof Chest c) {
                String key = plugin.getConfig().getString("loot." + kind, "minecraft:chests/end_city_treasure");
                LootTable table = Bukkit.getLootTable(NamespacedKey.fromString(key));
                if (table != null) {
                    c.setLootTable(table);
                    c.setSeed(random.nextLong());
                    c.update(true, false);
                }
            }
        }
        private void spawner(LimitedRegion r, int x, int y, int z, EntityType type) {
            put(r, x, y, z, Material.SPAWNER);
            BlockState state = r.getBlockState(x, y, z);
            if (state instanceof CreatureSpawner s) {
                s.setSpawnedType(type);
                s.setDelay(180);
                s.setMinSpawnDelay(220);
                s.setMaxSpawnDelay(420);
                s.setSpawnCount(2);
                s.setMaxNearbyEntities(5);
                s.setRequiredPlayerRange(12);
                s.update(true, false);
            }
        }
        private void ruins(LimitedRegion r, Random rand, int x, int y, int z) {
            Material[] blocks={Material.END_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.PURPUR_BLOCK};
            for (int dx=-5; dx<=5; dx++) for (int dz=-5; dz<=5; dz++) {
                if (Math.abs(dx)==5 || Math.abs(dz)==5) {
                    int h=2+rand.nextInt(4);
                    for (int dy=0; dy<h; dy++) put(r,x+dx,y+dy,z+dz,blocks[rand.nextInt(blocks.length)]);
                } else if (rand.nextDouble()<0.12) put(r,x+dx,y,z+dz,Material.MOSSY_STONE_BRICKS);
            }
            // Two possible hiding spots: one is selected per ruin.
            if (rand.nextBoolean()) {
                put(r,x+3,y,z+2,Material.END_STONE_BRICKS);
                chest(r,x+3,y+1,z+2,rand,"ruins");
            } else chest(r,x,y+1,z,rand,"ruins");
        }
        private void tower(LimitedRegion r, Random rand, int x, int y, int z) {
            for (int dy=0; dy<13; dy++) {
                int radius=dy<9?3:2;
                for (int dx=-radius; dx<=radius; dx++) for (int dz=-radius; dz<=radius; dz++) {
                    if (Math.abs(dx)==radius || Math.abs(dz)==radius) put(r,x+dx,y+dy,z+dz,Material.PURPUR_PILLAR);
                    else put(r,x+dx,y+dy,z+dz,dy%4==0?Material.PURPUR_SLAB:Material.AIR);
                }
            }
            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) put(r,x+dx,y+13,z+dz,Material.END_ROD);
            spawner(r,x,y+2,z,rand.nextBoolean()?EntityType.ENDERMITE:EntityType.ENDERMAN);
            chest(r,x,y+10,z,rand,"void-towers");
        }
        private void dungeon(LimitedRegion r, Random rand, int x, int y, int z) {
            room(r,x,y,z,4,4,4,Material.END_STONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS);
            for (int dx=-2; dx<=2; dx++) for (int dz=-2; dz<=2; dz++) {
                if (Math.abs(dx)==2 || Math.abs(dz)==2) put(r,x+dx,y+1,z+dz,Material.PURPUR_PILLAR);
            }
            spawner(r,x-2,y+1,z-2,EntityType.ENDERMITE);
            if (rand.nextBoolean()) spawner(r,x+2,y+1,z+2,EntityType.ENDERMAN);
            // The reward is deliberately off-centre so the room must be searched.
            chest(r,x+3,y+1,z-3,rand,"dungeons");
            put(r,x,y+1,z,Material.CRYING_OBSIDIAN);
        }
        private void bridge(LimitedRegion r, Random rand, int x, int y, int z) {
            for (int dx=-6; dx<=6; dx++) {
                for (int dz=-2; dz<=2; dz++) {
                    if (Math.abs(dz)<=1) put(r,x+dx,y,z+dz,Material.END_STONE_BRICKS);
                    if (Math.abs(dz)==2 && dx%3!=0) put(r,x+dx,y+1,z+dz,Material.END_STONE_BRICK_WALL);
                }
                if (dx%3==0) {
                    put(r,x+dx,y+1,z-2,Material.PURPUR_PILLAR);
                    put(r,x+dx,y+1,z+2,Material.PURPUR_PILLAR);
                }
            }
            spawner(r,x-4,y+1,z,EntityType.ENDERMITE);
            chest(r,x+5,y+1,z,rand,"broken-bridges");
        }
        private void temple(LimitedRegion r, Random rand, int x, int y, int z) {
            room(r,x,y,z,5,6,5,Material.END_STONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS,Material.PURPUR_BLOCK);
            for (int dx=-3; dx<=3; dx+=6) for (int dz=-3; dz<=3; dz+=6)
                for (int dy=1; dy<=8; dy++) put(r,x+dx,y+dy,z+dz,Material.PURPUR_PILLAR);
            // A simple search puzzle: find the concealed chest behind one of four pillars.
            int side=rand.nextInt(4);
            int[][] spots={{0,1,-4},{4,1,0},{0,1,4},{-4,1,0}};
            int[] s=spots[side];
            put(r,x+s[0],y+s[1],z+s[2],Material.PURPUR_BLOCK);
            chest(r,x+s[0],y+s[1]+1,z+s[2],rand,"end-temples");
            spawner(r,x,y+1,z,EntityType.ENDERMAN);
        }
        private void megaRuin(LimitedRegion r, Random rand, int x, int y, int z) {
            // Rare, moderately large landmark: a courtyard with two wings, not a giant maze.
            room(r,x,y,z,8,6,8,Material.END_STONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS,Material.PURPUR_BLOCK);
            room(r,x-5,y,z,3,4,3,Material.PURPUR_PILLAR,Material.END_STONE_BRICKS,Material.PURPUR_BLOCK);
            room(r,x+5,y,z,3,4,3,Material.PURPUR_PILLAR,Material.END_STONE_BRICKS,Material.PURPUR_BLOCK);
            for (int dx=-8; dx<=8; dx++) {
                put(r,x+dx,y+7,z-8,Material.PURPUR_PILLAR);
                put(r,x+dx,y+7,z+8,Material.PURPUR_PILLAR);
            }
            spawner(r,x-5,y+1,z,EntityType.ENDERMAN);
            spawner(r,x+5,y+1,z,EntityType.ENDERMITE);
            chest(r,x+7,y+1,z+7,rand,"mega-ruins");
            chest(r,x-7,y+1,z-7,rand,"mega-ruins");
        }
    }
}
