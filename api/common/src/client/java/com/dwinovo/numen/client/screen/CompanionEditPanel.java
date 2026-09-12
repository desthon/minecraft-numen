package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.CompanionHome;
import com.dwinovo.numen.client.skin.SkinLibrary;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Dropdown;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.voice.VoiceLibrary;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.persona.PersonaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 编辑卡——点当前激活头像打开,改一只<b>已创建</b>同伴:人设/模式/模型配置/声线/
 * 皮肤五个选择,外加一页「顺路挖矿」名单。草稿制:下拉只改草稿,点保存才统一落地,
 * 且只发真正变过的项(换肤要原地重建身体,误触代价高);取消丢弃草稿。草稿基线在
 * 开卡时从各自的真源取一次({@link #reset()});皮肤的当前选择记在绑定里(与档案/声线同模)。
 * 遣散收成右上角垃圾桶图标(悬停危险色),底部只留取消/保存一对主动作。
 *
 * <h2>顺路挖矿为什么是"子页 + 即时落盘"</h2>
 * 名单的真源在<b>服务端</b>(随存档落盘),能读写它的现成通道只有 {@code bonus_ores}
 * 工具(理由见 {@link BonusOresEditor})。它就必然要走一趟网络:放不进"下拉只改草稿"
 * 那套里,因为草稿制的前提是"值就在本地,保存只是提交一次",而这里值根本不在本地。
 * 于是给它一整页:进去读一次、增删各发一次调用、回执回来照着重画——所见即服务端的那份。
 */
public final class CompanionEditPanel {

    /** 屏幕侧的面:身份、网络动作与关卡。 */
    public interface Host {
        java.util.UUID uuid();

        String name();

        /** 关卡并弹遣散确认(危险操作的闸在屏幕层)。 */
        void onDismiss();

        void onClose();

        /** 与服务端 applyGameMode 的门同一判据:有 gamemode 权限或主人在创造。 */
        boolean canChooseMode();

        /** 名册推来的此刻模式。 */
        boolean currentCreative();

        void setCreative(boolean creative);

        /** {@code skinId} 是皮肤库条目 id,或 {@link #SKIN_BY_NAME}(按名字查同名正版)。 */
        void applySkin(String skinId);

        /** 面板自己换了页/刷新了数据:请宿主重建面板(控件与真输入框都归屏幕管)。 */
        void rebuildPanel();
    }

    private static final String PERSONA_NONE = "__default__";
    /** 与 {@link VoiceChoices#NONE} 同义;抽到那边是为了能单测"下标永远算得回来"。 */
    private static final String VOICE_NONE = VoiceChoices.NONE;
    /** 顺路挖矿子页每行的高与行尾 ✕ 的热区宽。 */
    private static final int ORE_ROW_H = 15;
    private static final int ORE_DEL_ZONE = 14;
    /** 与召唤卡的"默认(按名字)"同义:本机查同名正版,查不到落回原版默认皮肤。 */
    public static final String SKIN_BY_NAME = "__default__";

    /** 编辑草稿;开卡时从真源取基线,保存时与基线比对只落差异。 */
    private static final class Draft {
        String personaId;     // null = 默认人设
        String providerId;    // null = 未绑定(只出现在老档,不许改回)
        String voiceId;       // null = 无声
        boolean creative;
        String skinId = SKIN_BY_NAME;
    }

    private final UiRoot ui = new UiRoot();
    private final Host host;

    private Draft draft = new Draft();
    private String origPersona, origProvider, origVoice, origSkin;
    private boolean origCreative;
    private int trashX, trashY;

    private List<String> personaIds = List.of();
    private List<String> providerIds = List.of();
    private List<String> voiceIds = List.of();
    private List<String> skinIds = List.of();
    private int modeBoxX, modeBoxY, modeBoxW;
    private boolean modeLocked;
    /** 声线那一格下面的说明行(库空 / 总开关关掉时才画);-1 = 本帧不画。 */
    private String voiceHint;
    private int voiceHintX, voiceHintY = -1, voiceHintW;

    // ---- 顺路挖矿子页:服务端名单在客户端的镜面(见类注释与 BonusOresEditor) ----
    /** 卡内换页,不新开模态:它和卡上那五个选择是同一只同伴的配置。 */
    private boolean oresPage;
    /** 生效中的名单(标签已展开),来自最近一次成功回执。 */
    private final List<String> ores = new ArrayList<>();
    /** 清单里她当下挖不动的那几种(回执另报的一栏),只在状态行里提一句。 */
    private final List<String> oresNeedTool = new ArrayList<>();
    /** 状态行:空态说明 / 服务端原话 / 本地校验,空则按名单本身概括。 */
    private String oresNote;
    private boolean oresLoading;
    /** 主卡那行显示的条数;-1 = 还没读到过(不假装是 0)。 */
    private int oresCount = -1;
    private TextField oresField;
    /** 输入框内容跨重建保存在这里:每次刷新都要重建控件树。 */
    private String oresInput = "";
    private int oresRowW;
    /** 状态行的位置(两行,由面板自绘折行——服务端那句原话可能很长,标签一行装不下)。 */
    private int oresStatusX, oresStatusY1, oresStatusY2;

    public CompanionEditPanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                s -> mc.keyboardHandler.setClipboard(s));
        // 顺路挖矿那页要一个输入框(填方块 id / #标签),和召唤卡同一个口:编辑交给真
        // EditBox 只为了让输入法认得出来(见 McTextInput),画面仍归 NumenUI。
        ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
    }

    /** 每次开卡:草稿从当下真相取一次基线。 */
    public void reset() {
        var uuid = host.uuid();
        var loop = AgentLoopRegistry.getOrCreate(uuid);
        var binding = CompanionHome.binding(uuid);
        origPersona = loop.personaId();
        origProvider = binding.providerId();
        origVoice = binding.voiceId();
        origCreative = host.currentCreative();
        // 皮肤当前选择从绑定读;没记账或条目已删 = 按名字默认。
        String sk = binding.skinId();
        origSkin = sk != null && SkinLibrary.instance().get(sk) != null ? sk : SKIN_BY_NAME;
        draft = new Draft();
        draft.personaId = origPersona;
        draft.providerId = origProvider;
        draft.voiceId = origVoice;
        draft.creative = origCreative;
        draft.skinId = origSkin;
        // 顺路挖矿子页归零并读一次:主卡那行要显示"她现在顺手挖几种",而这答案只存在于
        // 服务端。一次小查询(不占身体、不进对话),开卡时问一次是它该有的代价。
        oresPage = false;
        ores.clear();
        oresNeedTool.clear();
        oresNote = null;
        oresLoading = false;
        oresCount = -1;
        oresInput = "";
        oresField = null;
        readBonusOres();
    }

    public void build(int x, int y, int w, int h, int dropBottom) {
        PersonaLibrary.instance().reload();   // 人设目录可能刚被增删,和召唤卡一样重扫
        VoiceLibrary.instance().reload();     // 声线库同理:库是文件,库外的一切改动都该看得见
        ui.clear();
        ui.setViewportHeight(dropBottom);
        if (oresPage) {   // 子页:整卡换页,卡框与暗幕由屏幕画
            buildOresPage(x, y, w, h);
            return;
        }

        int half = (w - 6) / 2;
        int ry = y;
        // 头像由屏幕画在标题左侧(面板不碰 GuiGraphics),文字给它让出 24px。
        Label title = ui.add(new Label(
                t(ModLanguageData.Keys.EDIT_TITLE) + " · " + host.name(), Label.Role.PRIMARY));
        title.setBounds(x + 24, ry + 5, w - 24, 9);
        trashX = x + w - 12;
        trashY = ry + 3;
        ry += 24;

        // 人设 | 模式
        int rowY = label(x, ry, ModLanguageData.Keys.SUMMON_PERSONA_LABEL);
        label(x + half + 6, ry, "numen.summon.mode");
        List<String> personaNames = new ArrayList<>();
        List<String> pIds = new ArrayList<>();
        pIds.add(PERSONA_NONE);
        personaNames.add(t(ModLanguageData.Keys.SUMMON_PERSONA_NONE));
        for (PersonaLibrary.Persona p : PersonaLibrary.instance().list()) {
            pIds.add(p.id());
            personaNames.add(p.name());
        }
        personaIds = pIds;
        String curPersona = draft.personaId == null ? PERSONA_NONE : draft.personaId;
        Dropdown personaPick = ui.add(new Dropdown(personaNames,
                Math.max(0, personaIds.indexOf(curPersona)),
                i -> {
                    String id = personaIds.get(i);
                    draft.personaId = PERSONA_NONE.equals(id) ? null : id;
                }));
        personaPick.setBounds(x, rowY, half, NumenStyle.CONTROL_H);

        modeLocked = !host.canChooseMode();
        if (modeLocked) {
            // 改不了(权限门在服务端也是同一道):画成置灰格,悬停给解释。
            modeBoxX = x + half + 6;
            modeBoxY = rowY;
            modeBoxW = half;
        } else {
            Dropdown modePick = ui.add(new Dropdown(
                    List.of(t(ModLanguageData.Keys.SUMMON_MODE_SURVIVAL),
                            t(ModLanguageData.Keys.SUMMON_MODE_CREATIVE)),
                    draft.creative ? 1 : 0, i -> draft.creative = i == 1));
            modePick.setBounds(x + half + 6, rowY, half, NumenStyle.CONTROL_H);
        }
        ry = rowY + NumenStyle.ROW_PITCH;

        // 模型配置 | 声线
        int rowY2 = label(x, ry, ModLanguageData.Keys.PROVIDER_TITLE);
        label(x + half + 6, ry, ModLanguageData.Keys.VOICE_SUMMON_LABEL);
        List<String> provNames = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (draft.providerId == null) {
            // 老档没绑过:占位首项如实显示,选中任一真档案即入草稿(不许改回"未绑定")。
            ids.add("");
            provNames.add(t(ModLanguageData.Keys.EDIT_PROVIDER_UNBOUND));
        }
        for (var e : ProviderLibrary.instance().list()) {
            ids.add(e.id());
            provNames.add(e.name());
        }
        providerIds = ids;
        if (!ids.isEmpty()) {
            Dropdown provPick = ui.add(new Dropdown(provNames,
                    Math.max(0, providerIds.indexOf(draft.providerId == null ? "" : draft.providerId)),
                    i -> {
                        String id = providerIds.get(i);
                        if (!id.isEmpty()) draft.providerId = id;
                    }));
            provPick.setBounds(x, rowY2, half, NumenStyle.CONTROL_H);
        }
        // 声线:恒出下拉(库里一条都没有时它只有"无(静音)"一项)。
        // 旧行为是"库空就整格不画"——主人看见标签底下空着、没有任何解释,报告就是
        // "音色选不了"。选不了可以有,但必须说清为什么、去哪儿配(见下面的提示行)。
        var voiceEntries = VoiceLibrary.instance().list();
        var voices = VoiceChoices.of(voiceEntries, draft.voiceId,
                t(ModLanguageData.Keys.VOICE_BIND_NONE));
        voiceIds = voices.ids();
        Dropdown voicePick = ui.add(new Dropdown(voices.names(), voices.selected(), i -> {
            String id = voiceIds.get(i);
            draft.voiceId = VOICE_NONE.equals(id) ? null : id;
        }));
        voicePick.setBounds(x + half + 6, rowY2, half, NumenStyle.CONTROL_H);

        ry = rowY2 + NumenStyle.ROW_PITCH;
        // 库里没条目、或语音总开关关着——两种"选了她也不会出声"的情形,各给一句话。
        // 总开关那条尤其要紧:声线绑了、开关关着,界面上一切正常却永远沉默。
        voiceHint = null;
        voiceHintY = -1;
        if (voiceEntries.isEmpty()) {
            voiceHint = "No voice yet - add one in Settings > Voice.";
        } else if (!VoiceLibrary.instance().enabled()) {
            voiceHint = "Voice is off - turn it on in Settings > Voice.";
        }
        if (voiceHint != null) {
            // 整行宽:半行宽装不下"去哪儿配"那半句,而那句话正是这条提示的全部意义
            voiceHintX = x;
            voiceHintW = w;
            voiceHintY = ry - 3;   // 紧贴下拉底缘,占的是它下面那点空
            ry += 10;              // 说明行占一行:下面的行整体让位,谁都别压谁
        }

        // 皮肤:整行宽,默认选中当前穿的(绑定里记的选择意图);
        // 保存时只有真换了才发包(换肤要原地重建身体)。
        ry = label(x, ry, ModLanguageData.Keys.SUMMON_SKIN);
        List<String> skinNames = new ArrayList<>();
        List<String> sIds = new ArrayList<>();
        sIds.add(SKIN_BY_NAME);
        skinNames.add(t(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT));
        for (var e : SkinLibrary.instance().list()) {
            if (e.signed()) {
                sIds.add(e.id());
                skinNames.add(e.name());
            }
        }
        skinIds = sIds;
        Dropdown skinPick = ui.add(new Dropdown(skinNames,
                Math.max(0, skinIds.indexOf(draft.skinId)),
                i -> draft.skinId = skinIds.get(i)));
        skinPick.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH + 4;

        // 顺路挖矿:整行一个入口,文案里带当前条数(点开是卡内子页,见 buildOresPage)。
        Button oresOpen = ui.add(new Button(oresRowLabel(), Button.Style.NORMAL, this::openOresPage));
        oresOpen.setBounds(x, ry, w, NumenStyle.CONTROL_H);
        ry += NumenStyle.ROW_PITCH;

        int bw = 64, gap = 8;
        int bx = x + (w - (bw * 2 + gap)) / 2;
        Button cancel = ui.add(new Button(t("numen.gui.settings.cancel"),
                Button.Style.NORMAL, host::onClose));
        cancel.setBounds(bx, ry, bw, 16);
        Button save = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_SAVE),
                Button.Style.ACCENT, this::save));
        save.setBounds(bx + bw + gap, ry, bw, 16);
    }

    // ---- 顺路挖矿子页 ----

    /** 主卡那行的文案。条数 = 她现在会顺手挖几种;还没读到过就是未知,不假装是 0。 */
    private String oresRowLabel() {
        String n = oresLoading ? "..." : oresCount < 0 ? "?" : oresCount == 0 ? "none"
                : String.valueOf(oresCount);
        return "Bonus ores: " + n + "  ▸";
    }

    /**
     * 子页:顺路挖矿名单。<b>卡内换页</b>而不是新开一层模态——它和卡上那五个选择是
     * 同一只同伴的配置,再开一层只多一层 Esc 语义,主人还得记住自己在第几层。
     *
     * <p>这一页的改动<b>即时落盘</b>,不走卡的草稿制:草稿制的前提是"值在本地,
     * 保存只是提交一次",而这份名单的真源在服务端——存一份本地草稿只会得到两份
     * 互相打架的状态(主人点了保存的到底是谁)。所以这里所见即服务端回执的那份。
     */
    private void buildOresPage(int x, int y, int w, int h) {
        int ry = y;
        trashX = x + w - 12;   // 遣散垃圾桶在子页照样在(逃生口不该被换页收走)
        trashY = ry + 3;
        Button back = ui.add(new Button("< Back", Button.Style.NORMAL, this::closeOresPage));
        back.setBounds(x, ry, 60, 16);
        Label title = ui.add(new Label("Bonus ores", Label.Role.PRIMARY));
        title.setBounds(x + 66, ry + 4, Math.max(40, w - 66 - 84), 9);
        // 清空:右缘留出 16px,别压上右上角遣散垃圾桶的热区(它先判命中,压上去就是误触)
        Button clear = ui.add(new Button("Clear list", Button.Style.NORMAL, this::clearOres));
        clear.setBounds(x + w - 78, ry, 62, 16);
        ry += 20;
        // 两行说明各自短到一行装得下(Label 超宽会截断,说明被截掉就等于没说)。
        Label hint1 = ui.add(new Label("Saved right away - it lives on the server.", Label.Role.MUTED));
        hint1.setBounds(x, ry, w, 9);
        ry += 10;
        Label hint2 = ui.add(new Label("Mined when she passes within 24 blocks of a job.", Label.Role.MUTED));
        hint2.setBounds(x, ry, w, 9);
        ry += 12;

        // 底下一行是输入框 + 添加;它上面留两行给状态行(空态/服务端原话都住那儿,
        // 由 render 自己折行——那些句子比一行宽)。名单列表占剩下的地方。
        int inputY = y + h - 22;
        oresStatusX = x;
        oresStatusY2 = inputY - 12;
        oresStatusY1 = oresStatusY2 - 10;
        oresRowW = w;
        ListView<String> list = ui.add(new ListView<>(ores, ORE_ROW_H, this::renderOreRow, null)
                .rowClick(this::oreRowClicked));
        list.setBounds(x, ry, w, Math.max(ORE_ROW_H, oresStatusY1 - ry - 4));
        oresField = ui.add(new TextField(oresInput, v -> oresInput = v)
                .placeholder("minecraft:diamond_ore or #minecraft:iron_ores"));
        oresField.setBounds(x, inputY, w - 62, NumenStyle.CONTROL_H);
        Button add = ui.add(new Button("Add", Button.Style.ACCENT, this::addOre));
        add.setBounds(x + w - 56, inputY, 56, NumenStyle.CONTROL_H);
    }

    /**
     * 状态行:<b>空态是说明不是报错</b>。名单空 = "一种都不顺路挖",那是主人可以做
     * 的真实选择(见 BonusOres 的类注释),所以它读起来要像一句交代,不像一次失败。
     */
    private String oresStatusText() {
        if (oresLoading) return "Reading the list from the server...";
        if (oresNote != null) return oresNote;
        if (ores.isEmpty()) {
            return "Nothing on the list yet - she mines nothing she merely walks past.";
        }
        String base = ores.size() + (ores.size() == 1 ? " ore" : " ores") + " picked up on the way";
        if (!oresNeedTool.isEmpty()) {
            base += " - " + oresNeedTool.size() + " of them need a better tool";
        }
        return base;
    }

    private void renderOreRow(IDrawSurface s, NumenTheme.Colors c, String id, int index,
                              int rx, int ry, int rw, int rh, boolean selected, boolean hovered) {
        s.drawText(TextClip.fit(s, id, rw - ORE_DEL_ZONE - 8), rx + 4, ry + 3,
                c.textPrimary(), false);
        // 行尾 ✕ = 删这一条(热区见 oreRowClicked);悬停整行就亮,不必精确压到那个字上
        s.drawText("✕", rx + rw - 11, ry + 3, hovered ? c.danger() : c.textMuted(), false);
    }

    /** 行内点击:只有右侧 ✕ 热区有动作,行体什么都不做(点一下不该动服务端的数据)。 */
    private boolean oreRowClicked(int index, double xInRow) {
        if (index >= 0 && index < ores.size() && xInRow >= oresRowW - ORE_DEL_ZONE) {
            deleteOre(ores.get(index));
        }
        return true;   // 行体也吞掉:点空白处不该落到别的控件上
    }

    private void openOresPage() {
        oresPage = true;
        readBonusOres();   // 每次进页都读一次:这份名单模型那边也在改,不吃旧镜面
    }

    private void closeOresPage() {
        oresPage = false;
        host.rebuildPanel();
    }

    // ---- 顺路挖矿:与服务器之间的那一趟 ----

    private void readBonusOres() {
        callBonusOres(BonusOresEditor.readArgs(), null);
    }

    /**
     * 发一次 {@code bonus_ores} 调用,回执回客户端主线程再重画。
     *
     * @param deleting 这次是不是在删某一条(传它的 id)。回执里它<b>还在</b> = 它来自
     *                 一条 {@code #标签}:服务端存的是那条标签,逐条删删不掉它。
     *                 这时要如实说明,而不是假装删掉了(见 applyOresReply)。
     * @return 这一趟真的发出去了(没连上 / 没有同伴 = false,调用方据此决定要不要动界面上别的东西)
     */
    private boolean callBonusOres(String argsJson, String deleting) {
        final java.util.UUID target = host.uuid();
        if (target == null) {
            return false;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            oresLoading = false;
            oresNote = "Not connected - the bonus-ore list lives on the server.";
            host.rebuildPanel();
            return false;
        }
        oresLoading = true;
        oresNote = null;
        host.rebuildPanel();
        NumenActuator.invoke(target, BonusOresEditor.TOOL, argsJson)
                .thenAccept(json -> Minecraft.getInstance().execute(() -> {
                    // 异步窗口里可能已经切了同伴:这份回执只属于点它的那一只
                    if (!target.equals(host.uuid())) return;
                    applyOresReply(BonusOresEditor.of(json), deleting);
                }));
        return true;
    }

    private void applyOresReply(BonusOresEditor.Reply reply, String deleting) {
        oresLoading = false;
        if (!reply.success()) {
            // 失败不改显示:上一份好数据比一片空白更接近真相,解释放状态行
            oresNote = reply.message() == null || reply.message().isBlank()
                    ? "The server did not answer the bonus-ores call." : reply.message();
            host.rebuildPanel();
            return;
        }
        ores.clear();
        ores.addAll(reply.ores());
        oresNeedTool.clear();
        oresNeedTool.addAll(reply.needBetterTool());
        oresCount = ores.size();
        oresNote = null;
        if (deleting != null && reply.has(deleting)) {
            oresNote = "Still listed: it comes from a #tag. Clear the list and add the ores"
                    + " you want one by one.";
        }
        host.rebuildPanel();
    }

    private void addOre() {
        String typed = oresField != null ? oresField.value() : oresInput;
        String id = BonusOresEditor.normalize(typed);
        if (id.isEmpty()) {
            oresNote = "Type a block id or a #tag first, e.g. minecraft:diamond_ore.";
            host.rebuildPanel();
            return;
        }
        if (BonusOresEditor.listed(ores, id)) {
            // 已经在生效名单里了:再 add 一次服务端会多存一份(标签 + 具体 id),镜面却不变。
            // 拦在本地,免得主人以为"加了没反应"。
            oresNote = "'" + id + "' is already on the list.";
            host.rebuildPanel();
            return;
        }
        // 发出去了才清空输入框:连不上/被服务端拒(认不出这个 id)时,打的那串要留着
        // 让主人改一个字再试;回执一到会重建控件,那时输入框才真的空。
        if (callBonusOres(BonusOresEditor.addArgs(id), null)) {
            oresInput = "";
        }
    }

    private void deleteOre(String id) {
        callBonusOres(BonusOresEditor.deleteArgs(id), id);
    }

    private void clearOres() {
        callBonusOres(BonusOresEditor.clearArgs(), null);
    }

    /** 保存:与开卡基线比对,只落真正变过的项,然后关卡。 */
    private void save() {
        var uuid = host.uuid();
        if (!Objects.equals(draft.personaId, origPersona)) {
            AgentLoopRegistry.getOrCreate(uuid).setPersona(draft.personaId);
        }
        if (draft.providerId != null && !draft.providerId.equals(origProvider)) {
            AgentLoopRegistry.getOrCreate(uuid).setProviderEntry(draft.providerId);
        }
        if (!Objects.equals(draft.voiceId, origVoice)) {
            CompanionHome.bind(uuid, CompanionHome.binding(uuid).withVoice(draft.voiceId));
        }
        if (!modeLocked && draft.creative != origCreative) {
            host.setCreative(draft.creative);
        }
        if (!draft.skinId.equals(origSkin)) {
            host.applySkin(draft.skinId);
            CompanionHome.bind(uuid, CompanionHome.binding(uuid)
                    .withSkin(SKIN_BY_NAME.equals(draft.skinId) ? null : draft.skinId));
        }
        host.onClose();
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        // 右上角垃圾桶(遣散):平时低调,悬停亮危险色;点击仍过确认卡,误触有闸。
        int tc = overTrash(mouseX, mouseY) ? c.danger() : c.textMuted();
        s.fillRect(trashX + 3, trashY, 5, 1, tc);        // 提手
        s.fillRect(trashX, trashY + 1, 11, 2, tc);       // 盖
        s.fillRect(trashX + 1, trashY + 4, 9, 8, tc);    // 桶身
        if (modeLocked && !oresPage) {   // 置灰的当前档(不是控件:点不了才是本意)
            NumenStyle.fieldCard(s, modeBoxX, modeBoxY, modeBoxW, NumenStyle.CONTROL_H,
                    c.sectionBg(), c.inputBorder());
            s.drawText(t(draft.creative ? ModLanguageData.Keys.SUMMON_MODE_CREATIVE
                            : ModLanguageData.Keys.SUMMON_MODE_SURVIVAL),
                    modeBoxX + 5, modeBoxY + (NumenStyle.CONTROL_H - s.lineHeight()) / 2 + 1,
                    c.textMuted(), false);
        }
        if (voiceHint != null && !oresPage) {
            // 空态说明画在声线下拉正下方(它占的那一行已在 build 里让出来了)
            s.drawText(TextClip.fit(s, voiceHint, voiceHintW), voiceHintX, voiceHintY,
                    c.textMuted(), false);
        }
        if (oresPage) {
            // 状态行折成最多两行自绘:Label 只有一行,服务端那句原话与空态说明都会被截断,
            // 而这两句恰恰是"为什么没有名单/为什么没加上"的全部解释。
            int ly = oresStatusY1;
            for (String line : com.dwinovo.numen.client.ui.TextWrap.wrap(
                    oresStatusText(), oresRowW, s::textWidth, 2)) {
                s.drawText(line, oresStatusX, ly, c.textMuted(), false);
                ly += 10;
            }
        }
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 悬停提示(宿主画 tooltip):垃圾桶报遣散,置灰模式格报锁因。 */
    public String tooltipAt(double mx, double my) {
        if (overTrash(mx, my)) {
            return t(ModLanguageData.Keys.EDIT_DISMISS);
        }
        if (!modeLocked || oresPage) return null;
        boolean over = mx >= modeBoxX && mx < modeBoxX + modeBoxW
                && my >= modeBoxY && my < modeBoxY + NumenStyle.CONTROL_H;
        return over ? t(ModLanguageData.Keys.EDIT_MODE_LOCKED) : null;
    }

    private boolean overTrash(double mx, double my) {
        return mx >= trashX - 1 && mx < trashX + 12 && my >= trashY - 1 && my < trashY + 13;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (overTrash(mx, my)) {
            host.onDismiss();
            return true;
        }
        return ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        // 子页里 Enter = 添加:输入框是"填一条就交"的语义,手不用离开键盘去点按钮。
        // 焦点不在输入框上时也照收——这一页只有这一个输入框,没有歧义。
        if (oresPage && keyCode == com.dwinovo.numen.client.ui.KeyCodes.ENTER
                && !ui.hasOverlay()) {
            addOre();
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    private int label(int lx, int ly, String key) {
        Label l = ui.add(new Label(t(key), Label.Role.MUTED));
        l.setBounds(lx, ly, 200, 9);
        return ly + NumenStyle.LABEL_PITCH;
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
