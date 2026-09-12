package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * Litematica 联动:让同伴看得见玩家加载的投影。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发(见各加载器 core 的
 * Builtin)。这里<b>不看加载器</b>:Litematica 与它的 Forge 移植 Forgematica 都是
 * 客户端模组,两个加载器上要注册的东西一模一样——一个工具、一篇攻略。
 *
 * <p>登记方式和第三方插件一字不差:全部经 {@code NumenPlugins.register} 那扇门。
 * 编译期也一样——本模块的类路径上只有瘦 api jar,引擎内部类够不着。
 */
public final class NumenLitematica {

    private NumenLitematica() {}

    /** 由各加载器的 Builtin 在确认 Litematica 在场、且当前是客户端之后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(numen -> {
            numen.registerTool(new LitematicaTool());
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
