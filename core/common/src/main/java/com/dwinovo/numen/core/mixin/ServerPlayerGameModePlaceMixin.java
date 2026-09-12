package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.scan.OwnerBuildMemory;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记住"这一格是玩家自己放上去的"({@link OwnerBuildMemory})。
 *
 * <h2>为什么挂在这里</h2>
 * 唯一能同时拿到<b>放置者</b>和<b>落地那一格</b>的口,是玩家右键的服务端入口
 * {@code ServerPlayerGameMode#useItemOn}(原版方块放置 {@code BlockItem#place} 就在它里面),
 * 而它没有事件、没有回调、也没有 API 能替代 —— 所以只能 mixin。
 *
 * <h2>判定方式:比较"点之前"和"点之后"</h2>
 * 放置的落地格不一定等于被点的那一格:点可替换的方块(草、雪)时是它本身,点实心方块时是
 * 它的{@code face} 邻格。原版这段选择逻辑在 {@code BlockItem#place} 里,复制一份出来必然
 * 会跟版本漂移。这里改成<b>两个候选格各抄一次状态,再比返回时的状态</b>:哪一格真的变了,
 * 就记哪一格 —— 一份都不用复制的判据。
 *
 * <p>状态用引用相等比较:{@code BlockState} 是原版单例池里的对象,同一状态必是同一个实例,
 * 引用不等的就是"真的换了方块"。
 *
 * <h2>三条例外,都是必须的</h2>
 * <ul>
 *   <li><b>同伴自己动手的放置不记。</b>她垫路、搭桥、修楼梯走的是同一个
 *       {@code useItemOn};记下来的话,她<b>刚踩着自己搭的路</b>走两步,那条路就成了
 *       下一段的"不可破坏建筑" —— 自己把自己锁死。所以放置者是 {@link NumenPlayer}
 *       时直接跳过。</li>
 *   <li><b>变成空气的不记,反而要销账。</b>右键也能把方块弄没(铲子/斧子/剪切),
 *       那一格已经不是"她放的东西"了。</li>
 *   <li><b>状态没变的不记。</b>开箱子、按按钮、拉杆、踩压力板都是走这条口的,
 *       它们没有"放置"这件事发生。</li>
 * </ul>
 *
 * <p>代价说明(与 {@link OwnerBuildMemory} 的取舍同一件事):主人随手垫的一格也会被记成
 * "建筑",于是机器人绕路、或者报"地形受阻"而不是挖穿 —— 这是故意的。
 */
@Mixin(net.minecraft.server.level.ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModePlaceMixin {

    /** HEAD 抄下来的现场(服务端主线程单线程,tick 内不会被覆盖;同一玩家同一刻只有一个 useItemOn 在跑)。 */
    @Unique private Level numen$level;
    @Unique private ServerPlayer numen$placer;
    @Unique private BlockPos numen$clicked;
    @Unique private BlockPos numen$side;
    @Unique private BlockState numen$clickedBefore;
    @Unique private BlockState numen$sideBefore;

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void numen$snapshotPlacement(ServerPlayer player, Level level, ItemStack stack,
                                         InteractionHand hand, BlockHitResult hit,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        BlockPos clicked = hit.getBlockPos();
        BlockPos side = clicked.relative(hit.getDirection());
        numen$level = level;
        numen$placer = player;
        numen$clicked = clicked;
        numen$side = side;
        numen$clickedBefore = level.getBlockState(clicked);
        numen$sideBefore = level.getBlockState(side);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void numen$recordPlacement(ServerPlayer player, Level level, ItemStack stack,
                                       InteractionHand hand, BlockHitResult hit,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        Level snapshotLevel = numen$level;
        ServerPlayer placer = numen$placer;
        BlockPos clicked = numen$clicked;
        BlockPos side = numen$side;
        BlockState clickedBefore = numen$clickedBefore;
        BlockState sideBefore = numen$sideBefore;
        // 先清现场:这一段里任何异常都不该把上一次交互的坐标留给下一次
        numen$level = null;
        numen$placer = null;
        numen$clicked = null;
        numen$side = null;
        numen$clickedBefore = null;
        numen$sideBefore = null;
        if (snapshotLevel == null || placer instanceof NumenPlayer) {
            return;   // 同伴自己搭的路不算主人的建筑(见类文档)
        }
        numen$noteChange(snapshotLevel, clicked, clickedBefore);
        numen$noteChange(snapshotLevel, side, sideBefore);
    }

    /** 这一格在被点之后真的换了方块吗;换了就记账,变回空气就销账。 */
    @Unique
    private static void numen$noteChange(Level level, BlockPos pos, BlockState before) {
        if (pos == null || before == null) {
            return;
        }
        BlockState now = level.getBlockState(pos);
        if (now == before) {
            return;                              // 状态没变:没有放置发生(开箱子/按钮/拉杆)
        }
        if (now.isAir()) {
            OwnerBuildMemory.forget(level, pos);  // 那一格已经没了:旧记录不能再留着
            return;
        }
        OwnerBuildMemory.record(level, pos);
    }
}
