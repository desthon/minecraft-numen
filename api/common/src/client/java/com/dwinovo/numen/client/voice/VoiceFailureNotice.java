package com.dwinovo.numen.client.voice;

/**
 * 语音链路上"该出声却没出声"的通知节流闸门——纯逻辑,无 Minecraft 依赖,可单测。
 * 两类成因共用它,所以口径只有一份:
 * <ul>
 *   <li><b>合成失败</b>(鉴权不对、额度用完、音色 id 写错、网络不通)——合成那一趟炸了;</li>
 *   <li><b>取不到声线</b>(总开关关着、绑定悬空)——压根没走到合成。</li>
 * </ul>
 *
 * <h2>为什么必须通知,而不是只写日志</h2>
 * 这两类在旧实现里都只有一行日志:聊天框里她的文字照常出现,声音却一句没有,
 * 界面上没有任何提示——主人只看得出"她哑了",查不出为什么。它们要上聊天框
 * ({@code ChatLines.notice} 那句 javadoc 说的正是这件事:系统性提醒不能让失败
 * 沉进日志里变成"已读不回")。
 *
 * <h2>为什么要节流</h2>
 * 一条坏声线是"每一句/每一轮都出事":一段回复分七八句、一段对话十几轮,就是十几条
 * 一模一样的提醒,把聊天框刷满,主人反而看不见别的东西。于是按<b>成因源</b>
 * (合成失败用 {@link TtsBackend#describe()},含后端/URL/模型/音色,不含 apiKey;
 * 静音用成因名)记账:同一个源第一次立刻说,之后最多每 {@link #COOLDOWN_MS} 说一次;
 * 换了源(换了声线/改了 URL/换了一种成因)= 立刻又能说。
 */
public final class VoiceFailureNotice {

    /** 同一个成因源最短重复间隔:修好之前句句都出事,不该句句都刷一行。 */
    public static final long COOLDOWN_MS = 60_000L;

    /** 上次说过的成因源(首帧 null = 还没说过任何一次)。 */
    private String lastSource;
    private long lastShownMs;

    /**
     * 这一条该不该让主人看见。
     *
     * @param source 成因源标识(同一配置 = 同一字符串)
     * @param nowMs  当前时间({@code System.currentTimeMillis()};测试传假时钟)
     * @return true = 这一次要说话(并记下这次的时间)
     */
    public boolean shouldShow(String source, long nowMs) {
        String key = source == null ? "" : source;
        if (!key.equals(lastSource)) {
            // 换了源(换了声线/改了 URL):立刻说——主人刚改完配置,正等着看结果
            lastSource = key;
            lastShownMs = nowMs;
            return true;
        }
        if (nowMs - lastShownMs < COOLDOWN_MS) {
            return false;   // 同一口气里的第二句、第三句……只写日志
        }
        lastShownMs = nowMs;
        return true;
    }

    /**
     * 忘掉上一次说过什么——下一次 {@link #shouldShow} 一律立刻通过。
     *
     * <p>什么时候该调:配置从"能出声"变成"出不了声"的那一刻(<b>开/关来回切、
     * 刚被删掉的声线</b>)。这时主人多半正盯着屏幕等反应,让他等满一个冷却期才被告知
     * 就白说了;冷却管的是"同一句话别复读",而这是一次新状态。
     */
    public void reset() {
        lastSource = null;
        lastShownMs = 0L;
    }

    /**
     * 失败原因进聊天行的那一份:短,但保留 HTTP 状态码、服务商原话这类关键信息
     * (它们正是"该去改哪个字段"的线索)。完整原文永远在日志里。
     */
    public static String shorten(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.isEmpty()) return "unknown";
        return r.length() <= 80 ? r : r.substring(0, 80) + "…";
    }
}
