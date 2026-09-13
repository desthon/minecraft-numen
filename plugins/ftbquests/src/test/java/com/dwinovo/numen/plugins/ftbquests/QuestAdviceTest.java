package com.dwinovo.numen.plugins.ftbquests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务书解析 + "下一步该做什么"的判断。
 *
 * <p>fixture 照真实任务书(命轮无章 promax / FTB Quests 2001.4.17)的写法搭:同一章里
 * 铺开全部四种依赖规则、可选与隐藏任务、以及半途的 objective 计数。这样一条断言就能
 * 钉住"可做 / 在做 / 做不了 / 不知道"四种状态是怎么分出来的——这条判断是这套联动里
 * 唯一会影响她行为的东西,值得比解析器本身测得细。
 */
class QuestAdviceTest {

    private static final String CHAPTER = """
            {
            	id: "AAAA000000000001"
            	title: "主线"
            	filename: "main"
            	order_index: 1
            	quests: [
            		{
            			id: "0000000000000001"
            			title: "开篇"
            			tasks: [{
            				id: "1000000000000001"
            				title: "读一读"
            				type: "checkmark"
            			}]
            		}
            		{
            			id: "0000000000000002"
            			title: "第二"
            			dependencies: ["0000000000000001"]
            			rewards: [{
            				id: "2000000000000001"
            				item: "celestial_artifacts:chaotic_etching"
            				type: "item"
            			}]
            			tasks: [{
            				count: 10L
            				id: "1000000000000002"
            				item: "infinite_random:money"
            				type: "item"
            			}]
            		}
            		{
            			id: "0000000000000003"
            			title: "第三"
            			dependencies: ["0000000000000001", "0000000000000002"]
            		}
            		{
            			id: "0000000000000004"
            			title: "分支"
            			dependency_requirement: "one_started"
            			dependencies: ["0000000000000001", "0000000000000002"]
            		}
            		{
            			id: "0000000000000005"
            			invisible: true
            			title: "藏起来的技术任务"
            		}
            		{
            			can_repeat: true
            			id: "0000000000000006"
            			optional: true
            			title: "可选"
            		}
            		{
            			id: "0000000000000007"
            			min_required_dependencies: 1
            			title: "满足一个就算够"
            			dependencies: ["0000000000000001", "0000000000000002"]
            		}
            	]
            }""";

    private static final String PROGRESS = """
            {
            	version: 1
            	uuid: "6d596261bea9413ab41ba12bbc267abd"
            	name: "desthon#6d596261"
            	task_progress: {
            		1000000000000002: 4
            	}
            	started: {
            		0000000000000001: 1782958207133L
            		0000000000000002: 1782958207133L
            	}
            	completed: {
            		0000000000000001: 1782958207138L
            	}
            }""";

    private static QuestBook book() {
        return QuestBook.of(List.of(QuestBook.parseChapter(CHAPTER, "main.snbt")));
    }

    private static QuestProgress progress() {
        return QuestProgress.parse(PROGRESS, "6d596261-bea9-413a-b41b-a12bbc267abd", null, 0L);
    }

