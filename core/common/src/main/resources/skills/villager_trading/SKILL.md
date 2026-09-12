---
name: villager_trading
description: Turn emeralds into gear by TRADING with villagers - which workstation gives which profession, how levels/restock/prices work, how to get a permanent discount, and the merchant-GUI loop. Load when the owner wants enchanted books (Mending), diamond gear, bulk food or emeralds. This is the merchant side of a village; opening village chests is village_loot, not this.
---

# Skill: villager_trading

Villagers are the only renewable source of **enchanted books an enchanting table cannot
produce** - Mending and every other treasure enchantment. They are also the cheapest route to
diamond armour and to bulk cooked food. This skill is the *merchant* side of a village; the
*chest* side is `village_loot`.

## Pay with emeralds

Emeralds come from: trading itself (renewable), `emerald_ore` / `deepslate_emerald_ore` in
mountain biomes (Y -16..320, densest at Y 232, mineable with iron; Fortune III yields up to 4),
and rarely a fox (1%). So the working loop is: **sell what villagers buy, spend on what they
sell**. Sticks and paper are farmable, and both have buyers - that is the income side.

## Profession = the block the villager has claimed

A villager's trade pool follows its workstation. Break the block: a villager you have **never
traded with** goes unemployed and will claim another workstation (so you can re-roll its
profession); one you **have** traded with keeps its profession and claims a matching block
instead. When the owner says "change his trades", move the workstation, not the villager.

| workstation | profession | why it matters |
|---|---|---|
| `lectern` | librarian | **the reason to build a trading hall**: sells enchanted books, buys paper |
| `composter` | farmer | buys wheat/carrots/potatoes/beetroots, and pumpkin + melon at higher levels; sells food |
| `blast_furnace` | armorer | upgrades into **enchanted diamond armour** |
| `smithing_table` | toolsmith | tools, later enchanted tools |
| `grindstone` | weaponsmith | weapons (the block is also your repair station) |
| `fletching_table` | fletcher | buys **32 sticks for 1 emerald** - the cheapest emeralds in the game |
| `smoker` | butcher | buys raw meat, sells cooked food |
| `barrel` | fisherman | buys fish; a master sells a boat for 1 emerald |
| `cartography_table` | cartographer | buys paper; sells explorer maps (monument / mansion) |
| `brewing_stand` | cleric | the brewing-materials trader |
| `cauldron` | leatherworker | buys leather |
| `loom` | shepherd | buys wool and dyes |
| `stonecutter` | mason | buys stone, sells quartz blocks |

## Levels, restock and price

- **5 levels**: novice, apprentice, journeyman, expert, master - reached at 10 / 70 / 150 / 250
  trading XP. Each level-up unlocks up to 2 new options and keeps the old ones.
- A level-up is only processed **after you close the trade GUI**: trade, `close_gui`, reopen.
- Each option has a limited number of uses and then shows a red x. The villager refills by
  **walking back to its own workstation** - up to **2 refills per option per game day**. Keep the
  workstation reachable and do not carry it away.
- **Prices move.** Repeat-buying one option raises its price (demand); hitting or killing
  villagers raises prices village-wide (reputation); **Hero of the Village** (won by defending a
  raid) discounts everything; a **cured** zombie villager is cheap permanently.
- Selling is the safe direction: buying an option to death raises its price, selling does not
  hurt you the same way.

## The GUI loop

1. `scan_nearby_entities` - the villager's runtime id.
2. `interact_entity(button=right, entity_id=<id>)` - walks over and opens the trade menu.
3. `inspect_gui` - two input slots, one take-only output, plus your own inventory.
4. Load the inputs: `transfer moves=[{from:<your items>, to:<input slot>}, ...]` (one call moves
   all of them; a full stack in the input pre-loads many identical trades).
5. **Take the output** - that single press performs the trade: `transfer moves=[{from:<output slot>}]`.
6. `close_gui`. Reopen if you want to see a level-up.
- Read the transfer's own result text: it says exactly what moved, merged, or was refused. If
  nothing moved, the inputs do not match that offer.

## The discount worth the work: cure a zombie villager

1. A villager killed by a zombie rises as a zombie villager (50% on normal, 100% on hard).
2. Throw a **splash/regular potion of Weakness** on it (see `potion_brewing`) and feed it a
   **golden apple**: `interact_entity(button=right, entity_id=<id>, item_id=minecraft:golden_apple)`.
3. It converts back into a villager whose trades are permanently cheap.
4. **Everything you need is already in an igloo with a basement**:
   `locate_structure("minecraft:igloo")` - the basement holds a brewing stand containing a
   splash weakness potion, a chest guaranteed to contain a golden apple, and a caged zombie
   villager. Cure it on the spot. Note the cured villager may lose its original profession.

## What to actually buy

| Want | Source | Note |
|---|---|---|
| **Mending** and other treasure enchantments | librarian | an enchanting table cannot produce them; a Mending book costs **double** |
| Sharpness / Efficiency / Protection books | librarian | usually cheaper than enchanting your own gear |
| Enchanted diamond armour | armorer | locked behind levelling: trade cheap items until it upgrades |
| Renewable emeralds | fletcher (32 sticks = 1), librarian/cartographer (paper), farmer (crops, later pumpkin/melon), butcher (raw meat) | build the hall around sticks and paper |
| Cooked food | butcher / farmer | no furnace time needed |

## Rules

- Trade only with villagers in the world or ones the owner pointed at. **Never hit a villager**,
  never break their beds or workstations, and never loot the owner's own chests for trade stock
  without permission.
- Villagers do not follow you to the Nether or the End. A trade hall stays where the village is;
  relocating villagers is the owner's project - ask first.
- When you find a librarian selling Mending, **report the village coordinates and the price**.
  That is a landmark the owner will want.

## What to load next

Spending the gear -> `tier_progression`; the books -> `enchant_and_repair`; the weakness potion
-> `potion_brewing`; mining the emeralds -> `underground_mining`.
