package nwndungeon;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Switch;

import java.util.Random;

/**
 * 副本入口的"遗迹"建筑（风格自定：残缺石砖 + 苔石 + 断柱 + 灯笼）。
 *
 * 规模按难度递进：铁 7×7 / 金 9×9 / 钻 11×11，墙高与装饰也一起变多。
 * 中间那扇铁门带标记（入口登记在区块 PDC 里）；**门上方一格 = 该难度的方块**
 * （铁块/金块/钻石块），门下方那格同样是难度方块；门西侧台座放灯笼、东侧台座放
 * **石按钮**（按一下给门通电 → 触发进本）。
 */
public final class Ruins {

    private Ruins() {
    }

    /** 生成遗迹，返回门旁按钮的位置（用于登记"按钮 → 入口"）。 */
    public static Location build(World world, int x, int y, int z, Tier tier, Random random) {
        String id = tier == null ? "iron" : tier.id();
        Material tierBlock = tier == null ? Material.IRON_BLOCK : tier.floorBlock();
        String tierName = tier == null ? "§f普通" : tier.display();

        int half = switch (id) {
            case "diamond" -> 5;   // 11×11
            case "gold" -> 4;      // 9×9
            default -> 3;          // 7×7
        };
        int wallHeight = half - 1;  // 铁 2 / 金 3 / 钻 4
        int baseY = y - 1;

        prepareGround(world, x, y, z, half, baseY, random);
        buildWalls(world, x, z, half, baseY, wallHeight, random);
        buildDoorFrame(world, x, y, z, tierBlock, tierName);
        decorate(world, x, z, half, baseY, wallHeight, random);
        return new Location(world, x + 1, y + 1, z);
    }

    // ---------------------------------------------------------------- 地基与地板

