package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * FTB Quests 联动:让同伴读得懂主人那本任务书,并据此推进整合包主线。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发(见各加载器 core 的
 * Builtin)。这里<b>不看加载器</b>:任务书与进度都是存档/配置里的文本文件,三个加载器
 * 上的位置与格式一模一样,要注册的东西也就一模一样——一个工具、一篇攻略。
 *
 * <p>登记方式和第三方插件一字不差:全部经 {@code NumenPlugins.register} 那扇门;
 * 编译期也一样——本模块的类路径上只有瘦 api jar,引擎内部类够不着。
 */
public final class NumenFtbQuests {

    private NumenFtbQuests() {}

    /** 由各加载器的 Builtin 在确认 FTB Quests 在场之后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(numen -> {
            numen.registerTool(new FtbQuestsTool());
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
