package com.dwinovo.numen.plugins.chainmine;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.TaskFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 连锁挖矿联动:同伴也能连锁挖矿——只要主人的整合包里装了 FTB Ultimine 或 Vein Mining。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发(见各加载器 core 的
 * Builtin)。这里<b>不看加载器</b>:两个目标模组都只有 Forge 版,但联动本身一个类都不
 * 引用它们(全走反射),于是三个加载器上要装的东西一模一样——一个工具、一篇攻略、
 * 一个任务类型。
 *
 * <p>登记方式和第三方插件一字不差:全部经 {@code NumenPlugins.register} 那扇门;
 * 编译期也一样——本模块的类路径上只有瘦 api jar,引擎内部类够不着。
 */
public final class NumenChainmine {

    /**
     * 两个模组各一道闸(加载器的 Builtin),<b>两个都在场时 install 会被叫两次</b>。
     * 工具注册表对重名是直接抛的(见 ToolRegistry.register),所以这里自己守着:
     * 第一次进来才装,后面的调用当无操作。
     */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private NumenChainmine() {}

    /** 由各加载器的 Builtin 在确认 FTB Ultimine 或 Vein Mining 在场之后调用;重复调用无副作用。 */
    public static void install(Path skillsRoot) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        // 任务类型要在工具被用之前就位;与工具同批注册,顺序无所谓(注册表是懒查的)。
        TaskFactory.register(ChainMineTaskRecord.class, ChainMineTask::new);
        NumenPlugins.register(numen -> {
            numen.registerTool(new ChainMineTool());
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
