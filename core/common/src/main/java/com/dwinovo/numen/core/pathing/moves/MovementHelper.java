package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 移动原语与搜索共用的静态方块判定库(BlockGetter 域):
 * 可穿行 / 可跳穿 / 可站立 / 禁挖 / 流体流动 / 破坏成本等。
 * 位置无关的判定拆成三态预筛(YES/NO/MAYBE),MAYBE 再做位置精判,
 * 让绝大多数格子只看 BlockState 就能出结论。
 */
public final class MovementHelper {

    private MovementHelper() {}

    /** 三态判定结果:仅看 BlockState 能否定论,MAYBE 需要位置精判。 */
    public enum Ternary {
        YES, MAYBE, NO
    }

    // ==================== 可穿行(身体能否占据该格) ====================

    public static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return canWalkThrough(context.view, context.loadedTest, x, y, z, context.get(x, y, z));
    }

    public static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        return canWalkThrough(context.view, context.loadedTest, x, y, z, state);
    }

    /** 实时世界重载(chunk 视为全部已加载)。 */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        return canWalkThrough(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
    }

    public static boolean canWalkThrough(BlockGetter view, ChunkLoadedTest loaded,
                                         int x, int y, int z, BlockState state) {
        Ternary result = canWalkThroughBlockState(state);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkThroughPosition(view, loaded, x, y, z, state);
    }

    /** 三态预筛:只看 BlockState。 */
    public static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.TRIPWIRE || block == Blocks.COBWEB
                || block == Blocks.END_PORTAL || block == Blocks.COCOA
                || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN
                || block instanceof ShulkerBoxBlock || block instanceof SlabBlock
                || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK
                || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock
                || block instanceof AzaleaBlock || block == Blocks.BIG_DRIPLEAF
                || block == Blocks.POWDER_SNOW) {
            return Ternary.NO;
        }
        if (NavSettings.get().blocksToAvoid().contains(block)) {
            return Ternary.NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // 木门/栅栏门假定可开(执行层右键);铁门无红石打不开,按实体墙处理
            if (block == Blocks.IRON_DOOR) {
                return Ternary.NO;
            }
            return Ternary.YES;
        }
        if (block instanceof CarpetBlock) {
            return Ternary.MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // 缓存 chunk 顶层的雪可能拿不到层数,留到位置精判
            return Ternary.MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getAmount() != 8) {
                // 非满格(流动中):岩浆照旧不可走,横向流水改成可穿。
                //
                // "流水是墙"曾经让河道/急流一律绕路或报无路,可原版玩家是能逆流游上去的
                // (推力 0.014 格/tick 抵不过游泳加速度,见 FlowCost 里的原版事实),
                // 代价该由 MovementTraverse 按顺流/横渡/逆流分档,而不是一刀切封死。
                //
                // 下落水柱不走这一支:原版 FALLING 的流体水量恒为 8
                // (见 isHorizontalWaterFlow 的注释),推力竖直向下,而执行侧在液体里
                // 从不按 JUMP(上浮输入),放行它就成了"能规划、走不动"。
                return NavSettings.get().allowFlowingWater && isHorizontalWaterFlow(fluidState)
                        ? Ternary.MAYBE
                        : Ternary.NO;
            }
            return Ternary.MAYBE;
        }
        if (block instanceof CauldronBlock) {
            return Ternary.NO;
        }
        return state.isPathfindable(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO, PathComputationType.LAND) ? Ternary.YES : Ternary.NO;
    }

    /** MAYBE 的位置精判:地毯 / 雪层 / 满格流体。 */
    public static boolean canWalkThroughPosition(BlockGetter view, ChunkLoadedTest loaded,
                                                 int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            // 地毯是薄层,可走过的前提是地毯下面能站
            return canWalkOn(view, loaded, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // 未加载 chunk 拿不到层数,放行(否则雪原长途寻路直接瘫掉)
            if (!loaded.isLoaded(x, z)) {
                return true;
            }
            // 原版可通行判定是 <5 层;但 2 格高净空里 ≥3 层就挤不过去了
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            return canWalkOn(view, loaded, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            // 横向流水(非满格水)走的是"泳位"语义:能不能穿只由"头出不出水"决定,
            // 水流快慢是"价"的问题,不是墙的问题(见 waterMoveCost)。
            // 其余"可能在流动"的情形(满格源挨着流水)维持旧判:不可穿。
            boolean horizontalFlow = isHorizontalWaterFlow(fluidState);
            if (!horizontalFlow && !isFallingWater(fluidState) && isFlowing(view, x, y, z, state)) {
                return false; // 水流会把人冲离路径
            }
            if (NavSettings.get().assumeWalkOnWater) {
                return false; // 水面行走语义下水柱不可穿
            }
            BlockState up = view.getBlockState(new BlockPos(x, y + 1, z));
            boolean headClear = up.getFluidState().isEmpty() && !(up.getBlock() instanceof WaterlilyBlock);
            if (isFallingWater(fluidState)) {
                // 下落水柱:不管头出不出水,都先过"底下有没有着落"这一关 ——
                // 底下是岩浆/虚空的水柱不许把身体放进去(被按下去就是没了)。
                // 水柱本身是泳道(每 tick 按跳的浮力挂得住),不是墙。
                return NavSettings.get().allowFallingWater
                        && fallingWaterTerminatesSafely(view, loaded, x, y, z);
            }
            if (!headClear) {
                // 头还泡在水里:横向流水照旧可穿(满格源挨着流水等模糊情形维持旧判);
                // 另外 —— <b>水面之下那一格泳道本来就该是"身体待着的那一格"</b>
                // (见 isSwimLane):只有让身体占得住那一层,路径才可能真的在水面之下游。
                // 以前这里一律 false,身体只能占水面那一格 → 执行侧怎么划水都进不了泳姿,
                // 实机就是"踩着水面走"。要真能容身:水方块本身(没有碰撞体),
                // 不是"水封的半砖/木牌"那种。
                return (NavSettings.get().allowFlowingWater && horizontalFlow)
                        || isSwimLane(view, loaded, x, y, z)
                                && state.getBlock() instanceof LiquidBlock;
            }
            return fluidState.getType() instanceof WaterFluid; // 只有水柱可游走
        }

        return state.isPathfindable(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO, PathComputationType.LAND);
    }

    // ==================== 完全无阻碍(可跳跃穿过) ====================

    /**
     * 比可穿行更严:不含需要右键的门/栅栏门、不含减速的
     * 藤蔓/梯子/蛛网、不含任何流体。用于跑酷与头顶净空检查。
     */
    public static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return Ternary.NO;
        }
        return state.isPathfindable(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO, PathComputationType.LAND) ? Ternary.YES : Ternary.NO;
    }

    public static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context.get(x, y, z));
    }

    public static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        return fullyPassable(state);
    }

    public static boolean fullyPassable(BlockGetter level, BlockPos pos) {
        return fullyPassable(level.getBlockState(pos));
    }

    public static boolean fullyPassable(BlockState state) {
        return fullyPassableBlockState(state) == Ternary.YES;
    }

    // ==================== 可站立(能否作为脚下地面) ====================

    public static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context.view, context.loadedTest, x, y, z, context.get(x, y, z));
    }

    public static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        return canWalkOn(context.view, context.loadedTest, x, y, z, state);
    }

    /** 实时世界重载。 */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        return canWalkOn(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return canWalkOn(view, loaded, x, y, z, view.getBlockState(new BlockPos(x, y, z)));
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded,
                                    int x, int y, int z, BlockState state) {
        Ternary result = canWalkOnBlockState(state);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkOnPosition(view, loaded, x, y, z, state);
    }

    /** 三态预筛:只看 BlockState。 */
    public static Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        if (isBlockNormalCube(state) && block != Blocks.MAGMA_BLOCK
                && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return Ternary.YES;
        }
        if (block instanceof AzaleaBlock) {
            return Ternary.YES;
        }
        if (block == Blocks.LADDER || (block == Blocks.VINE && NavSettings.get().allowVines)) {
            return Ternary.YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return Ternary.YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return Ternary.YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return Ternary.YES;
        }
        if (block instanceof StairBlock) {
            return Ternary.YES;
        }
        if (isWater(state)) {
            return Ternary.MAYBE;
        }
        if (isLava(state) && NavSettings.get().assumeWalkOnLava) {
            return Ternary.MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!NavSettings.get().allowWalkOnBottomSlab) {
                // 只许站非下半台阶
                return state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM ? Ternary.YES : Ternary.NO;
            }
            return Ternary.YES;
        }
        return Ternary.NO;
    }

    /**
     * MAYBE 的位置精判(水/岩浆)。水的"泳位"语义:默认只能站在<b>泳位</b>上
     * (见 {@link #isSwimLane}:身位那格是水、<b>头顶还有水</b> —— 身体没入水面之下),
     * 另留一格"出水的踏脚点"给登岸用({@link #isExitPad});开水面行走语义则是站在
     * 上方无水的水面上 —— 与泳位语义按 XOR 互斥。
     *
     * <p><b>入参是支撑格,不是身位格</b> —— 与实心方块同一把尺:判"节点 y 合不合法"用的是
     * {@code canWalkOn(x, y-1, z)}(见 {@code Movement.pathStart} 与各个成本函数),
     * 所以水这一支要往上问一格,问的才是"站在这一格上的人待在哪一层"。
     *
     * <p><b>水面那一格为什么不自己当路</b>:停在那一格时身体露在水面上(站姿眼高 1.62,
     * 眼睛在水面之上),原版 {@code updateSwimming} 的准入(疾跑 + 眼睛在水里)不成立 ——
     * 于是只剩"踩着水面走"。实机 12:45 的 {@code [diag]} 正是"泳道节点 62(水面格)、
     * 身位 63(水面之上)"。泳道因此下沉到水面之下那一格;水面那一格只在紧挨着岸、
     * 下一步能登岸时才有条件留下当踏板。
     *
     * <p><b>湖底那一格</b>走的是"支撑格是实心"那条路(节点 = 贴着湖底那一层水),
     * 由水价档位按"踩得到底"计价(见 {@link #waterTierCost})。它不再因为"头在水下"被排除:
     * 2 格深的水里人只可能待在贴着底那一格,排掉它等于把整个池子变成墙。
     */
    public static boolean canWalkOnPosition(BlockGetter view, ChunkLoadedTest loaded,
                                            int x, int y, int z, BlockState state) {
        if (isWater(state)) {
            BlockState upState = view.getBlockState(new BlockPos(x, y + 1, z));
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            // 站在这一格上的人,身位在它上面那一格 —— 泳位判据问的是那一格(见方法注)。
            int bodyY = y + 1;
            if (isFallingWater(state.getFluidState())) {
                // 下落水柱里的"泳位":浮力(每 tick 按跳)把人挂在水柱里,所以自上而下
                // 每一格都站得住;但只在水柱<b>底下有着落</b>时才算(见
                // fallingWaterTerminatesSafely)——不能浮在半空的瀑布里,更不能挂在
                // 一条直通岩浆的水柱上(那是被按进岩浆的捷径,正是要守住的危险面)。
                return NavSettings.get().allowFallingWater && !NavSettings.get().assumeWalkOnWater
                        && isFloating(view, loaded, x, bodyY, z)
                        && isSignedOffBelow(view, loaded, x, bodyY, z);
            }
            if (NavSettings.get().assumeWalkOnWater) {
                // 开水面行走语义:只能站在"上方无水"的水面上,与泳位语义按 XOR 互斥
                return !isWater(upState);
            }
            // 静水/横向流水停得住的两档(见方法注):
            //   1) 泳位 —— 身位那格是水、<b>头顶还有水</b>:身体没入水面之下,
            //      原版才进得了泳姿(见 isSwimLane);
            //   2) 出水的踏脚点 —— 水面那一格(头顶没水),只在这格紧挨着岸、
            //      下一步能登岸时才算(见 isExitPad)。它单独存在就是"踩着水面走"。
            return isSwimLane(view, loaded, x, bodyY, z)
                    || isExitPad(view, loaded, x, bodyY, z);
        }

        if (isLava(state) && !isFlowing(view, x, y, z, state) && NavSettings.get().assumeWalkOnLava) {
            return true;
        }

        return false; // 未识别的一律不站,宁可绕
    }

    /** 霜行者能否把该格冻成冰面(静水源且有附魔)。 */
    public static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state.getBlock() == Blocks.WATER
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * 若要站上/走过该格,它是否必须是实心的(霜行者判定用):
     * 梯子/藤蔓不算;流体上盖着上半台阶/顶部楼梯/关着的顶部活板门/
     * 脚手架/树叶等仍算有实心顶面。
     */
    public static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.assumeWalkOnWater) {
                return false;
            }
            if (context.getBlock(x, y + 1, z) instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    // ==================== 禁挖判定 ====================

    /**
     * 挖 (x,y,z) 是否被<b>物理上</b>禁止(代价无穷)。禁的只剩"要命的与动不了的":
     * <ul>
     *   <li>世界边界外(内缩一格)——那里连贴放/瞄准都做不到;</li>
     *   <li>被虫蚀方块(挖了放出蠹虫);</li>
     *   <li>上方/四个水平邻格是要命的东西:岩浆(挖开就引过来)、悬空的落沙
     *       (挖了会塌下来埋住她)。见 {@link #avoidAdjacentBreaking}。</li>
     * </ul>
     *
     * <p><b>曾经在这里、已经出去了的两条</b>(bug 2:过度保守)——
     * <b>冰</b>:挖掉冰只是开出一条路(它本来就是水),曾经当硬禁的结果是冰原/冻洋上
     * 宁可绕一整圈也不肯凿一块;<b>邻格是水</b>:水会灌下来把她冲离路径,但不会要命,
     * 降级成有限的高倍罚金({@link #neighbourFluidBreakMultiplier}),因为地下挖矿时
     * 脚边有水是常态,硬禁等于"该挖的矿碰都不碰"。
     *
     * <p>保护性的硬禁挖(do_not_break 标签、玩家自己放过的方块)不在这里 ——
     * 那是 {@link CalculationContext#breakCostMultiplierAt} 的事。
     */
    public static boolean avoidBreaking(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.worldBorder != null
                && !(x > context.worldBorder.getMinX()
                        && x + 1 < context.worldBorder.getMaxX()
                        && z > context.worldBorder.getMinZ()
                        && z + 1 < context.worldBorder.getMaxZ())) {
            return true;
        }
        Block b = state.getBlock();
        return b instanceof InfestedBlock
                || avoidAdjacentBreaking(context, x, y + 1, z, true)
                || avoidAdjacentBreaking(context, x + 1, y, z, false)
                || avoidAdjacentBreaking(context, x - 1, y, z, false)
                || avoidAdjacentBreaking(context, x, y, z + 1, false)
                || avoidAdjacentBreaking(context, x, y, z - 1, false);
    }

    /**
     * 邻格 (x,y,z) 是否让"挖它旁边那格"变得<b>要命</b>(=代价无穷)。只查上方与四个
     * 水平向,不查下方。上方是落沙类不禁(整根沙柱的连锁挖掘成本已计入);其他邻格:
     * 悬空的落沙类会被更新塌下来 → 禁;岩浆(源或流)→ 禁;从严开关
     * ({@code strictLiquidCheck})打开时任何相邻流体 → 禁。
     *
     * <p>水不在这个集合里:它由 {@link #neighbourFluidBreakMultiplier} 折成有限罚金。
     * 判据(源方块爱水平漫延 / 会向水平流的流 / 含水方块)与降级前逐条一致,只是
     * 换了后果。
     */
    public static boolean avoidAdjacentBreaking(CalculationContext context, int x, int y, int z, boolean directlyAbove) {
        BlockState state = context.get(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove
                && block instanceof FallingBlock
                && NavSettings.get().avoidUpdatingFallingBlocks
                && FallingBlock.isFree(context.get(x, y - 1, z))) {
            return true;
        }
        if (isLava(state)) {
            return true;   // 岩浆:源方块与流动的都是硬禁(挖开就是一条烧到身上的河)
        }
        if (NavSettings.get().strictLiquidCheck && !state.getFluidState().isEmpty()) {
            return true;   // 从严开关:任何相邻液体都禁挖(含含水方块)
        }
        return false;
    }

    /**
     * 挖 (x,y,z) 时"邻格有水"带来的成本乘数(没有邻水就是 1.0)。
     *
     * <p>与 {@link #avoidAdjacentBreaking} 互补:那边管<b>要命的</b>(岩浆/塌方/世界边界),
     * 这边管<b>会漫过来的</b>。水在挖穿之后会顺着缺口流下来,淹掉一段通道、把她冲离路径
     * —— 贵,但不是不能挖。判据沿用降级前那一套:
     * <ul>
     *   <li>上方是水:永远算(挖开就是头顶淋水);</li>
     *   <li>水平向的<b>源</b>方块:爱向水平漫延 → 算;</li>
     *   <li>水平向的<b>流水</b>:只要它下方不是液体就会继续向水平流 → 算;</li>
     *   <li><b>含水方块</b>(half-submerged 的楼梯/台阶之类):挖开旁边会渗水 → 算。</li>
     * </ul>
     * 多个方向命中就连乘,所以夹角里的那一格最贵。
     */
    public static double neighbourFluidBreakMultiplier(CalculationContext context, int x, int y, int z) {
        double penalty = context.waterAdjacentBreakMultiplier;
        double mult = 1.0;
        if (waterNeighbourRisk(context, x, y + 1, z, true)) {
            mult *= penalty;
        }
        if (waterNeighbourRisk(context, x + 1, y, z, false)) {
            mult *= penalty;
        }
        if (waterNeighbourRisk(context, x - 1, y, z, false)) {
            mult *= penalty;
        }
        if (waterNeighbourRisk(context, x, y, z + 1, false)) {
            mult *= penalty;
        }
        if (waterNeighbourRisk(context, x, y, z - 1, false)) {
            mult *= penalty;
        }
        return mult;
    }

    /** 邻格的水会不会在挖穿之后漫过来(见 {@link #neighbourFluidBreakMultiplier})。 */
    private static boolean waterNeighbourRisk(CalculationContext context, int x, int y, int z, boolean directlyAbove) {
        BlockState state = context.get(x, y, z);
        if (!isWater(state)) {
            return false;
        }
        if (directlyAbove || NavSettings.get().strictLiquidCheck) {
            return true;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true;   // 源方块爱水平漫延
            }
            return !(context.getBlock(x, y - 1, z) instanceof LiquidBlock);
        }
        return true;           // 含水方块(水 logged):挖开旁边会渗出来
    }

    // ==================== 破坏成本 ====================

    public static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    /**
     * 挖穿该格的成本(tick)。本就可穿行 → 0;流体 → INF;
     * 禁挖 → INF;否则 1/速度 + 附加罚金,再乘上下文乘数与邻水罚金。
     * {@code includeFalling} 时向上递归叠加整根落沙柱的成本。
     *
     * <p>三处 INF 的分工:{@code breakCostMultiplierAt} 管"不许"(sacred /
     * do_not_break 标签 / 玩家自己放的 / 许可), {@link #avoidBreaking} 管"要命"
     * (世界边界 / 虫蚀 / 岩浆 / 塌方),剩下能挖的东西只贵不堵
     * ({@link #neighbourFluidBreakMultiplier})。
     */
    public static double getMiningDurationTicks(CalculationContext context, int x, int y, int z,
                                                BlockState state, boolean includeFalling) {
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(context, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            double result = 1 / strVsBlock;
            result += context.breakBlockAdditionalCost;
            result *= mult * neighbourFluidBreakMultiplier(context, x, y, z);
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(context, x, y + 1, z, above, true);
                }
            }
            return result;
        }
        return 0; // 无需真挖,也就不必查上方落沙
    }

    // ==================== 放置相关 ====================

    /**
     * 该格是否可被放置动作替换掉:空气、单层雪(未加载 chunk 放行)、
     * 高草/大型蕨,及其余原版可替换方块。
     */
    public static boolean isReplaceable(int x, int y, int z, BlockState state, ChunkLoadedTest loaded) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            if (!loaded.isLoaded(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    public static boolean canPlaceAgainst(CalculationContext context, int x, int y, int z) {
        return canPlaceAgainst(context, x, y, z, context.get(x, y, z));
    }

    public static boolean canPlaceAgainst(CalculationContext context, int x, int y, int z, BlockState state) {
        if (!placeableWithinBorder(context.worldBorder, x, z)) {
            return false;
        }
        return canPlaceAgainst(state);
    }

    public static boolean canPlaceAgainst(BlockGetter level, BlockPos pos) {
        if (level instanceof net.minecraft.world.level.Level live
                && !placeableWithinBorder(live.getWorldBorder(), pos.getX(), pos.getZ())) {
            return false;
        }
        return BlockHelper.canPlaceAgainstAnyFace(level.getBlockState(pos));
    }

    /**
     * 贴面格是否离世界边界足够远:各向内缩一格——贴着边界的方块无法
     * 被右键选面。边界未知(null)按不限制。
     */
    public static boolean placeableWithinBorder(net.minecraft.world.level.border.WorldBorder border,
                                                int x, int z) {
        if (border == null) {
            return true;
        }
        return x > border.getMinX() && x + 1 < border.getMaxX()
                && z > border.getMinZ() && z + 1 < border.getMaxZ();
    }

    /**
     * 能否拿这一格当放置贴面(无坐标版:任何一个面可贴即算)。
     *
     * <p>唯一真源是 {@link BlockHelper#canPlaceAgainstAnyFace}:按<b>面</b>问
     * {@code isFaceSturdy} 的形状判定。旧判据是"完整实心方块或玻璃",把下半台阶的底面、
     * 楼梯的背面、灵魂沙的顶面这些<b>真能贴</b>的表面一并判死了 —— 于是执行器明明引得
     * 出那条射线,规划期却说"这儿放不了",两边对同一个动作各执一词。
     *
     * <p>箱/地毯这类"侧面不是齐平面"的方块仍然是 false:它们的碰撞盒缩在格子里,
     * 瞄侧面中心的射线打不到齐平面。这不是白名单式的保守,而是形状本身的结论。
     */
    public static boolean canPlaceAgainst(BlockState state) {
        return BlockHelper.canPlaceAgainstAnyFace(state);
    }

    /**
     * 按<b>具体哪一面</b>判:执行器已经知道要贴的是哪个面(它就是从 {@code placeAt}
     * 往那个方向找到 {@code against} 的),就该问那一面,而不是问整块方块。
     * 同一形状判据,见 {@link BlockHelper#canPlaceAgainst(BlockGetter, BlockPos, Direction)}。
     */
    public static boolean canPlaceAgainst(BlockGetter level, BlockPos pos, Direction face) {
        return BlockHelper.canPlaceAgainst(level, pos, face);
    }

    // ==================== 门 / 栅栏门通行 ====================

    /** 木门当下能否直接走过(玩家在门格里 → 不行)。 */
    public static boolean isDoorPassable(BlockGetter level, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) {
            return false;
        }
        BlockState state = level.getBlockState(doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }
        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }

    /** 栅栏门当下能否走过(只看 OPEN)。 */
    public static boolean isGatePassable(BlockGetter level, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }
        BlockState state = level.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }
        return state.getValue(FenceGateBlock.OPEN);
    }

    /**
     * 带朝向的门板通行判定:接近轴与门板朝向轴同向时,开着才能过;
     * 垂直时反而是关着才不挡路(门板收在格边)。
     */
    public static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState,
                                                    BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) {
            return false;
        }
        var facing = blockState.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = blockState.getValue(propertyOpen);

        net.minecraft.core.Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = net.minecraft.core.Direction.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = net.minecraft.core.Direction.Axis.X;
        } else {
            return true;
        }
        return (facing == playerFacing) == open;
    }

    // ==================== 危险格 ====================

    /** 绝不能走进去的格:任何流体、岩浆块、仙人掌、浆果丛、火等。 */
    public static boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty()
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    // ==================== 台阶 / 流体基础判定 ====================

    /** 占据下半格的台阶。 */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /** 是否为水(含流动态)。 */
    public static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    /** 是否为岩浆(含流动态)。 */
    public static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    /** 是否为任意液体。 */
    public static boolean isLiquid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /** 可能在流动:流体类且非满格。 */
    public static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getAmount() != 8;
    }

    /**
     * 该格流体是否在流动:非满格即流动;满格源方块若四个水平邻格
     * 任一可能在流动(池边),也按流动处理。
     */
    public static boolean isFlowing(BlockGetter view, int x, int y, int z, BlockState state) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getAmount() != 8) {
            return true;
        }
        return possiblyFlowing(view.getBlockState(new BlockPos(x + 1, y, z)))
                || possiblyFlowing(view.getBlockState(new BlockPos(x - 1, y, z)))
                || possiblyFlowing(view.getBlockState(new BlockPos(x, y, z + 1)))
                || possiblyFlowing(view.getBlockState(new BlockPos(x, y, z - 1)));
    }

    // ==================== 流水穿越 ====================

    /** 水流下游探几格(原版推力是连续矢量,规划里按格近似成"往那边 1~2 格")。 */
    private static final int PUSHED_CELLS = 2;

    /** 看一片水底下有没有底:最多往下看几格(grotto/峡谷底通常近在眼前,太深就别赌)。 */
    private static final int WATER_BASE_LOOKDOWN = 8;

    /** 原版按水高缩推力的门槛:{@code maxHeight < 0.4} 才缩,之上是全额推力。 */
    private static final double MIN_PUSH_HEIGHT = 0.4;

    /**
     * 是否<b>横向流动的水</b>:非源、水量 1..7、非 FALLING 的水。
     *
     * <p>为什么正好是这一组判据(不用再猜):{@code LiquidBlock.initFluidStateCache}
     * 把方块的 LEVEL 映射成流体状态 —— LEVEL 0 → {@code getSource(false)}
     * (水量 8、{@code isSource});LEVEL 1..7 → {@code getFlowing(8 - level, false)}
     * (水量 7..1、{@code FALLING = false});LEVEL ≥ 8 → {@code getFlowing(8, true)}
     * (水量 8、{@code FALLING = true},即下落水柱)。所以"非源 && 水量 &lt; 8"
     * 与"横向流水"本就是同一件事;FALLING 那一项只是把它写成显式的,免得将来
     * 谁改了水量语义没人发现。
     */
    public static boolean isHorizontalWaterFlow(FluidState fluidState) {
        return fluidState.getType() instanceof WaterFluid
                && !fluidState.isSource()
                && fluidState.getAmount() < 8
                && !isFallingWater(fluidState);
    }

    /**
     * 下落的水:原版 {@code FlowingFluid.FALLING} 的流体状态(瀑布的水柱)。
     * 它的 {@code getFlow} 竖直分量恒朝下(见 {@code FlowingFluid.getFlow} 末尾那段
     * "FALLING 且旁边有实心面 → 归一化后加 (0,-6,0)"),推力把人往<b>下</b>按。
     *
     * <p><b>曾经的墙、现在的价</b>:上一轮它被判成不可穿,理由是"执行侧在液体里不按
     * JUMP(没有上浮输入),放行就是能规划走不动"。本轮把执行侧那半个前提补上了
     * ({@link Movement#update()} 浮着就按跳),水柱因此可穿 —— 定价见
     * {@link FlowCost#fallingWaterCost}(向上贵、向下便宜),底线见
     * {@link #fallingWaterTerminatesSafely}(底下是岩浆/虚空就不许进)。
     */
    public static boolean isFallingWater(FluidState fluidState) {
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getValue(FlowingFluid.FALLING);
    }

    /**
     * (x,y,z) 这格<b>身位</b>里的水能不能把人浮住:那一格是水,而且水是可游的
     * (横向流水 / 下落水柱 / 静水都算,满格源挨着流水的模糊情形不算 ——
     * 与 {@link #canWalkThroughPosition} 同一把尺)。
     *
     * <p>入参是<b>身位格</b>(人要待的那一格),不是支撑格 —— 见
     * {@link #canWalkOnPosition} 的入参说明。
     *
     * <p>为什么需要这一档:原版 {@code LivingEntity.travel} 的水分支里
     * {@code if (!onGround()) h *= 0.5} —— 离地(浮着)时深海探索者只算一半;
     * 而 {@link CalculationContext.WaterCost} 的 {@code waterDepth} 入参就是为它留的
     * (踏底涉水 vs 浮在水柱里)。
     */
    public static boolean isFloatableLiquid(CalculationContext context, int x, int y, int z) {
        return isFloatableLiquid(context.view, context.loadedTest, x, y, z);
    }

    public static boolean isFloatableLiquid(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        BlockState state = view.getBlockState(new BlockPos(x, y, z));
        if (!isWater(state)) {
            return false;
        }
        if (isFallingWater(state.getFluidState())) {
            // 下落水柱:推力朝下,按住跳的人照样挂得住(每 tick +0.04 的上浮)。
            // "底下有没有着落"不在这一层判 —— 那是泳位的事(见 isSwimLane)。
            return NavSettings.get().allowFallingWater;
        }
        if (isHorizontalWaterFlow(state.getFluidState())) {
            return NavSettings.get().allowFlowingWater;
        }
        // 静水(含瀑布砸下来的那一片池塘):水就是水,浮得住。
        // 剩下的是"满格源挨着流水"这类说不清的情形,维持旧判(可穿)。
        return !isFlowing(view, x, y, z, state) || !isHorizontalWaterFlow(state.getFluidState());
    }

    /**
     * 身位在 (x,y,z) 这格的<b>身体是不是浮着的</b>:脚那格是水,而且<b>脚下一格也是水</b>
     * —— 踩不到底,只能靠浮力。
     *
     * <p>入参是<b>身位格</b>(人要待的那一格),不是支撑格 —— 见
     * {@link #canWalkOnPosition} 的入参说明。水里那三条处置({@link Movement#waterDrive})
     * 刻意<b>不</b>用这一条:浮在水面时脚正好落在水面上方那一格(空气)里,按格问必然为假,
     * 那条判据会把最该上浮的状态判成"不在水里"。
     *
     * <p>这一条是两个地方的分界线:
     * <ul>
     *   <li>水价档位:{@link #waterTierCost} 浮着按 {@code FLOATING_DEPTH}(原版离地时
     *       附魔减半),否则按涉水档;</li>
     *   <li>泳位:{@link #canWalkOnPosition} 据此放行整条泳道(浮着的水格 + 浮着的水柱)。</li>
     * </ul>
     *
     * <p>脚下一格是水的判据用的是 {@link #isFloatableLiquid}(不要求"头出得水"):
     * 水柱中段那几格照样是浮着的 —— 只是模型里不拿它们当落脚点罢了。
     */
    public static boolean isFloatingAt(CalculationContext context, int x, int y, int z) {
        return isFloating(context.view, context.loadedTest, x, y, z);
    }

    public static boolean isFloatingAt(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return isFloating(view, loaded, x, y, z);
    }

    private static boolean isFloating(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return isFloatableLiquid(view, loaded, x, y, z)
                && isFloatableLiquid(view, loaded, x, y - 1, z); // 脚下一格也是水 → 踩不到底
    }

    /**
     * (x,y,z) 这格<b>身位</b>能不能当泳位/落脚点(入参是身位格,不是支撑格)。
     *
     * <p>两条路,两条都要求 {@link #hasWaterAbove 头顶还有水}(身体没入水面之下):
     * <ul>
     *   <li><b>静水/横向流水</b>:要"浮着"——脚那格与脚下一格都是水。湖底那一格因此
     *       被排除(脚踩着实心、头在水下是憋气,不该当路)。</li>
     *   <li><b>下落水柱</b>:浮力(每 tick 按跳)挂在柱子里,所以自上而下每一格都算,
     *       只要这条水柱底下有着落(不是岩浆、不是虚空)—— 就是
     *       {@link #fallingWaterTerminatesSafely}。</li>
     * </ul>
     *
     * <p><b>泳道不在水面那一格,而在水面之下那一格</b>(本轮修正):水面那一格
     * 上面就是空气,身体露在水面上,原版<b>进不了泳姿</b>(见 {@link #hasWaterAbove}
     * 的反汇编依据)。实机表现正是"踩着水面走":身体浮在水面格的上沿、眼睛在水面
     * 之上,{@code updateSwimming} 的首个条件不成立,于是照陆地那一套走;
     * 规划出来的节点又恰好是那一格,执行侧怎么按跳都只在液面上打转。
     * 排掉水面那一格之后,模型落点自动落在"头刚好没入"的那一层。
     * 浅水(1~2 格)照旧不受影响:那几格不是浮着的水(脚下踩得到底),
     * 走的是"走进水里的那一格"。
     */
    private static boolean isSwimLane(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        if (!hasWaterAbove(view, loaded, x, y, z) || !isFloatableLiquid(view, loaded, x, y, z)) {
            return false;
        }
        BlockState state = view.getBlockState(new BlockPos(x, y, z));
        if (isFallingWater(state.getFluidState())) {
            // 水柱里的"泳位":浮力挂得住,但底下得有着落(见 isSignedOffBelow)
            return isSignedOffBelow(view, loaded, x, y, z);
        }
        // 注意这里不再要求"脚下一格也是水"(那是水价档位 isFloatingAt 的事):
        // 2 格深的水里,人只可能待在贴着底那一格 —— 要求"踩不到底"就把整个池子变成墙。
        return true;
    }

    /** {@link #isSwimLane} 的上下文版(不查"水柱底下有没有着落"那一支,落地择层用)。 */
    public static boolean isSwimLaneAt(CalculationContext context, int x, int y, int z) {
        return isSwimLane(context.view, context.loadedTest, x, y, z);
    }

    /**
     * (x,y,z) 这格<b>身位</b>是"浮在水面那一格":脚下是水、<b>头顶不是水</b> ——
     * 身体露在水面上,原版进不了泳姿(见 {@link #hasWaterAbove})。
     */
    public static boolean isSurfaceFloat(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return !hasWaterAbove(view, loaded, x, y, z)
                && isFloating(view, loaded, x, y, z);
    }

    /**
     * 水面那一格能不能当<b>出水的踏脚点</b>:它紧挨着岸,下一步(上升原语,横一格再上一格)
     * 就能登上去。
     *
     * <p>为什么水面那一格不能自己当路:停在那一格时身体露在水面上,{@code updateSwimming}
     * 的准入(疾跑 + 眼睛在水里)不成立 —— 那就是实机看到的"踩着水面走"。可从水里上岸必须
     * 经过这样一格:泳道(没入水面之下那一格)横着走到岸边时,脚到岸面差着 1 格,
     * 上升原语只能"横一格 + 上一格",于是它需要一格"水面上的落脚点"当踏板。
     * 于是把水面那一格收窄成<b>只在这一个用途下</b>存在:邻格有能站的岸(节点在它上面一格),
     * 且那一格本身通透(身体进得去)。湖中央的水面格四邻都是水或更深的岸,自然不算路。
     *
     * <p>不会递归:邻格若是水,{@link #canWalkOn} 问的是"能不能站在那格水上"(节点在它上面),
     * 而水面上方是空气 → 立刻为假。
     */
    private static boolean isExitPad(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        if (!isSurfaceFloat(view, loaded, x, y, z)) {
            return false;
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX();
            int nz = z + d.getStepZ();
            // 邻格岸面上的身位得通透(身体挤得进去),而且踩得住(节点 = 那格顶面)
            BlockPos up = new BlockPos(nx, y + 1, nz);
            if (canWalkThrough(view, loaded, nx, y + 1, nz, view.getBlockState(up))
                    && canWalkOn(view, loaded, nx, y, nz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * (x,y,z) 这格<b>身位</b>上面还有水:身体没入水面之下 —— 泳姿的物理门槛。
     *
     * <p>为什么门槛是"眼睛进水"而不是"脚泡在水里"(<b>1.20.1 反汇编核对</b>,
     * Gradle 缓存里的 official 映射 jar):
     * <ul>
     *   <li>{@code Entity.updateSwimming}:不在泳姿时
     *       {@code setSwimming(isSprinting() && (isUnderWater() || canStartSwimming()) && !isPassenger())}
     *       —— <b>疾跑 + 眼睛在水里</b>两条缺一不可;而
     *       {@code isUnderWater() = wasEyeInWater && isInWater()};</li>
     *   <li>{@code Entity.updateFluidOnEyes}(每 tick 在 {@code baseTick} 里先于
     *       {@code updateSwimming} 调用)判"眼睛进水"用的是
     *       {@code eyeY - 0.111 < 格底 + 水面高};而
     *       {@code FlowingFluid.getHeight} 只在"上面还有水"时返回 1.0,
     *       顶层水格只有 8/9 = 0.888 —— 所以站在水面那一格里,眼睛(站姿眼高 1.62,
     *       见 {@code Player.getStandingEyeHeight})永远在水面之上;</li>
     *   <li>{@code LivingEntity.travel} 的水分支只在 {@code isSwimming()} 时走
     *       {@code Player.travel} 的游泳竖直耦合(俯仰就是竖直速度),不进泳姿就没有
     *       "下潜/上浮"这一套,只剩"在水里走"。</li>
     * </ul>
     */
    private static boolean hasWaterAbove(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        // 用"可浮液体"这一把尺:下落水柱与横向流水都算(受限开关同 isSwimLane 那两支)
        return isFloatableLiquid(view, loaded, x, y + 1, z);
    }

    /** 这一格水柱下面有没有着落(水池/地面):只判"掉下去会不会没命",不管是不是泳位。 */
    private static boolean isSignedOffBelow(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return NavSettings.get().allowFallingWater && waterBaseIsSafe(view, loaded, x, y - 1, z);
    }



    /**
     * 下落水柱 (x,y,z) 的下方有没有着落:沿水柱往下扫,直到
     * <ul>
     *   <li>水柱底下的那格<b>要命</b>(岩浆 / 火 / 岩浆块 / 仙人掌 / 甜浆果 / 末地门)
     *       → <b>不行</b>。这就是"水柱底部是岩浆/峡谷/虚空时不许规划进去"那一条;</li>
     *   <li>水柱底下是能站住的地面 → 行(游到底就是岸);</li>
     *   <li>水柱底下是<b>另一格水</b> → 行,那个人能浮在水面上;那一格再往下的账由
     *       它自己那趟水去算,不在这里往下递归(避免瀑布打在海洋上时一路扫到海底);</li>
     * </ul>
     * 一路到世界底还是水/空气 → <b>不行</b>(峡谷或虚空:人一路掉到底)。
     *
     * <p><b>看不清的下游(未加载)一律算不安全</b> —— 宁可绕路,不赌一次(与
     * {@link #flowCarriesIntoDanger} 同一条规矩)。
     */
    public static boolean fallingWaterTerminatesSafely(BlockGetter view, ChunkLoadedTest loaded,
                                                       int x, int y, int z) {
        int bottom = view.getMinBuildHeight();
        for (int py = y - 1; py > bottom; py--) {
            if (!loaded.isLoaded(x, z)) {
                return false; // 水柱穿过未加载区块:底下有什么不知道
            }
            BlockState below = view.getBlockState(new BlockPos(x, py, z));
            if (isDeadlyToBePushedInto(below)) {
                return false; // 水柱底部是岩浆/火:人被按进去就没了
            }
            if (isWater(below)) {
                // 水柱落进水里(池塘/湖面/另一条水柱):人浮在水面上,是安全的着落。
                // 底线只查"这片水底下是不是要命的东西" —— 水接水一层层往下看,遇到
                // 岩浆/火/虚空才算危险(瀑布砸进岩浆池、或一路砸进峡谷)。
                return waterBaseIsSafe(view, loaded, x, py, z);
            }
            if (!below.getFluidState().isEmpty()) {
                return false; // 落到岩浆之外的别的液体上:不赌
            }
            if (canWalkOn(view, loaded, x, py, z, below)) {
                return true; // 水柱落在地面/泳道上:游到底就是岸
            }
            if (!canWalkThrough(view, loaded, x, py, z, below)) {
                return false; // 撞上不能穿也不能站的方块(栅栏之类):别往这条水柱里规划
            }
        }
        return false; // 一路通到世界底:虚空 / 深谷
    }

    /**
     * 该格流水的水平流速矢量(长度 0..1)。直接问原版 {@code FluidState.getFlow}
     * ——实体推力的方向就是它({@code Entity.updateFluidHeightAndDoFluidPushing}),
     * 不自己另造一套近似。不是横向流水、或本地水面没有梯度(静水)→ {@link Vec3#ZERO}。
     *
     * <p>线程审计:只读 {@code context.view}(冻结快照)与相邻四格的方块/流体状态,
     * 与其余成本函数同一把尺,不会解引用玩家或活世界。
     */
    public static Vec3 horizontalWaterFlow(CalculationContext context, int x, int y, int z) {
        FluidState fluidState = context.get(x, y, z).getFluidState();
        if (!isHorizontalWaterFlow(fluidState)) {
            return Vec3.ZERO;
        }
        Vec3 flow = fluidState.getFlow(context.view, new BlockPos(x, y, z));
        // 竖直分量不属于"横向流水"(FALLING 的推力另有处置),这里只留水平面内的方向
        return new Vec3(flow.x, 0, flow.z);
    }

    /**
     * 站在该格流水里,水流会不会把人推向要命的地方(岩浆 / 火 / 悬崖 / 虚空),
     * 该情形下这一格<b>不可规划</b>({@link ActionCosts#COST_INF})。
     *
     * <p>为什么必须有这一关:一旦放行流水,规划器就敢走进一块<b>会自己走路的地形</b>
     * ——原版每 tick 往流向推 0.014 格/tick(见 {@link FlowCost}),水流末端是瀑布、
     * 岩浆池或峡谷时,人是被"送"进去的,而单格定价看不见这件事。
     * 判据只看流向的<b>主方向</b>下游 {@link #PUSHED_CELLS} 格:那里是要命方块,
     * 或者一路下去没有落脚点,就按危险处理。<b>看不清的下游(未加载)一律算危险</b>
     * ——宁可绕路,不赌一次。
     *
     * <p>已知的粗:<b>推力是连续的</b>,这里只按格看两格。人正被推着走时多漂一格、
     * 恰好漂过崖口的情形管不住 —— 那种情形交给既有的事后机制(动作超时 → 重规划 /
     * 坠落计价),不在这里假装精确。
     */
    public static boolean flowCarriesIntoDanger(CalculationContext context, int x, int y, int z) {
        return flowCarriesIntoDanger(context, x, y, z, horizontalWaterFlow(context, x, y, z));
    }

    private static boolean flowCarriesIntoDanger(CalculationContext context, int x, int y, int z, Vec3 flow) {
        if (flow.lengthSqr() <= 1e-8) {
            return false; // 静水:谁也不推谁
        }
        int stepX = 0;
        int stepZ = 0;
        if (Math.abs(flow.x) >= Math.abs(flow.z)) {
            stepX = flow.x > 0 ? 1 : -1;
        } else {
            stepZ = flow.z > 0 ? 1 : -1;
        }
        for (int step = 1; step <= PUSHED_CELLS; step++) {
            int px = x + stepX * step;
            int pz = z + stepZ * step;
            if (!context.isLoaded(px, pz)) {
                return true; // 下游看不清:按危险处理,宁可绕
            }
            if (isDeadlyToBePushedInto(context.get(px, y, pz))
                    || isDeadlyToBePushedInto(context.get(px, y + 1, pz))) {
                return true; // 被推进岩浆/火里就是没了,这里不讨论"疼不疼"
            }
            if (!hasSupportBelow(context, px, y, pz)) {
                return true; // 一路下去没有落脚点:悬崖 / 峡谷 / 虚空
            }
        }
        return false; // 下游两格都有着落
    }

    /**
     * (x,y,z) 这片水的<b>底下</b>安不安全:水接水一层层往下扫,直到
     * <ul>
     *   <li>要命的东西(岩浆/火/岩浆块/仙人掌/甜浆果/末地门)→ 不安全;</li>
     *   <li>能站住的地面 → 安全(浅水塘、河床、海底都算);</li>
     *   <li>一路到世界底 → 不安全(虚空)。</li>
     * </ul>
     * 水柱落点用得到它:瀑布砸下来的那片池塘是安全着陆点,砸进岩浆池就不是。
     */
    private static boolean waterBaseIsSafe(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        int bottom = view.getMinBuildHeight();
        int limit = y - WATER_BASE_LOOKDOWN;
        for (int py = y; py > bottom && py >= limit; py--) {
            if (!loaded.isLoaded(x, z)) {
                return false; // 看不清就按危险处理,不赌
            }
            BlockState state = view.getBlockState(new BlockPos(x, py, z));
            if (isDeadlyToBePushedInto(state)) {
                return false; // 这片水底下是岩浆:掉进去就没了
            }
            if (isWater(state) || state.isAir()) {
                continue; // 水接水、水下面还有一层空腔:继续往下找底
            }
            return canWalkOn(view, loaded, x, py, z, state); // 找到能站住的水底才算安全
        }
        return false; // 看不到底(峡谷/虚空):不赌
    }

    /**
     * 撞上/落进这格会不会要命。比 {@link #avoidWalkingInto} 窄一层:水不算 —— 水就是路。
     *
     * <p>气泡柱曾经(以及 {@link #avoidWalkingInto} 里现在仍然)在这一列,但对"水柱
     * 底下有什么"这个探针来说是错的:向上气泡柱恰恰是原版把人<b>送上去</b>的东西,而
     * 向下气泡柱也不是岩浆(按跳浮得出去)。真正该拦的是"一旦进去就没了"的那几种。
     */
    private static boolean isDeadlyToBePushedInto(BlockState state) {
        Block block = state.getBlock();
        return isLava(state)
                || block instanceof BaseFireBlock
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.END_PORTAL;
    }

    /** (x,y,z) 往下 {@code maxFallHeightNoWater} 格内有没有能停住身体的落点。 */
    private static boolean hasSupportBelow(CalculationContext context, int x, int y, int z) {
        int limit = Math.max(1, context.maxFallHeightNoWater);
        for (int drop = 1; drop <= limit; drop++) {
            int py = y - drop;
            if (py <= context.worldBottom) {
                return false; // 一路到世界底:虚空或深谷
            }
            if (canWalkOn(context.view, context.loadedTest, x, py, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该格的水价档:浮着(见 {@link #isFloatingAt})就用 {@code FLOATING_DEPTH} 那一档
     * ——原版在水里离地时深海探索者只算一半({@code if (!onGround()) h *= 0.5},
     * 见 {@link CalculationContext.WaterCost});踩得到底就是涉水档 {@code WADING_DEPTH}。
     *
     * <p><b>为什么非要有这一档</b>(现象 1 的模型侧):{@link CalculationContext#waterWalkSpeed}
     * 是<b>一次搜索一个值</b>,取的是涉水档。深水横渡时脚那格是水、脚下也是水,人是浮着的,
     * 却一直按涉水档收钱 —— 有深海探索者时这就是"深水里按踩底的速度定价",再叠上
     * 泳道里 {@code isWater(pb0)} 为真而把疾跑折扣关掉,泳道就比湖底(脚在干格上、
     * 吃到疾跑折扣)还贵,规划器于是把深水横渡整条压在湖底当陆地走。
     *
     * <p><b>现象 2 又补了一刀</b>:泳道这一档原先连速度基准也拿错 —— 它和不疾跑的
     * 涉水档共用 2.2 格/s 那颗常量,而深水里只有泳姿能移动(泳姿准入就是
     * {@code isSprinting()},原版水阻随之从 0.8 降到 0.9)。现在泳道档的基准是
     * {@link ActionCosts#SWIM_ONE_BLOCK_COST}(4 格/s),涉水档原样不动。
     */
    public static double waterTierCost(CalculationContext context, double baseWaterCost,
                                       int destX, int destY, int destZ) {
        if (!isFloatingAt(context, destX, destY, destZ)) {
            return baseWaterCost; // 踩得到底:涉水档
        }
        return CalculationContext.WaterCost.cost(context.waterDepthStrider,
                CalculationContext.WaterCost.FLOATING_DEPTH);
    }

    /**
     * 穿越一格流水的水价(顺流便宜、横渡原价、逆流贵),危险流向直接
     * {@link ActionCosts#COST_INF};下落水柱走另一档(向上贵、向下便宜,见
     * {@link FlowCost#fallingWaterCost}),水柱底下要命时同样 {@code COST_INF}。
     * 静水、无水、以及"这就是个普通水格"的情形一格不涨价。
     *
     * @param baseWaterCost 该档"没有水流"的水价({@link CalculationContext#waterWalkSpeed})
     * @param fromY         起点脚位的高度,只给下落水柱算落差用({@code destY - fromY})
     * @return 每格成本(tick);{@link ActionCosts#COST_INF} 表示水流会把人送进危险里
     */
    public static double waterMoveCost(CalculationContext context, int fromX, int fromZ, int fromY,
                                       int destX, int destY, int destZ, double baseWaterCost) {
        FluidState fluidState = context.get(destX, destY, destZ).getFluidState();
        if (isFallingWater(fluidState)) {
            // 下落水柱:推力竖直朝下,顺水柱下去便宜、逆着上浮贵(见 FlowCost.fallingWaterCost)。
            // 底线先判:水柱底下是岩浆/虚空就不许进(与横向流水的流向探针同一条规矩)。
            if (!NavSettings.get().allowFallingWater
                    || !fallingWaterTerminatesSafely(context.view, context.loadedTest, destX, destY, destZ)) {
                return COST_INF;
            }
            return FlowCost.fallingWaterCost(baseWaterCost, destY - fromY, context.waterDepthStrider);
        }
        if (!isHorizontalWaterFlow(fluidState)) {
            return baseWaterCost; // 静水 / 不是水:与放行流水之前同价
        }
        Vec3 flow = horizontalWaterFlow(context, destX, destY, destZ);
        if (flow.lengthSqr() <= 1e-8) {
            return baseWaterCost; // 本地水面没有梯度:没有推力可言
        }
        if (flowCarriesIntoDanger(context, destX, destY, destZ, flow)) {
            return COST_INF;
        }
        // 原版只在"水高 < 0.4 格"时按水高把推力缩一次(脚踝深的水膜几乎推不动),
        // 0.4 以上一律全额推力。脚按格底算(涉水),深水柱里 getHeight 直接是 1。
        double heightAboveFeet = fluidState.getHeight(context.view, new BlockPos(destX, destY, destZ));
        double strength = heightAboveFeet >= MIN_PUSH_HEIGHT ? 1 : heightAboveFeet;
        return FlowCost.cost(baseWaterCost, destX - fromX, destZ - fromZ, flow.x, flow.z,
                strength, context.waterDepthStrider);
    }

    /**
     * 完整实心立方体判定:碰撞形状为满格,并排除一批形状异常/
     * 会动的方块(竹、活塞移动方块、脚手架、潜影盒、滴水石锥、
     * 紫水晶簇)。取形状抛异常时按 false。
     */
    public static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // 拿不到碰撞形状的异类按非实心处理
        }
        return false;
    }

}
