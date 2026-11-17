package com.example.endexpansion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
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
            int pickX = (chunkX << 4) + 8;
            int pickZ = (chunkZ << 4) + 8;
            int ground = findGround(region, pickX, pickZ);
            if (ground < info.getMinHeight() + 5) return;

            String[] types = {"ruins", "void-towers", "dungeons", "broken-bridges", "end-temples"};
            for (String type : types) {
                String path = "structures." + type;
                if (!plugin.getConfig().getBoolean(path + ".enabled", true)) continue;
                double chance = Math.max(0, Math.min(1, plugin.getConfig().getDouble(path + ".chance", 0)));
                if (random.nextDouble() < chance) {
                    int x = (chunkX << 4) + 8;
                    int z = (chunkZ << 4) + 8;
                    int y = findGround(region, x, z);
                    if (y < info.getMinHeight() + 5) return;
                    switch (type) {
                        case "ruins" -> ruins(region, random, x, y + 1, z);
                        case "void-towers" -> tower(region, random, x, y + 1, z);
                        case "dungeons" -> dungeon(region, random, x, y - 5, z);
                        case "broken-bridges" -> bridge(region, random, x, y + 1, z);
                        case "end-temples" -> temple(region, random, x, y + 1, z);
                    }
                    return; // At most one structure per chunk.
                }
            }
        }

        private int findGround(LimitedRegion r, int x, int z) {
            for (int y = r.getMaxHeight() - 3; y > r.getMinHeight() + 8; y--) {
                if (!r.isInRegion(x, y, z)) continue;
                Material m = r.getType(x, y, z);
                if (m == Material.END_STONE) return y;
            }
            return -1;
        }

        private boolean inside(LimitedRegion r, int x, int y, int z) {
            return r.isInRegion(x, y, z) && y > r.getMinHeight() && y < r.getMaxHeight() - 1;
        }
        private void put(LimitedRegion r, int x, int y, int z, Material m) {
            if (inside(r,x,y,z)) r.setType(x,y,z,m);
        }
        private void shellRoom(LimitedRegion r, int x, int y, int z, int rx, int ry, int rz,
                               Material wall, Material floor, Material roof) {
            for (int dx=-rx; dx<=rx; dx++) for (int dz=-rz; dz<=rz; dz++) for (int dy=0; dy<=ry; dy++) {
                boolean edge = Math.abs(dx)==rx || Math.abs(dz)==rz || dy==0 || dy==ry;
                if (edge) put(r,x+dx,y+dy,z+dz,dy==0?floor:(dy==ry?roof:wall));
                else put(r,x+dx,y+dy,z+dz,Material.AIR);
            }
        }
        private void ruins(LimitedRegion r, Random rand, int x, int y, int z) {
            Material[] blocks={Material.END_STONE_BRICKS,Material.CRACKED_STONE_BRICKS,Material.PURPUR_BLOCK};
            for(int dx=-5;dx<=5;dx++) for(int dz=-5;dz<=5;dz++) {
                if(Math.abs(dx)==5||Math.abs(dz)==5) {
                    int h=2+rand.nextInt(4);
                    for(int dy=0;dy<h;dy++) put(r,x+dx,y+dy,z+dz,blocks[rand.nextInt(blocks.length)]);
                } else if(rand.nextDouble()<0.12) put(r,x+dx,y,z+dz,Material.MOSSY_STONE_BRICKS);
            }
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) put(r,x+dx,y,z+dz,Material.PURPUR_BLOCK);
            chest(r,x,y+1,z,rand,"ruins");
        }
        private void tower(LimitedRegion r, Random rand, int x, int y, int z) {
            for(int dy=0;dy<13;dy++) {
                int radius=dy<9?3:2;
                for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                    if(Math.abs(dx)==radius||Math.abs(dz)==radius) put(r,x+dx,y+dy,z+dz,Material.PURPUR_PILLAR);
                    else if(dy%4==0) put(r,x+dx,y+dy,z+dz,Material.PURPUR_SLAB);
                    else put(r,x+dx,y+dy,z+dz,Material.AIR);
                }
                if(dy%4==0) for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++)
                    if(Math.abs(dx)==radius||Math.abs(dz)==radius) put(r,x+dx,y+dy,z+dz,Material.PURPUR_BLOCK);
            }
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) put(r,x+dx,y+13,z+dz,Material.END_ROD);
            chest(r,x,y+10,z,rand,"void-towers");
        }
        private void dungeon(LimitedRegion r, Random rand, int x, int y, int z) {
            shellRoom(r,x,y,z,4,4,4,Material.END_STONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS);
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                if(Math.abs(dx)==2||Math.abs(dz)==2) put(r,x+dx,y+1,z+dz,Material.PURPUR_PILLAR);
            }
            put(r,x,y+1,z,Material.SPAWNER);
            chest(r,x+2,y+1,z+2,rand,"dungeons");
        }
        private void bridge(LimitedRegion r, Random rand, int x, int y, int z) {
            for(int dx=-6;dx<=6;dx++) {
                for(int dz=-2;dz<=2;dz++) {
                    if(Math.abs(dz)<=1) put(r,x+dx,y,z+dz,Material.END_STONE_BRICKS);
                    if(Math.abs(dz)==2 && (dx%3!=0)) put(r,x+dx,y+1,z+dz,Material.END_STONE_BRICK_WALL);
                }
                if(dx%3==0) {
                    put(r,x+dx,y+1,z-2,Material.PURPUR_PILLAR);
                    put(r,x+dx,y+1,z+2,Material.PURPUR_PILLAR);
                }
            }
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) put(r,x+dx,y+1,z+dz,Material.CHEST);
            chest(r,x,y+1,z,rand,"broken-bridges");
        }
        private void temple(LimitedRegion r, Random rand, int x, int y, int z) {
            shellRoom(r,x,y,z,5,6,5,Material.END_STONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS,Material.PURPUR_BLOCK);
            for(int dx=-3;dx<=3;dx+=6) for(int dz=-3;dz<=3;dz+=6) {
                for(int dy=1;dy<=8;dy++) put(r,x+dx,y+dy,z+dz,Material.PURPUR_PILLAR);
            }
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++)
                if(Math.abs(dx)==2||Math.abs(dz)==2) put(r,x+dx,y+1,z+dz,Material.END_ROD);
            chest(r,x,y+1,z,rand,"end-temples");
        }
        private void chest(LimitedRegion r, int x, int y, int z, Random random, String kind) {
            if (!inside(r,x,y,z)) return;
            put(r,x,y,z,Material.CHEST);
            BlockState state = r.getBlockState(x,y,z);
            if (state instanceof Chest chest) {
                String key = plugin.getConfig().getString("loot."+kind, "minecraft:chests/end_city_treasure");
                LootTable table = Bukkit.getLootTable(NamespacedKey.fromString(key));
                if (table != null) {
                    chest.setLootTable(table);
                    chest.setSeed(random.nextLong());
                    chest.update(true, false);
                }
            }
        }
    }
}