    private static QuestDef quest(String id) {
        Optional<QuestDef> found = book().quest(id);
        assertTrue(found.isPresent(), "fixture 里应该有此任务:" + id);
        return found.get();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @Test
    void aChapterCarriesItsQuestsWithObjectivesAndRewards() {
        ChapterDef chapter = book().chapters().get(0);

        assertEquals("主线", chapter.displayTitle());
        assertEquals(7, chapter.quests().size());
        QuestDef second = quest("0000000000000002");
        assertEquals(1, second.tasks().size());
        assertEquals("item", second.tasks().get(0).type());
        assertEquals(10L, second.tasks().get(0).count());
        assertEquals("bring 10x infinite_random:money", second.tasks().get(0).describe());
        assertEquals(List.of("0000000000000001"), second.dependencies());
        assertEquals("item: celestial_artifacts:chaotic_etching x1", second.rewards().get(0));
    }

    @Test
    void aQuestWithNoTitleFallsBackToItsFirstObjectiveThenItsId() {
        QuestDef untitled = QuestBook.parseQuest(Snbt.parseCompound("""
                {
                	id: "0000000000000009"
                	tasks: [{
                		id: "1000000000000009"
                		title: "读一读这一页"
                		type: "checkmark"
                	}]
                }"""), "0000000000000009", "AAAA000000000001", "主线");

        assertEquals("读一读这一页", untitled.displayTitle());
    }

    @Test
    void everyTaskTypeThePackUsesGetsAReadableObjective() {
        assertEquals("kill 3x minecraft:warden",
                QuestBook.parseTask(Snbt.parseCompound("{ entity: \"minecraft:warden\", type: \"kill\", value: 3 }"))
                        .describe());
        assertEquals("reach dimension minecraft:the_nether",
                QuestBook.parseTask(Snbt.parseCompound("{ dimension: \"minecraft:the_nether\", type: \"dimension\" }"))
                        .describe());
        assertEquals("earn advancement witherstormmod:main/wither_storm_defeated (criterion x)",
                QuestBook.parseTask(Snbt.parseCompound("{ advancement: \"witherstormmod:main/wither_storm_defeated\", "
                        + "criterion: \"x\", type: \"advancement\" }")).describe());
        // 认不出的类型不丢:原始键值对留在 detail 里,模型仍看得见它要什么
        assertEquals("custom (stage_name=chapter_two)",
                QuestBook.parseTask(Snbt.parseCompound("{ id: \"1\", type: \"custom\", stage_name: \"chapter_two\" }"))
                        .describe());
    }

    // ------------------------------------------------------------------
    // 判断
    // ------------------------------------------------------------------

    @Test
    void dependenciesFollowFtbsFourRulesNotJustAllCompleted() {
        QuestBook book = book();
        QuestProgress progress = progress();

        assertEquals(QuestAdvice.DependencyState.SATISFIED,
                QuestAdvice.dependencyState(book.quest("0000000000000002").orElseThrow(), progress), "前置已完成");
        assertEquals(QuestAdvice.DependencyState.UNSATISFIED,
                QuestAdvice.dependencyState(book.quest("0000000000000003").orElseThrow(), progress),
                "前置要全部完成,而第二个只开了工");
        assertEquals(QuestAdvice.DependencyState.SATISFIED,
                QuestAdvice.dependencyState(book.quest("0000000000000004").orElseThrow(), progress),
                "one_started:有一个开了工就算够——只认 all_completed 会把分支任务判死");
        assertEquals(QuestAdvice.DependencyState.SATISFIED,
                QuestAdvice.dependencyState(book.quest("0000000000000007").orElseThrow(), progress),
                "min_required_dependencies=1:两个前置里满足一个就算够");
    }

    @Test
    void withoutAProgressFileDependenciesAreUnknownNotBlocked() {
        QuestDef second = quest("0000000000000002");

        assertEquals(QuestAdvice.DependencyState.UNKNOWN, QuestAdvice.dependencyState(second, null));
        assertFalse(QuestAdvice.isDoable(second, null), "不知道就不敢说能做");
        // 没有依赖的任务仍然照报——存档还没保存,不等于整本书都不能动
        assertTrue(QuestAdvice.isDoable(quest("0000000000000006"), null));
    }

    @Test
    void doableAndInProgressAreDisjointSoNothingIsRecommendedTwice() {
        QuestBook book = book();
        QuestProgress progress = progress();

        List<String> doable = QuestAdvice.doable(book, progress, 0).stream().map(QuestDef::id).toList();
        List<String> inProgress = QuestAdvice.inProgress(book, progress, 0).stream().map(QuestDef::id).toList();

        assertEquals(List.of("0000000000000004", "0000000000000006", "0000000000000007"), doable);
        assertEquals(List.of("0000000000000002"), inProgress);
        assertTrue(doable.stream().noneMatch(inProgress::contains));
    }

    @Test
    void aHiddenTechnicalQuestIsNeverOfferedAsTheNextThingToDo() {
        QuestBook book = book();

        assertFalse(QuestAdvice.isDoable(quest("0000000000000005"), progress()));
        assertTrue(QuestAdvice.doable(book, progress(), 0).stream()
                .noneMatch(q -> q.id().equals("0000000000000005")));
    }

    @Test
    void needsSayHowMuchIsStillMissing() {
        List<QuestAdvice.TaskNeed> needs = QuestAdvice.needs(quest("0000000000000002"), progress());

        assertEquals(1, needs.size());
        assertEquals(4L, needs.get(0).have());
        assertEquals(6L, needs.get(0).remaining());
        assertFalse(needs.get(0).done());
    }

    @Test
    void chapterCountsAddUp() {
        QuestAdvice.ChapterProgress counts = QuestAdvice.chapterProgress(book(), progress()).get(0);

        assertEquals(7, counts.total());
        assertEquals(1, counts.completed(), "开篇");
        assertEquals(1, counts.inProgress(), "第二");
        assertEquals(3, counts.doable(), "分支 / 可选 / 满足一个就算够");
    }

    @Test
    void aBookKnowsWhetherItsObjectivesGateOnDependencies(@TempDir Path questsDir) throws IOException {
        // FTB 的 TeamData.canStartTasks:非 flexible 模式下依赖没完成,任务根本开不动。
        // 我们一律按"依赖满足才算可做"报,所以书是哪一种必须如实带出去。
        Files.createDirectories(questsDir.resolve("chapters"));
        Files.writeString(questsDir.resolve("chapters").resolve("main.snbt"), CHAPTER, StandardCharsets.UTF_8);
        Files.writeString(questsDir.resolve("data.snbt"), """
                {
                	default_quest_shape: "circle"
                	progression_mode: "flexible"
                	title: "&e&l命轮无章"
                }""", StandardCharsets.UTF_8);

        assertEquals("flexible", QuestBook.load(questsDir).progressionMode());
        assertEquals(7, QuestBook.load(questsDir).questCount());

        // data.snbt 不在(或读不动)就用 FTB 自己的默认值 LINEAR,而不是崩掉整本书
        Files.delete(questsDir.resolve("data.snbt"));
        assertEquals(QuestBook.LINEAR, QuestBook.load(questsDir).progressionMode());
    }

    @Test
    void missingDependenciesAreNamedSoTheModelCanSayWhyNotYet() {
        List<String> missing = QuestAdvice.missingDependencies(quest("0000000000000003"), book(), progress());

        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("第二"), missing.get(0));
    }
}
