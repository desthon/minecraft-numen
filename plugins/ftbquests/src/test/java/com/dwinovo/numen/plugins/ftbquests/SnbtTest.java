package com.dwinovo.numen.plugins.ftbquests;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SNBT 读取器:只对着<b>真实任务书里出现过的写法</b>断言。
 *
 * <p>fixture 全部抄自整合包 {@code config/ftbquests/quests} 下的真文件(命轮无章 promax,
 * FTB Quests 2001.4.17),不是编的:那些"看起来奇怪但 FTB 就是这么写"的地方——键不带引号、
 * 数值带 {@code 10L}/{@code 0.5d} 后缀、条目之间没有逗号、布尔写成 {@code 0b}、
 * 物品 NBT 里藏着 {@code [I; ...]} 数组——才是这个解析器存在的理由。
 */
class SnbtTest {

    @Test
    void readsTheShapeFtbActuallyWrites() {
        Map<String, Object> root = Snbt.parseCompound("""
                {
                	version: 1
                	title: "&e&l命轮无章"
                	grid_scale: 0.5d
                	count: 10L
                	free_to_join: 0b
                	can_repeat: true
                	quests: [{
                		id: "0400FA8EC82BD9ED"
                		x: -87.5d
                	}]
                }""");

        assertEquals("&e&l命轮无章", Snbt.str(root, "title", ""));
        assertEquals(0.5d, ((Number) root.get("grid_scale")).doubleValue(), 1e-9);
        assertEquals(10L, ((Number) root.get("count")).longValue());
        assertEquals(Boolean.FALSE, root.get("free_to_join"), "0b 是 FTB 写布尔的方式");
        assertEquals(Boolean.TRUE, root.get("can_repeat"));
        assertEquals(-87.5d, ((Number) ((Map<?, ?>) Snbt.children(root, "quests").get(0)).get("x")).doubleValue(), 1e-9);
    }

    @Test
    void itemNbtWithAnIntArrayDoesNotBreakTheBook() {
        // 真实写法:物品带 NBT,里面一个 [I; ...] 数组。读不动它 = 整本书读不出来。
        Map<String, Object> root = Snbt.parseCompound("""
                {
                	item: {
                		Count: 1
                		id: "sophisticatedbackpacks:netherite_backpack"
                		tag: {
                			contentsUuid: [I;
                				-975508961
                				-483376495
                			]
                			inventorySlots: 120
                		}
                	}
                }""");

        Map<String, Object> item = Snbt.child(root, "item");
        assertEquals("sophisticatedbackpacks:netherite_backpack", Snbt.str(item, "id", ""));
        assertEquals(1L, Snbt.num(item, "Count", 0L));
        assertEquals(2, Snbt.children(Snbt.child(item, "tag"), "contentsUuid").size());
        assertEquals(120L, Snbt.num(Snbt.child(item, "tag"), "inventorySlots", 0L));
    }

    @Test
    void quotedKeysAndEscapedCharactersSurvive() {
        // 键带引号(FTB Teams 的 "ftbteams:display_name" 就是这么写的);
        // 值里带 SNBT 自己的转义——文本块里的 \\n 是两字符,交给 SNBT 去解成换行
        Map<String, Object> root = Snbt.parseCompound("""
                {
                	"ftbteams:display_name": "desthon"
                	description: "第一行\\n第二行 \\"带引号\\""
                }""");

        assertEquals("desthon", Snbt.str(root, "ftbteams:display_name", ""));
        String description = Snbt.str(root, "description", "");
        assertTrue(description.contains("\n"), description);
        assertTrue(description.contains("\"带引号\""), description);
    }

    @Test
    void numbersWithoutSuffixKeepTheirKind() {
        Map<String, Object> root = Snbt.parseCompound("{ a: 12, b: 12.5, c: minecraft:the_nether }");

        assertEquals(12L, root.get("a"));
        assertEquals(12.5d, root.get("b"));
        assertEquals("minecraft:the_nether", root.get("c"), "无引号字符串里有冒号也不能被截断");
    }

    @Test
    void anUnclosedCompoundSaysWhichLine() {
        Snbt.ParseException ex = assertThrows(Snbt.ParseException.class,
                () -> Snbt.parseCompound("{\n\tid: \"abc\"\n"));

        assertTrue(ex.getMessage().contains("第 3 行"), ex.getMessage());
    }

    @Test
    void anEmptyCompoundAndAnEmptyListAreFine() {
        Map<String, Object> root = Snbt.parseCompound("{ a: { }, b: [ ], c: [ ] }");

        assertTrue(Snbt.child(root, "a").isEmpty());
        assertTrue(Snbt.children(root, "b").isEmpty());
    }
}
