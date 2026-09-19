---
name: enchant_and_repair
description: Enchant your own gear at an enchanting table with the enchant tool, and repair / combine / apply books / rename on an anvil with the anvil tool - plus where the table CANNOT help (treasure enchantments: fishing, librarians, loot) and the durability maths (Unbreaking, Mending, the prior-work penalty, the 40-level "Too Expensive" cap, the 39 rename clamp, the 50-character name limit). Load when gear is wearing out, when you want an enchantment on something you own, or before the dragon fight gear check.
---

# Skill: enchant_and_repair

Two separate problems that people lump together: **putting enchantments on your own gear** (the
enchanting table, and the anvil that moves enchantments and names around) and **keeping gear
alive** (durability, repair cost, and when to retire a tool).

Both machines have a step that is **not a slot** — which is why `transfer` alone can never finish
the job, and why these two tools exist:

| machine | the step that is not a slot | the tool |
|---|---|---|
| enchanting table | the three offers are **menu buttons** | `enchant` |
| anvil | the **name field** (a text box, not a slot) | `anvil` |

## Enchanting yourself: the `enchant` tool

```
enchant(item_id="minecraft:diamond_pickaxe")            # read the three offers, spend nothing
enchant(item_id="minecraft:diamond_pickaxe", tier=3)    # then take one
```

It finds a table within ~16 blocks (walking there is cheaper than building one — see the cost
below), or places / crafts a table of its own and takes it back afterwards, opens it, puts your
item and the lapis in, reads the offers, presses the one you asked for, **takes the enchanted item
back into your pack**, and reports the levels and lapis actually spent. Call it **without** `tier`
first: the reply lists the three offers (their level cost and a clue enchantment), what you have,
and exactly how many levels and lapis you are short — **nothing is spent**. Then call it again with
the tier you want.

The same item sees the **same three offers** every time until you actually enchant something (the
offers are rolled from your enchantment seed, and only a successful enchant re-rolls it), so
reading first and choosing afterwards is safe.

**The numbers that matter (vanilla 1.20.1, verified against the game code):**

- **The cost shown is a requirement, not a price.** Taking offer 3 with "30 levels" written on it
  requires you to *be* level 30 and **spends 3 levels**. Offers cost 1 / 2 / 3 levels respectively.
- **Lapis**: offer 1 needs 1, offer 2 needs 2, offer 3 needs 3 lapis lazuli **in the table**.
- **Bookshelves cap at 15 counted.** Offer 3's level cost is at least **2 × bookshelves** (so 15
  shelves = a guaranteed 30-level offer; 3 shelves = a floor of 6). Fewer shelves means weaker
  offers, not "no offer".
- **The table only takes an unenchanted, single, damageable item** (or a book). Already-enchanted
  gear has **no offers at all** — to add to it, use the anvil with a book. The tool refuses up
  front rather than walking you there for nothing.
- **Building a table costs 4 obsidian + 2 diamonds + 1 book** (a 3×3, so a crafting table first).
  The tool judges whether that is worth it, and when you cannot afford it the reply names each
  missing piece instead of quietly walking off.

## What to ask the table for

| item | ask for |
|---|---|
| pickaxe | **Efficiency** (speed), **Fortune** *or* **Silk Touch** (mutually exclusive) |
| sword | **Sharpness** *or* **Smite** *or* **Bane of Arthropods** (mutually exclusive; Smite for undead, Bane for spiders) |
| bow | Power, Infinity (bring your own arrows anyway) |
| armour | Protection; **Feather Falling** on the boots |
| any gear you rely on | **Unbreaking** |
| ocean work | Respiration + Aqua Affinity on the helmet |
| fishing rod | Luck of the Sea (more treasure), Lure (faster) |

The table **never** gives treasure enchantments. **Mending** and the other treasure enchantments
come from somewhere else — that part has not changed:

1. **Fish for enchanted books.** Your `fish` tool is the renewable source you control. Fishing
   gives **85% fish / 10% junk / 5% treasure**, and the treasure table is six equally likely items -
   one of them is an **enchanted book**. Those books are **level 30 and can carry treasure
   enchantments, Mending included**, which no enchanting table can produce. Treasure only comes
   from **open water** (the 5x4x5 column at the bobber must be water, or air/water-lily above
   water); in a puddle you only ever catch fish and junk. Luck of the Sea raises the treasure rate.
2. **Buy them from a librarian** (see `villager_trading`). A librarian is far more reliable than
   fishing for one specific book; Mending costs double there.
3. **Loot chests.** Mineshafts, strongholds (the library), shipwrecks, desert/jungle temples and
   nether structures all carry books and enchanted gear.

## Durability: the numbers that change decisions

- **Unbreaking** reduces the *chance* of losing durability, not the amount:
  - everything except armour: 50% / 33.3% / 25% at levels I / II / III - so **Unbreaking III makes
    a tool last about 4x as long**.
  - armour: 80% / 73.3% / 70% - only about 1.25-1.43x. Unbreaking III on armour is much weaker than
    people assume.