    private static void prepareGround(World world, int x, int y, int z, int half, int baseY, Random random) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int wx = x + dx;
                int wz = z + dz;
                int surface = world.getHighestBlockYAt(wx, wz);
                if (surface > baseY) {
                    for (int wy = baseY + 1; wy <= surface; wy++) {
                        set(world, wx, wy, wz, Material.AIR);
                    }
                } else if (surface < baseY) {
                    // 地势低的地方垫起来当地基（最多向下补 6 格，免得悬崖上架出柱子）
                    for (int wy = Math.max(surface + 1, baseY - 6); wy <= baseY; wy++) {
                        set(world, wx, wy, wz, random.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE);
                    }
                }
                set(world, wx, baseY, wz, floorMaterial(random, dx, dz, half));
            }
        }
        // 遗迹上方清空，别让树和草穿模
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int wy = baseY + 1; wy <= y + 3; wy++) {
                    set(world, x + dx, wy, z + dz, Material.AIR);
                }
            }
        }
    }

    private static Material floorMaterial(Random random, int dx, int dz, int half) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) == half) {
            return random.nextInt(3) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
        }
        if (dx == 0) {
            return Material.CHISELED_STONE_BRICKS;   // 中间一条石板路，直接通到门
        }
        int roll = random.nextInt(10);
        if (roll < 5) {
            return Material.STONE_BRICKS;
        }
        if (roll < 7) {
            return Material.CRACKED_STONE_BRICKS;
        }
        if (roll < 9) {
            return Material.MOSSY_STONE_BRICKS;
        }
        return Material.COBBLESTONE;
    }

    // ---------------------------------------------------------------- 围墙

    private static void buildWalls(World world, int x, int z, int half, int baseY, int wallHeight, Random random) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int edge = Math.max(Math.abs(dx), Math.abs(dz));
                if (edge != half) {
                    continue;   // 只砌最外圈
                }
                boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                boolean gate = dz == half && Math.abs(dx) <= 1;   // 南面留 3 格门洞
                if (gate) {
                    continue;
                }
                if (!corner && random.nextInt(4) == 0) {
                    continue;   // 塌掉的缺口
                }
                int height = Math.max(1, wallHeight + (corner ? 1 : 0) - (random.nextInt(4) == 0 ? 1 : 0));
                for (int h = 0; h < height; h++) {
                    set(world, x + dx, baseY + 1 + h, z + dz, wallMaterial(random));
                }
                int topY = baseY + 1 + height;
                int roll = random.nextInt(10);
                if (roll < 3) {
                    set(world, x + dx, topY, z + dz, Material.COBBLESTONE_WALL);
                } else if (roll < 5) {
                    set(world, x + dx, topY, z + dz, Material.MOSSY_COBBLESTONE_WALL);
                } else if (roll < 7) {
                    slab(world, x + dx, topY, z + dz, random);
                } else if (roll < 8) {
                    stairs(world, x + dx, topY, z + dz, random);
                }
            }
        }
        // 四角立柱 + 门洞两侧立柱（凿制石砖 + 灯笼）
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                pillar(world, x + sx * half, z + sz * half, baseY, wallHeight);
            }
            pillar(world, x + sx * 2, z + half, baseY, wallHeight);
        }
    }

    private static void pillar(World world, int px, int pz, int baseY, int wallHeight) {
        for (int h = 0; h <= wallHeight; h++) {
            set(world, px, baseY + 1 + h, pz, Material.CHISELED_STONE_BRICKS);
        }
        set(world, px, baseY + 2 + wallHeight, pz, Material.LANTERN);
    }

    private static Material wallMaterial(Random random) {
        int roll = random.nextInt(10);
        if (roll < 4) {
            return Material.STONE_BRICKS;
        }
        if (roll < 6) {
            return Material.CRACKED_STONE_BRICKS;
        }
        if (roll < 8) {
            return Material.MOSSY_STONE_BRICKS;
        }
        return roll < 9 ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
    }

    // ---------------------------------------------------------------- 门与按钮

    private static void buildDoorFrame(World world, int x, int y, int z, Material tierBlock, String tierName) {
        // 难度方块：门下方那格 + 门上面那格（用户要求）
        set(world, x, y - 1, z, tierBlock);
        set(world, x, y + 2, z, tierBlock);

        placeDoor(world, x, y, z);

        // 两侧台座：西边灯笼、东边石按钮（按钮通电 → 门开 → 触发进本）
        set(world, x - 1, y, z, Material.CHISELED_STONE_BRICKS);
        set(world, x - 1, y + 1, z, Material.LANTERN);
        set(world, x + 1, y, z, Material.CHISELED_STONE_BRICKS);
        Block buttonBlock = world.getBlockAt(x + 1, y + 1, z);
        buttonBlock.setType(Material.STONE_BUTTON, false);
        if (buttonBlock.getBlockData() instanceof Switch button) {
            button.setFace(Switch.Face.FLOOR);
            button.setFacing(BlockFace.NORTH);
            button.setPowered(false);
            buttonBlock.setBlockData(button, false);
        }

        // 告示牌立在门楣（难度方块）上面
        set(world, x, y + 3, z, Material.OAK_SIGN);
        Block signBlock = world.getBlockAt(x, y + 3, z);
        if (signBlock.getState() instanceof Sign sign) {
            sign.setLine(0, "§8副本入口");
            sign.setLine(1, tierName + " §r难度");
            sign.setLine(2, "§7按下按钮进入");
            sign.update(true, false);
        }
    }

    private static void placeDoor(World world, int x, int y, int z) {
        Block lower = world.getBlockAt(x, y, z);
        lower.setType(Material.IRON_DOOR, false);
        if (lower.getBlockData() instanceof Door data) {
            data.setFacing(BlockFace.NORTH);
            data.setHalf(Bisected.Half.BOTTOM);
            data.setOpen(false);
            lower.setBlockData(data, false);
        }
        Block upper = world.getBlockAt(x, y + 1, z);
        upper.setType(Material.IRON_DOOR, false);
        if (upper.getBlockData() instanceof Door data) {
            data.setFacing(BlockFace.NORTH);
            data.setHalf(Bisected.Half.TOP);
            data.setOpen(false);
            upper.setBlockData(data, false);
        }
    }

    // ---------------------------------------------------------------- 装饰

    private static void decorate(World world, int x, int z, int half, int baseY, int wallHeight, Random random) {
        for (int i = 0; i < half; i++) {
            int dx = random.nextInt(half * 2 + 1) - half;
            int dz = random.nextInt(half * 2 + 1) - half;
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) {
                continue;   // 门前那一圈别挡路
            }
            int roll = random.nextInt(10);
            if (roll < 4) {
                slab(world, x + dx, baseY + 1, z + dz, random);
            } else if (roll < 7) {
                set(world, x + dx, baseY + 1, z + dz, wallMaterial(random));
            } else if (roll < 9) {
                stairs(world, x + dx, baseY + 1, z + dz, random);
            } else {
                set(world, x + dx, baseY + 1, z + dz, Material.COBBLESTONE_WALL);
            }
        }
        // 断柱：1~2 根
        int pillars = 1 + random.nextInt(2);
        for (int i = 0; i < pillars; i++) {
            int dx = random.nextInt(half * 2 + 1) - half;
            int dz = random.nextInt(half * 2 + 1) - half;
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 3) {
                continue;
            }
            int height = 1 + random.nextInt(Math.max(1, wallHeight));
            for (int h = 0; h < height; h++) {
                set(world, x + dx, baseY + 1 + h, z + dz, Material.CHISELED_STONE_BRICKS);
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void slab(World world, int x, int y, int z, Random random) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(random.nextBoolean() ? Material.STONE_BRICK_SLAB : Material.COBBLESTONE_SLAB, false);
        if (block.getBlockData() instanceof Slab slab) {
            slab.setType(random.nextInt(3) == 0 ? Slab.Type.TOP : Slab.Type.BOTTOM);
            block.setBlockData(slab, false);
        }
    }

    private static void stairs(World world, int x, int y, int z, Random random) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE_BRICK_STAIRS, false);
        if (block.getBlockData() instanceof Stairs stairs) {
            BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
            stairs.setFacing(faces[random.nextInt(faces.length)]);
            stairs.setHalf(random.nextBoolean() ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
            block.setBlockData(stairs, false);
        }
    }

    private static void set(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }
}
