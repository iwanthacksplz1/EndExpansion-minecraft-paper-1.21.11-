package com.example.endexpansion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EndExpansionPlugin extends JavaPlugin implements CommandExecutor {
    private final Map<String, Set<String>> generatedStructures = new ConcurrentHashMap<>();
    private File locationsFile;
    private YamlConfiguration locationsData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        locationsFile = new File(getDataFolder(), "generated-structures.yml");
        locationsData = YamlConfiguration.loadConfiguration(locationsFile);
        loadGeneratedStructures();
        if (getCommand("endexpansion") != null) {
            getCommand("endexpansion").setExecutor(this);
        } else {
            getLogger().severe("Command 'endexpansion' is missing from plugin.yml!");
        }
        getLogger().info("EndExpansion enabled. Structures generate in new End chunks.");
        for (World world : Bukkit.getWorlds()) addPopulator(world);
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onWorldInit(org.bukkit.event.world.WorldInitEvent event) {
                addPopulator(event.getWorld());
            }
        }, this);
    }

    @Override
    public void onDisable() {
        saveGeneratedStructures();
    }

    private void loadGeneratedStructures() {
        for (String type : locationsData.getKeys(false)) {
            Set<String> entries = ConcurrentHashMap.newKeySet();
            entries.addAll(locationsData.getStringList(type));
            generatedStructures.put(type, entries);
        }
    }

    private synchronized void saveGeneratedStructures() {
        if (locationsData == null || locationsFile == null) return;
        for (String type : generatedStructures.keySet()) {
            locationsData.set(type, generatedStructures.get(type).stream().sorted().toList());
        }
        try {
            locationsData.save(locationsFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save generated structure locations: " + ex.getMessage());
        }
    }

    private void recordStructure(String type, String world, int x, int y, int z) {
        generatedStructures.computeIfAbsent(type, ignored -> ConcurrentHashMap.newKeySet())
                .add(world + "|" + x + "|" + y + "|" + z);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("You must be an operator to use this command.");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("find")) {
            sender.sendMessage("Usage: /endexpansion find <structure|all>");
            sender.sendMessage("Structures: end-villages, ruins, void-towers, dungeons, broken-bridges, end-temples, mega-ruins");
            return true;
        }

        String requested = args[1].toLowerCase(java.util.Locale.ROOT);
        if (requested.equals("all")) {
            int total = 0;
            for (String type : generatedStructures.keySet()) {
                Set<String> entries = generatedStructures.get(type);
                if (entries == null || entries.isEmpty()) continue;
                sender.sendMessage(type + " (" + entries.size() + "):");
                for (String entry : entries) {
                    String[] parts = entry.split("\\\\|", 4);
                    if (parts.length == 4) sender.sendMessage("  " + parts[0] + " X:" + parts[1] + " Y:" + parts[2] + " Z:" + parts[3]);
                }
                total += entries.size();
            }
            if (total == 0) sender.sendMessage("No generated EndExpansion structures have been recorded yet.");
            else sender.sendMessage("Total recorded structures: " + total);
            return true;
        }

        Set<String> entries = generatedStructures.get(requested);
        if (entries == null || entries.isEmpty()) {
            sender.sendMessage("No recorded '" + requested + "' structures yet. Explore new End chunks first.");
            return true;
        }

        String best = null;
        double bestDistance = Double.MAX_VALUE;
        Player player = sender instanceof Player p ? p : null;
        for (String entry : entries) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4) continue;
            if (player == null) {
                best = entry;
                break;
            }
            if (!parts[0].equals(player.getWorld().getName())) continue;
            try {
                double dx = Integer.parseInt(parts[1]) - player.getLocation().getX();
                double dz = Integer.parseInt(parts[3]) - player.getLocation().getZ();
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entry;
                }
            } catch (NumberFormatException ignored) { }
        }
        if (best == null) {
            sender.sendMessage("No '" + requested + "' structures recorded in your current world.");
            return true;
        }
        String[] parts = best.split("\\|", 4);
        sender.sendMessage("Nearest " + requested + ": " + parts[0] + " X: " + parts[1] + " Y: " + parts[2] + " Z: " + parts[3]);
        return true;
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

            // Keep generated structures off the central End island.
            // The outer End islands begin roughly 1,000 blocks from the origin.
            long distanceSquared = (long) x * x + (long) z * z;
            if (distanceSquared < 1_000_000L) return;

            int ground = findGround(region, info, x, z);
            if (ground < info.getMinHeight() + 8) return;

            // Rare mega-ruins are checked first, but are intentionally uncommon.
            if (enabled("mega-ruins") && random.nextDouble() < chance("mega-ruins", 0.002)) {
                megaRuin(region, random, x, ground + 1, z);
                plugin.recordStructure("mega-ruins", info.getName(), x, ground + 1, z);
                return;
            }

            String[] types = {"end-villages", "ruins", "void-towers", "dungeons", "broken-bridges", "end-temples"};
            for (String type : types) {
                if (!enabled(type) || random.nextDouble() >= chance(type, 0)) continue;
                switch (type) {
                    case "end-villages" -> village(region, random, x, ground + 1, z);
                    case "ruins" -> ruins(region, random, x, ground + 1, z);
                    case "void-towers" -> tower(region, random, x, ground + 1, z);
                    case "dungeons" -> dungeon(region, random, x, ground - 5, z);
                    case "broken-bridges" -> bridge(region, random, x, ground + 1, z);
                    case "end-temples" -> temple(region, random, x, ground + 1, z);
                }
                plugin.recordStructure(type, info.getName(), x, ground + 1, z);
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
        private EntityType randomMob(Random rand) {
            return switch (rand.nextInt(3)) {
                case 0 -> EntityType.ZOMBIE;
                case 1 -> EntityType.SKELETON;
                default -> EntityType.SPIDER;
            };
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
            spawner(r,x,y+2,z,randomMob(rand));
            chest(r,x,y+10,z,rand,"void-towers");
        }
        private void dungeon(LimitedRegion r, Random rand, int x, int y, int z) {
            room(r,x,y,z,4,4,4,Material.END_STONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS,Material.POLISHED_BLACKSTONE_BRICKS);
            for (int dx=-2; dx<=2; dx++) for (int dz=-2; dz<=2; dz++) {
                if (Math.abs(dx)==2 || Math.abs(dz)==2) put(r,x+dx,y+1,z+dz,Material.PURPUR_PILLAR);
            }
            spawner(r,x-2,y+1,z-2,randomMob(rand));
            if (rand.nextBoolean()) spawner(r,x+2,y+1,z+2,randomMob(rand));
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
            spawner(r,x-4,y+1,z,randomMob(rand));
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
            spawner(r,x,y+1,z,randomMob(rand));
        }
        private void village(LimitedRegion r, Random rand, int x, int y, int z) {
            // A small abandoned End settlement: three compact buildings around a plaza.
            // It is deliberately much smaller than a vanilla village.
            Material[] palettes = {Material.END_STONE_BRICKS, Material.PURPUR_BLOCK, Material.POLISHED_BLACKSTONE_BRICKS};
            Material wall = palettes[rand.nextInt(palettes.length)];

            // Central plaza and a broken bell marker.
            for (int dx=-4; dx<=4; dx++) for (int dz=-4; dz<=4; dz++) {
                put(r, x+dx, y, z+dz, (Math.abs(dx)+Math.abs(dz))%3==0
                        ? Material.PURPUR_BLOCK : Material.END_STONE_BRICKS);
            }
            put(r, x, y+1, z, Material.BELL);
            put(r, x, y+2, z, Material.PURPUR_PILLAR);

            // Three small houses with door gaps and roof details.
            house(r, x-8, y+1, z-5, wall, rand, "end-villages");
            house(r, x+8, y+1, z-5, wall, rand, "end-villages");
            house(r, x, y+1, z+9, wall, rand, "end-villages");

            // A modest guard post makes the settlement an encounter, not a free loot stop.
            for (int dy=0; dy<5; dy++) {
                put(r, x-1, y+dy, z-12, Material.PURPUR_PILLAR);
                put(r, x+1, y+dy, z-12, Material.PURPUR_PILLAR);
            }
            put(r, x, y+4, z-12, Material.END_ROD);
            spawner(r, x, y+1, z-12, randomMob(rand));
        }

        private void house(LimitedRegion r, int x, int y, int z, Material wall, Random rand, String lootKind) {
            // 5x5 house, 4 blocks tall, with a front doorway and a small hidden cache.
            for (int dx=-2; dx<=2; dx++) for (int dz=-2; dz<=2; dz++) {
                put(r, x+dx, y, z+dz, Material.POLISHED_BLACKSTONE_BRICKS);
                put(r, x+dx, y+4, z+dz, Material.PURPUR_BLOCK);
            }
            for (int dy=1; dy<=3; dy++) {
                for (int dx=-2; dx<=2; dx++) {
                    put(r, x+dx, y+dy, z-2, wall);
                    put(r, x+dx, y+dy, z+2, wall);
                }
                for (int dz=-1; dz<=1; dz++) {
                    put(r, x-2, y+dy, z+dz, wall);
                    put(r, x+2, y+dy, z+dz, wall);
                }
            }
            // Doorway and windows.
            put(r, x, y+1, z-2, Material.AIR);
            put(r, x, y+2, z-2, Material.AIR);
            put(r, x-2, y+2, z, Material.PURPLE_STAINED_GLASS);
            put(r, x+2, y+2, z, Material.PURPLE_STAINED_GLASS);
            put(r, x, y+3, z, Material.END_ROD);
            // Each house has a chest, but its location varies.
            int[][] caches = {{-1,1,1},{1,1,-1},{-1,1,-1},{1,1,1}};
            int[] cache = caches[rand.nextInt(caches.length)];
            chest(r, x+cache[0], y+cache[1], z+cache[2], rand, lootKind);
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
            spawner(r,x-5,y+1,z,randomMob(rand));
            spawner(r,x+5,y+1,z,randomMob(rand));
            chest(r,x+7,y+1,z+7,rand,"mega-ruins");
            chest(r,x-7,y+1,z-7,rand,"mega-ruins");
        }
    }
}
