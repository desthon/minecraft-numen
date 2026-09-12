---
name: storage_and_sorting
description: Base storage layout and inventory discipline - chest/barrel/shulker/ender-chest capacity and placement rules, a category scheme, what may be compacted into blocks and what may not, labeling, and how to survey a store room with inspect_block_storage. Load when the owner says "organize the chests", "where do we keep X", or after a big haul. The open/inspect/transfer mechanics themselves are containers.
---

# Skill: storage_and_sorting

`containers` teaches how to move items through a GUI. This skill is the decisions around it: **what
holds how much, what goes where, what must never be squashed into a block, and what you are not
allowed to touch.**

## What holds what

| container | slots | notes |
|---|---|---|
| chest | 27 | two side-by-side, same-facing chests merge into a **double chest, 54** |
| barrel | 27 | same capacity as a chest, but a block directly above does **not** stop you opening it - the floor-level option |
| shulker box | 27 | **keeps its contents when broken** and always drops when blown up - the only way to carry bulk |
| ender chest | 27 | private to each player and **survives death**; the contents are the same from any dimension |

Placement gotchas that look like bugs but are not:

- A chest or barrel **cannot be opened** if there is a solid or redstone-conducting block (or a
  bottom-half slab) directly above it. Beacons, glowstone, sea lanterns and TNT are the exceptions.
- A **cat sitting on a chest** blocks it.
- To keep two chests separate, place the second one from a different side - placing it alongside in
  the same direction merges them into a double chest.

## Survey before you move anything

`inspect_block_storage` reads a block's **items, fluid and energy directly, without opening it**,
and it works on most modded machines and tanks too. Use it to answer "what do we actually have and
where" in a few calls instead of opening a dozen chests - and to check a machine's fill level
without disturbing it.

When you do need to move things, batch it: `transfer moves=[{from:S1}, {from:S2}, ...]` with **no
`to`** routes each whole stack to the other side of the menu; add `to` + `count` for an exact
amount; a different item in the target slot swaps the two. One call can empty or fill a whole chest.

## A category scheme that survives a long game

One chest of "everything" becomes unusable within a day. Build rows of labeled containers along
these lines (the wiki's own list, condensed):

| category | examples |
|---|---|
| ores, ingots, gems | coal, raw iron, iron ingots, diamonds, their **blocks** |
| wood | logs, planks, sticks, saplings, fences, boats |
| stone & building | cobblestone, deepslate, stone bricks, glass, terracotta, wool colours |
| **food** | raw in one, **cooked in another, nearest the door** - that is the one you grab before a trip |
| mob drops | leather, string, bones, feathers, gunpowder, ender pearls, slime balls |
| tools & armour | plus one "spare gear" chest: the backup pickaxe lives here, not in your pack |
| redstone | dust, repeaters, pistons, observers, hoppers |
| brewing | bottles, nether wart, ingredients, finished potions |
| the Nether / the End | netherrack, blackstone, soul sand / end stone, chorus fruit, shulker shells |
| junk (mass) | dirt, gravel, sand, cobblestone overflow - keep it out of the working room |
| irreplaceable | dragon egg, nether star, elytra, shulker shells: **ender chest, not a wall chest** |

Label each container with a **sign or an item frame** on its face. A frame showing an iron ingot
reads faster than a sign, and a frame showing the category's icon is how a real store room stays
navigable.

## Compacting: what may be squashed and what may not

- **Safe**: 9 ingots/gems/dusts into a block, and back again - iron, gold, copper, coal, lapis,
  redstone, diamond, emerald and netherite. This is the single biggest space saving in a base.
- **Never compact**: **nether quartz, prismarine shards, amethyst shards, nether wart** - the block
  form **cannot be turned back** into items. Same trap for anything you only ever need as an item.
- Tools, armour, potions and music discs do not stack at all - they eat slots, so give them their
  own chest rather than scattering them.

## Inventory discipline

- Keep a working set on you always: logs/planks, coal, iron, **32 torches**, food, a spare pickaxe,
  and a crafting table.
- When mining or building far from base, place a **temporary chest** at the site and dump the junk
  (gravel, dirt, overflow cobble) into it instead of carrying it home.
- `drop_items` throws a stack away - use it for genuine junk only, and never for anything the owner
  might want. Report notable drops; a "mass junk" chest is a request, not a unilateral decision.
- For a long trip (the End, a far stronghold), carry a **shulker box** as luggage: 27 slots that
  keep their contents when you break it, so the return trip does not cost an inventory.
- Valuables and the "if I die I lose it" set go in the **ender chest**: it survives death and follows
  you across dimensions.

## Trust rules

- **Never empty, "tidy" or reorganize the owner's private storage without being asked.** Chests in
  a player-built base are theirs; world-generated chests are fair game (see `village_loot`).
- If a chest has been renamed by the owner, that name is the instruction - keep to it or ask.
- Ambiguous items go in a labeled **overflow** chest, not into a category where you guessed. And
  say where you put things: "sorted the mining haul into the ore chest; the gravel is in the junk
  chest" is the report that makes the work usable.

## What to load next

The GUI mechanics -> `containers`; what to keep for a specific trip -> `dragon_combat`,
`nether_entry`, `underground_mining`; what to do with the ores you just stored ->
`tier_progression`.