- **Mending** converts XP orbs into durability at **1 XP = 2 durability**, and it picks randomly
  among all your damaged Mending items. Mining ore, smelting, kills, fishing and breeding all feed
  it - so a Mending pickaxe largely maintains itself while you work.
- **Material repair (anvil)**: each unit of the right material restores **25% of max durability**
  and costs 1 level. Iron ingots repair iron gear, diamonds repair diamond gear, planks repair a
  shield, phantom membranes repair an elytra.
- **Combining two identical items (anvil)**: durability adds, **plus 12% of maximum**, for 2 levels.
- **The prior-work penalty is the trap**: every non-rename anvil operation multiplies the item's
  penalty by 2 and adds 1 (1, 3, 7, 15, 31, **63**...). After **6 operations** the penalty alone is
  63 levels and that item can never be worked on again in survival. So:
  - **Repair in as few operations as possible** - four material units in one go refills a tool,
    instead of four separate repairs.
  - **Renaming does not deepen the trap** (see below), but it is not free either.
  - A **crafting-grid repair** (two damaged identical items in the grid) or a **grindstone** resets
    the penalty to 0, **but destroys all non-curse enchantments**. Never grindstone enchanted gear
    by accident.
- **The anvil refuses anything costing 40+ levels** ("Too Expensive!"). **39 is the ceiling.**
- **Anvils wear out**: 12% chance to degrade per use, chipped -> damaged -> destroyed, about 25 uses
  on average. The `anvil` tool reads the block back after the job and tells you if it just degraded.

## The anvil: the `anvil` tool

```
anvil(item_id="minecraft:diamond_pickaxe", material="minecraft:diamond")   # repair with material
anvil(item_id="minecraft:diamond_sword",   material="minecraft:enchanted_book")
anvil(item_id="minecraft:diamond_sword",   name="Dragonbane")              # rename only
anvil(item_id="minecraft:diamond_sword",   name="")                        # strip a custom name
```

It finds an anvil within ~32 blocks (**it will not build one** — an anvil is 3 iron blocks + 4 iron
ingots = **31 iron**, so when there is none the reply quotes that gap rather than spending your
iron), opens it, loads the first slot with exactly one item, picks the second input for you if you
do not name one (repair material first, then an enchanted book, then a second copy of the same
item — the reply says which), sets the name if you asked for one, reads the real level cost, takes
the product back into your pack, and reports the levels and material it actually consumed.

**What it refuses, and why (all verified in the game code):**

- **Cost 40+ = "Too Expensive!"** - the anvil refuses and produces nothing. 39 is fine.
- **Renaming is the exception**: if the *only* thing the job does is rename, the cost is clamped to
  39, so even a 6-times-worked item can still be named. But **the rename still costs 1 level**, and
  typing the name that is already on the item costs nothing (it is not a rename at all).
- **Names are capped at 50 characters and longer ones are REJECTED**, not truncated. (Formatting
  codes are stripped *before* the length check.)
- **The input slot must hold exactly ONE item** - a stack forces the 40-level refusal, so the tool
  places one and the second input one at a time.
- **Not every pair of items does anything**: the material has to be one vanilla accepts for that
  item (and the item has to be damaged), an enchanted book has to carry enchantments that fit, and a
  second copy has to be literally the same item. If nothing works, the tool says so instead of
  silently taking your items.
- **Levels are spent when you take the product out**, not when you load the anvil. Being short is
  reported with the exact gap ("needs 30 levels, you have 29 - short by 1"); nothing is lost, and
  closing the anvil returns both input slots to your pack.

**Doing it by hand** - only when no anvil the tool can drive is available (a modded block that only
looks like one): `interact_at(button=right, x, y, z)` opens it, `inspect_gui` shows the two input
slots and the take-only result slot, `transfer` loads them and takes the result. **You cannot set
the name that way** - the rename is a text box, not a slot - so ask the owner to type it. Read the
transfer's own reply: if nothing moves, the operation was refused (not enough XP levels, or a cost
over 39).

## Running a tool to death

- **Carry a spare pickaxe.** A pickaxe that breaks mid-tunnel strands you deep underground with the
  wrong tool in hand and `goto` unable to dig.
- Retire a tool at low durability rather than at zero: switch to the spare, then repair in one
  operation later.
- `get_self_status` prints what is equipped **and its enchantments** - check it before the dragon
  fight, and confirm the sword/pickaxe/armour set still matches the `dragon_combat` packlist.
- Fortune on the pickaxe raises the **maximum** ore drops, so a Fortune III pickaxe is worth far more
  than a spare; treat it as the tool you protect.

## What to load next

Books from villagers -> `villager_trading`; the ore the Fortune pickaxe is for -> `underground_mining`;
the fight the gear is for -> `dragon_combat`; furnace/smoker cycles that feed Mending -> `containers`.
