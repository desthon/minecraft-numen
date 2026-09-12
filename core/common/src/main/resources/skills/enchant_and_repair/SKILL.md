---
name: enchant_and_repair
description: Get enchantments without an enchanting table (fishing treasure, librarians, loot), know which enchantments to ask the owner for, and manage durability and repair costs - Unbreaking maths, Mending, anvil prior-work penalty, the 39-level cap, grindstone. Load when gear is wearing out, when the owner offers to enchant, or before the dragon fight gear check.
---

# Skill: enchant_and_repair

Two separate problems that people lump together: **where enchantments come from** (you cannot
operate an enchanting table - the three offers are menu buttons, not item slots, so `transfer`
cannot press them) and **keeping gear alive** (durability, repair cost, and when to retire a tool).

## What to ask the owner for

When the owner says "I can enchant that for you", name what you want. Enchanting costs **lapis
(1-3 per option)** plus the XP levels shown, and the third, strongest option only appears when the
table has **15 bookshelves** around it. The table **never** gives treasure enchantments.

| item | ask for |
|---|---|
| pickaxe | **Efficiency** (speed), **Fortune** *or* **Silk Touch** (mutually exclusive) |
| sword | **Sharpness** *or* **Smite** *or* **Bane of Arthropods** (mutually exclusive; Smite for undead, Bane for spiders) |
| bow | Power, Infinity (bring your own arrows anyway) |
| armour | Protection; **Feather Falling** on the boots |
| any gear you rely on | **Unbreaking**, and **Mending** above all |
| ocean work | Respiration + Aqua Affinity on the helmet |
| fishing rod | Luck of the Sea (more treasure), Lure (faster) |

## Getting enchantments yourself

1. **Fish for enchanted books.** Your `fish` tool is the only renewable enchantment source you
   control. Fishing gives **85% fish / 10% junk / 5% treasure**, and the treasure table is six
   equally likely items - one of them is an **enchanted book**. Those books are **level 30 and can
   carry treasure enchantments, Mending included**, which no enchanting table can produce.
   - Treasure only comes from **open water**: the 5x4x5 column at the bobber must be water (or
     air/water-lily above water). In a puddle you only ever catch fish and junk.
   - Luck of the Sea is the enchantment that raises the treasure rate - worth asking for on the rod.
2. **Buy them from a librarian** (see `villager_trading`). Mending books are the headline purchase
   and cost double, but a librarian is far more reliable than fishing for one specific book.
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
  - A **crafting-grid repair** (two damaged identical items in the grid) or a **grindstone** resets
    the penalty to 0, **but destroys all non-curse enchantments**. Never grindstone enchanted gear
    by accident.
- **The anvil refuses anything costing 40+ levels** ("Too expensive!"). 39 is the survival ceiling,
  and it applies per operation.
- **Anvils wear out**: 12% chance to degrade per use, chipped -> damaged -> destroyed, about 25 uses
  on average. Use it deliberately, not to rename junk.

## Using an anvil

`interact_at(button=right, x, y, z)` opens the anvil like any other block GUI. `inspect_gui` shows
the two input slots and the take-only result slot; load them with `transfer` (`to` each slot) and
take the result with `transfer {from:<result slot>}`. **Read the transfer's own reply** - if nothing
moves, the operation was refused: not enough XP levels, or a cost over 39. Ask the owner to do it if
you are short on levels.

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
