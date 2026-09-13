package com.dwinovo.numen.plugins.ftbquests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 任务书与队伍进度各自住在哪一块磁盘上——这个问题的<b>唯一</b>答案。
 *
 * <p>两个位置都从 FTB Quests 自己的代码里核实过,不是猜的:
 * <ul>
 *   <li>定义:{@code <config>/ftbquests/quests}(ServerQuestFile.load →
 *       {@code Platform.getConfigFolder().resolve("ftbquests/quests")});</li>
 *   <li>进度:{@code <world>/ftbquests/<队伍id>.snbt}
 *       (ServerQuestFile 里那个 LevelResource("ftbquests") 加
 *       {@code MinecraftServer#getWorldPath});</li>
 *   <li>"谁是哪支队伍":{@code <world>/ftbteams/player/<玩家uuid>.snbt} 的
 *       {@code id} 字段(FTB Teams 写的)。单人时它就是玩家自己的 uuid——整合包装机
 *       核对过,没有队伍文件时按这个兜底。</li>
 * </ul>
 *
 * <p>本类只有 {@link Path} 与文件读写,不碰任何 Minecraft 类型:工具负责把服务端目录
 * 交进来,判据可以脱离游戏环境单测。
 */
public final class QuestFiles {

    private QuestFiles() {}

    public static Path questsDir(Path configDir) {
        return configDir.resolve("ftbquests").resolve("quests");
    }

    public static Path progressDir(Path worldDir) {
        return worldDir.resolve("ftbquests");
    }

    public static Path teamsDir(Path worldDir) {
        return worldDir.resolve("ftbteams");
    }

    /** 目录里的 {@code *.snbt},按文件名排序(顺序稳定,输出才稳定)。目录不在就是空表。 */
    public static List<Path> listSnbt(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                    .forEach(out::add);
        }
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** 一支队伍:队伍 id + 这支队伍里的人叫什么(FTB Teams 的文件里带着)。 */
    public record TeamRef(String teamId, String playerName, String kind) {}

    /**
     * 玩家属于哪支队伍。FTB Teams 为每个登录过的玩家写一份
     * {@code player/<uuid>.snbt},里面 {@code id} 就是队伍 id:单人时等于自己,
     * 组队时等于队伍 id(那时 {@code type} 是 party)。
     *
     * <p>文件不在就按"玩家的队伍就是他自己"处理——FTB 的默认行为如此,而且这份文件
     * 是登出时才写的:主人刚开档就问她任务,这时候文件还没落盘。
     */
    public static Optional<TeamRef> teamOf(Path teamsDir, UUID player) {
        Path file = teamsDir.resolve("player").resolve(player.toString() + ".snbt");
        if (!Files.isRegularFile(file)) {
            return Optional.of(new TeamRef(player.toString(), "", "player"));
        }
        try {
            Map<String, Object> root = Snbt.parseCompound(Files.readString(file, StandardCharsets.UTF_8));
            String teamId = Snbt.str(root, "id", player.toString());
            return Optional.of(new TeamRef(teamId, Snbt.str(root, "player_name", ""),
                    Snbt.str(root, "type", "player")));
        } catch (IOException | RuntimeException e) {
            return Optional.of(new TeamRef(player.toString(), "", "player"));
        }
    }

    /** 读一支队伍的进度。文件名与队伍 id 只差横线大小写,归一化后再比。 */
    public static Optional<QuestProgress> loadProgress(Path progressDir, String teamId) throws IOException {
        String want = normalizeUuid(teamId);
        for (Path file : listSnbt(progressDir)) {
            String name = file.getFileName().toString();
            String bare = name.substring(0, name.length() - ".snbt".length());
            if (!normalizeUuid(bare).equals(want)) continue;
            long writtenAt = Files.getLastModifiedTime(file).toMillis();
            return Optional.of(QuestProgress.parse(
                    Files.readString(file, StandardCharsets.UTF_8), teamId, file, writtenAt));
        }
        return Optional.empty();
    }

    static String normalizeUuid(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }
}
