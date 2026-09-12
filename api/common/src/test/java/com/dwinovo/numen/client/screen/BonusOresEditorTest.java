package com.dwinovo.numen.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BonusOresEditor} 的纯逻辑:调用参数的形状、回执的解析、本地判据。
 * 面板本身(布局 / 命中 / 与服务器那一趟)要真机验证,这里只钉住"不碰 Minecraft 的那半边"。
 */
class BonusOresEditorTest {

    // ---- 调用参数 ----

    @Test
    void readCarriesTheActionAndNothingElse() {
        JsonObject args = parse(BonusOresEditor.readArgs());
        assertEquals("read", args.get("action").getAsString());
        assertFalse(args.has("block_ids"), "读名单不该带 block_ids");
    }

    @Test
    void addAndDeleteCarryTheIdInsideABlockIdsArray() {
        JsonObject add = parse(BonusOresEditor.addArgs("minecraft:diamond_ore"));
        assertEquals("add", add.get("action").getAsString());
        assertEquals("minecraft:diamond_ore", add.getAsJsonArray("block_ids").get(0).getAsString());

        JsonObject del = parse(BonusOresEditor.deleteArgs("#minecraft:iron_ores"));
        assertEquals("delete", del.get("action").getAsString());
        assertEquals("#minecraft:iron_ores", del.getAsJsonArray("block_ids").get(0).getAsString());
    }

    @Test
    void clearNeedsNoArguments() {
        JsonObject args = parse(BonusOresEditor.clearArgs());
        assertEquals("clear", args.get("action").getAsString());
        assertFalse(args.has("block_ids"));
    }

    /** 参数用 Gson 拼:主人输入里的引号只能是字符串内容,不能变成 JSON 结构(否则就是一条注入路径)。 */
    @Test
    void userInputCannotBreakOutOfTheJsonString() {
        String nasty = "x\"],\"action\":\"clear";
        JsonObject args = parse(BonusOresEditor.addArgs(nasty));
        assertEquals("add", args.get("action").getAsString(), "输入没能改写 action");
        assertEquals(nasty, args.getAsJsonArray("block_ids").get(0).getAsString());
    }

    @Test
    void surroundingBlanksAreTrimmed() {
        JsonObject args = parse(BonusOresEditor.addArgs("  minecraft:stone  "));
        assertEquals("minecraft:stone", args.getAsJsonArray("block_ids").get(0).getAsString());
    }

    // ---- 回执解析 ----

    @Test
    void parsesTheSuccessReply() {
        String json = "{\"success\":true,\"message\":\"bonus-ore list (2)\",\"data\":{";
        json += "\"bonus_ores\":[\"minecraft:diamond_ore\",\"minecraft:iron_ore\"],";
        json += "\"not_harvestable_with_current_tool\":[\"minecraft:diamond_ore\"]}}";
        BonusOresEditor.Reply r = BonusOresEditor.of(json);
        assertTrue(r.success());
        assertEquals(List.of("minecraft:diamond_ore", "minecraft:iron_ore"), r.ores());
        assertEquals(List.of("minecraft:diamond_ore"), r.needBetterTool());
        assertEquals("bonus-ore list (2)", r.message());
    }

    /** 空名单是成功回执,不是错误——空表 = 一种都不顺路挖,那是主人的真实选择。 */
    @Test
    void parsesTheEmptyReplyAsAnEmptyListNotAnError() {
        String json = "{\"success\":true,\"message\":\"bonus-ore list is empty\",";
        json += "\"data\":{\"bonus_ores\":[]}}";
        BonusOresEditor.Reply r = BonusOresEditor.of(json);
        assertTrue(r.success());
        assertTrue(r.ores().isEmpty());
        assertTrue(r.needBetterTool().isEmpty(), "服务端没报挖不动的那栏,就不该凭空造一栏");
    }

    /** 服务端的拒绝(add / delete 没拿到能用的 id)没有 data 段,照样要能读。 */
    @Test
    void parsesARefusalWithoutData() {
        String json = "{\"success\":false,\"message\":\"add got nothing usable\"}";
        BonusOresEditor.Reply r = BonusOresEditor.of(json);
        assertFalse(r.success());
        assertEquals("add got nothing usable", r.message());
        assertTrue(r.ores().isEmpty());
    }

    @Test
    void unreadableRepliesDegradeInsteadOfThrowing() {
        String[] bad = {null, "", "   ", "not json", "{}", "[1,2]", "{\"success\":nope}"};
        for (String s : bad) {
            BonusOresEditor.Reply r = BonusOresEditor.of(s);
            assertFalse(r.success(), "认不出来就是失败,不能假装成功:" + s);
            assertTrue(r.ores().isEmpty());
        }
    }

    /** data 段不是对象(别人的数据出错):回执本身照样能读出来,名单当空的。 */
    @Test
    void aMalformedDataSectionDoesNotBreakTheReply() {
        BonusOresEditor.Reply r = BonusOresEditor.of("{\"success\":true,\"data\":\"nope\"}");
        assertTrue(r.success());
        assertTrue(r.ores().isEmpty());
    }

    /** 数组里混进 null / 数字 / 空白时只挑能用的,不能让整份名单解析失败(名单是主人的数据)。 */
    @Test
    void skipsJunkInsideTheList() {
        String json = "{\"success\":true,\"data\":{\"bonus_ores\":[";
        json += "\"minecraft:stone\",null,42,\"  \",\"minecraft:coal_ore\"]}}";
        assertEquals(List.of("minecraft:stone", "minecraft:coal_ore"),
                BonusOresEditor.of(json).ores());
    }

    // ---- 本地判据 ----

    @Test
    void listedIgnoresCaseAndPadding() {
        List<String> ores = List.of("minecraft:diamond_ore");
        assertTrue(BonusOresEditor.listed(ores, "Minecraft:Diamond_Ore"));
        assertTrue(BonusOresEditor.listed(ores, "  minecraft:diamond_ore  "));
        assertFalse(BonusOresEditor.listed(ores, "minecraft:iron_ore"));
        assertFalse(BonusOresEditor.listed(ores, "   "), "空输入不算已经在名单上");
        assertFalse(BonusOresEditor.listed(List.of(), "minecraft:diamond_ore"));
    }

    /** 标签与它展开出来的方块不是同一条:删除之后"它还在"就是靠这条判出来的。 */
    @Test
    void aTagIsNotTheSameEntryAsTheBlockItExpandsTo() {
        assertFalse(BonusOresEditor.listed(List.of("minecraft:iron_ore"), "#minecraft:iron_ores"));
        assertTrue(BonusOresEditor.listed(List.of("#minecraft:iron_ores"), "#Minecraft:Iron_Ores"));
    }

    @Test
    void replyKnowsWhetherAnIdIsStillOnTheList() {
        BonusOresEditor.Reply r = BonusOresEditor.of(
                "{\"success\":true,\"data\":{\"bonus_ores\":[\"minecraft:iron_ore\"]}}");
        assertTrue(r.has("MINECRAFT:IRON_ORE"));
        assertFalse(r.has("minecraft:gold_ore"));
    }

    @Test
    void normalizeIsTheSingleWritingRule() {
        assertEquals("minecraft:stone", BonusOresEditor.normalize(" MineCraft:Stone "));
        assertEquals("", BonusOresEditor.normalize(null));
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
