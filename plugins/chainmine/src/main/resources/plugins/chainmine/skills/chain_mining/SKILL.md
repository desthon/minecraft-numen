---
name: chain_mining
description: Chain-mine with the mod the player has installed (FTB Ultimine or Vein Mining) — break one block, let the mod take the whole vein at once, then fetch the drops. Use when the player says chain mine / vein mine / 连锁挖矿 / 连锁采集 / 一键挖矿, or asks her to clear an ore vein in one hit.
---

# Skill: chain_mining

On a pack with FTB Ultimine or Vein Mining the player has a key that turns one break into a whole vein.
`chain_mine` gives her that same single break — the mod does the rest, on the server, for real.

## First, get the tool definition

`chain_mine` is deferred: call `find_tools(["chain_mine"])` once before using it. The engine checks
whether you hold the definition, not whether you remember it.

## It only exists when a chain mod is installed

If the world has neither FTB Ultimine nor Vein Mining, the tool is **not offered at all** — you will not
find it in `find_tools`, and you should reach for `mine` instead. Say that out loud rather than
pretending: the player may believe their pack has it.

## What one call does

1. She looks at the target block (**reach matters**: within ~4.5 blocks — the same as the player's arm).
2. Numen sets the mod's own "chain key held" state on the server for that instant, then breaks the block
   through the native break path. Both mods listen to that break and take the vein **synchronously**, the
   same way they would for the player.
3. The key state is restored immediately — she does not stay in "chain mode" for the next swing.
4. She walks straight to the drops and picks them up (`collect_radius`, default 16).

## Passing the target

- `block_ids` — namespaced ids, and **include every variant** (`minecraft:iron_ore` **and**
  `minecraft:deepslate_iron_ore`). She breaks the nearest match within `radius` (default 4).
- `x`/`y`/`z` — the exact block, when the player pointed at one or you read it from
  `scan_blocks` / `inspect`. All three or none.

She has to already be next to it. If she is not, use `goto`/`mine` first — chain mining is not travel.

## Read the result before you speak

The result is the evidence, so use it instead of guessing:

| field | meaning |
| --- | --- |
| `vein_blocks` | how many blocks of that vein Numen read around the target before the break |
| `vein_blocks_gone` | how many of them are actually gone afterwards |
| `chained_beyond_first` | `vein_blocks_gone - 1`: the blocks the **mod** took beyond the one she broke |
| `items_collected` | drops that ended up in her inventory |
| `items_left_behind` | drops she could not reach walking straight (run `collect_items` for those) |
| `note` | set when the activation could not be fully planted (old/repackage build, mod not initialised) |

**`chained_beyond_first = 0` means the chain did NOT fire.** The usual reasons, in order: she has no
proper tool in hand (both mods check — FTB Ultimine has a `require_tool` setting, Vein Mining wants its
enchantment unless the pack configured otherwise); the block is not one the mod chains (tags / blocks
list); or the mod's own activation setting does not allow it. Report that honestly — *"I broke the one
block; the chain mod did not take the rest, she may need the right tool in hand"* — instead of claiming
a vein.

## Do not oversell it

- **It is one break, not a mining trip.** For "gather 64 iron" the right tool is `mine`; it mines until
  the count is met. Use chain mining when the player wants *that vein*, *now*.
- **She cannot do the player's key press for them.** `chain_mine` arms her own break only. If the
  player wants chain mining for themselves, that is their key and their config.
- **Nothing is duplicated.** The mod breaks the blocks exactly as it would for the player: same drops,
  same tool damage, same exhaustion. Never present it as free ore.
- **The pickup is straight-line.** Drops on a ledge, across a gap or in a hole stay there and are counted
  in `items_left_behind`; `collect_items` (which pathfinds, digs and bridges) can fetch them.
- If the result says the block was protected or refused, that is a real answer: say the area is protected
  instead of retrying the same call.
