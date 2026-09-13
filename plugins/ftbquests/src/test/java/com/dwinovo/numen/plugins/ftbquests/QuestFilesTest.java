package com.dwinovo.numen.plugins.ftbquests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁盘布局:这两条路径是从 FTB Quests 自己的代码里核实来的,不是猜的——
 * {@code <config>/ftbquests/quests} 放定义,{@code <world>/ftbquests/<队伍id>.snbt} 放进度,
 * {@code <world>/ftbteams/player/<uuid>.snbt} 决定谁属于哪支队伍。这里的 fixture
 * 照抄真实存档(新的世界 / 薄暮乡)的目录形状与文件内容。
 */
class QuestFilesTest {

    private static final UUID OWNER = UUID.fromString("6d596261-bea9-413a-b41b-a12bbc267abd");

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void whereTheBookAndTheProgressLive(@TempDir Path root) {
        assertEquals(root.resolve("ftbquests").resolve("quests"), QuestFiles.questsDir(root));
        assertEquals(root.resolve("ftbquests"), QuestFiles.progressDir(root));
        assertEquals(root.resolve("ftbteams"), QuestFiles.teamsDir(root));
    }

    @Test
    void aSoloTeamIsThePlayerItselfWhenFtbTeamsHasNoFileYet(@TempDir Path world) {
        Optional<QuestFiles.TeamRef> team = QuestFiles.teamOf(QuestFiles.teamsDir(world), OWNER);

        assertTrue(team.isPresent());
        assertEquals(OWNER.toString(), team.get().teamId());
    }

    @Test
    void theTeamIdComesFromFtbTeamsAndTheProgressFileIsFoundByNormalizedUuid(@TempDir Path world) throws IOException {
        // 真实存档:玩家文件里 id 是队伍 id(带横线小写),进度文件名同款,但两边大小写/横线并不保证一致
        write(QuestFiles.teamsDir(world).resolve("player").resolve(OWNER + ".snbt"), """
                {
                	id: "6d596261-bea9-413a-b41b-a12bbc267abd"
                	type: "player"
                	player_name: "desthon"
                	ranks: {
                		6d596261-bea9-413a-b41b-a12bbc267abd: "owner"
                	}
                	extra: { }
                }""");
        write(QuestFiles.progressDir(world).resolve("6D596261BEA9413AB41BA12BBC267ABD.snbt"), """
                {
                	version: 1
                	uuid: "6d596261bea9413ab41ba12bbc267abd"
                	name: "desthon#6d596261"
                	task_progress: {
                		39C4C87161CF0B87: 4
                	}
                	started: {
                		3DDAE6237043CFE2: 1782958207133L
                	}
                	completed: {
                		072A574EABD2B7A1: 1782958207138L
                	}
                	player_data: { }
                }""");

        QuestFiles.TeamRef team = QuestFiles.teamOf(QuestFiles.teamsDir(world), OWNER).orElseThrow();
        assertEquals("6d596261-bea9-413a-b41b-a12bbc267abd", team.teamId());
        assertEquals("desthon", team.playerName());

        QuestProgress progress = QuestFiles.loadProgress(QuestFiles.progressDir(world), team.teamId()).orElseThrow();
        assertTrue(progress.isCompleted("072a574eabd2b7a1"), "id 大小写不该影响判定");
        assertTrue(progress.isStarted("3DDAE6237043CFE2"));
        assertFalse(progress.isCompleted("3DDAE6237043CFE2"));
        assertEquals(4L, progress.progressOf("39c4c87161cf0b87"));
        assertEquals("desthon#6d596261", progress.teamName());
    }

    @Test
    void aTeamWithoutAProgressFileIsNotAnErrorJustAnAbsentOptional(@TempDir Path world) throws IOException {
        assertTrue(QuestFiles.loadProgress(QuestFiles.progressDir(world), OWNER.toString()).isEmpty());
    }
}
