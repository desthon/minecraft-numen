---
name: redstone_basics
description: Redstone components and the traps in them - dust signal decay, repeaters (delay and latching), comparators (compare / subtract / read a container), pistons and sticky pistons, observers, torch / lever / button / pressure plate - and, for every part, HOW TO ACTUALLY PLACE IT with build + interact_at, which components are easy and which are hard because of exact orientation. Load when the owner asks for a redstone circuit, a door/piston/observer mechanism, or any "make it automatic" part that is not a farm. Farm-scale machines are simple_farms.
---

# Skill: redstone_basics

Redstone is where a build stops being static and starts doing work. Everything here is MC 1.20.1
Java behaviour. Two things make it different for you than for a human player:

1. **You place blocks with `build`, and you click with `interact_at`** - so a circuit is only real if
   you can express it as an ordered list of cells with the right **facing**.
2. **You have no F3, no tick-stepping and no invisible-state view.** A human debugs a circuit by
   looking at it; you debug it by `inspect_block` on the components and reading their properties.

## The signal rules that explain most failures

- Signal strength is an integer **0-15**. **Redstone dust loses 1 per block travelled**, so one run
  of dust reaches at most **15 blocks**. A repeater **restores 15**; a comparator **passes the
  strength through unchanged** when its sides are unpowered.
- **Power vs. activate.** A powered block next to a component is not the same as a component that
  is *pointed at* it. Mechanical parts (piston, dispenser, door, hopper) need an *active* input on
  the correct face; a repeater/comparator only accepts a signal on its **input** end, and a
  comparator only accepts a side signal from very specific sources (dust, redstone block, or a
  strongly-powering block). Place something one block off and it simply does nothing.
- **Repeaters and comparators are directional.** The arrow on top points from input to output.
- **Water destroys redstone.** In Java, water flowing over dust, a repeater or a lever breaks it
  (dust and the lever drop as items, the repeater just breaks). Never run a circuit through a farm
  channel; keep it on the frame above.
- **A repeater does not see a 2-game-tick pulse as two events.** A repeater delays by **2/4/6/8 game
  ticks** depending on its **1-4** setting; the same stage refuses to split a signal shorter than
  its own delay. Observers and fast clocks therefore need a comparator (2-tick scheduled, so it eats
  pulses of 2 ticks or less) or careful timing - this is the #1 "my machine does not fire" cause.

## The seven parts you will actually use

| part | what it does | can `build` place it? |
|---|---|---|
| **redstone dust** | carries 0-15, loses 1/block | **Use `interact_at`** - the block id `redstone_wire` exists and `build` *may* take it, but a `use` item on a block face is the reliable path. See "Placing dust" below |
| **repeater** | restores 15, delays 1-4 stages (2-8 game ticks), blocks backflow, **latches** when a repeater or comparator feeds its side | yes - `set` with `facing` (output direction) and `properties:{delay:"1".."4"}` |
| **comparator** | keep strength / compare / subtract / **read a container** | yes - `set` with `facing`; the mode is the output-side torch, set it by clicking the block, not by a property |
| **piston / sticky piston** | push a row of blocks (sticky also **pulls** one back) | yes - but **facing is the whole game**, read the warning below |
| **observer** | watches the face opposite its output; a block-state change there makes it emit **strength 15 for 2 game ticks**, 2 game ticks after the change | yes - `set` with `facing` (output direction) |
| **lever / button** | manual power, **15** | yes, and right-click works (see below) |
| **pressure plate** | 15 while a body stands on it | yes, and it needs no orientation at all |
| **redstone torch** | constant 15, inverted when its block is powered | yes; needs a solid face to stand on |

### Placing dust (and other item-only things)

Redstone dust is a **`use` item on a block face** - the same gesture as a torch, a button or a water
bucket - so you place it by aiming at the cell *underneath* (or beside) where the dust belongs and
using the item:

```
interact_at(button=right, x, y, z, item_id="minecraft:redstone")     # aim at the SUPPORT block
```

`build` *may* accept `block_id "minecraft:redstone_wire"` (the block does map to the redstone item),
but do not design around it: building one cell per `interact_at` is slow for a long run, and a long
run of dust is usually better replaced by repeaters anyway. **Test a single cell before you commit
to a layout.**

What `build` flatly refuses: **liquids** ("she does not place or drain liquids; leave water and lava
out of it and dig the basin instead, or let the player pour it"), **piston heads** and **moving
pistons**. A piston block lands **retracted**, which is what you want.

`goto` first if you are not already within ~4.5 blocks: **`interact_at` never travels.** If the
crosshair lands on a different block than you aimed at, it fails and names the blocker - break that,
or step to the target's open side, then retry.

### Everything that is a click, not a placement

```
interact_at(button=right, x, y, z)                 # lever / button / pressure plate: toggle or press
interact_at(button=right, x, y, z, hold_ticks=...) # a hold: door, or a bow
interact_at(button=right, x, y, z, item_id="minecraft:water_bucket")   # pour water
```

**You do not need to simulate redstone to test your own circuit.** A lever toggled with
`interact_at` right-click, or a pressure plate you step onto, is a real input. Then
`inspect_block` the downstream parts and read their state properties: a repeater reports
`powered`, a piston reports `extended`, so "did my circuit work" is a readable fact, not a guess.

## Orientation: the part that decides whether the machine works

Orientation is where a hand-built circuit and a cell list diverge. The rules:

- **Plain `set` = a player's right-click.** Nothing but `block_id,x,y,z` in a `set` means the block
  lands the way a real player would place it - which for **pistons, observers, dispensers, droppers
  and levers means "facing the player"**, i.e. it depends on where you stood. That is occasionally
  what you want (see the standing trick) but it is **not reproducible**.
- **`set` with `facing` (or `axis`, `half`, `properties`) = blueprint semantics.** The cell is
  written exactly as specified - deterministic, reproducible, and the right choice for a
  repeatable farm module.
- So: **for any mechanism, always pass `facing`.** The one exception worth knowing: if you want a
  piston/observer to land "facing you" and you cannot work out the right facing string, stand on the
  side you want it to point *away* from, then `interact_at` the placement cell with the item -
  the player-action path orients it off your gaze.

**Facing names** (same strings everywhere): `north` = -Z, `south` = +Z, `west` = -X, `east` = +X,
plus `up`/`down` where vertical facing is legal. For a piston or observer, **`facing` is the
direction it pushes / emits** (the built-in help describes observers as "the output end faces the
player", so for a given target: place it on the *opposite* side of that target, or state `facing`
as the vector toward the target).

**Always verify the first unit before you copy it 20 times:**

```
inspect_block(x, y, z)        # read facing / delay / powered / extended
```

If `facing` is wrong, the fix is usually one `build` op with an explicit `facing` - ten seconds of
work compared with a whole array that silently does nothing.

### Which components are hard, and why

| component | difficulty | the trap |
|---|---|---|
| pressure plate, lever on a floor block | easy | none - no facing needed |
| a wall of pistons all facing one way | medium | every cell needs `facing`; a plain `set` takes the direction **you** happen to stand in |
| observer chains / stacked farm units | medium-hard | each observer needs both **the right `facing`** and a target block that actually changes state. A one-block error makes a dead module |
| comparators in a sorter | hard | needs the input face right *and* a container behind it at exactly the tested distance |
| repeaters everywhere | medium | they are the easy part; the hard part is that they **only take a signal from their back**, so a dust line has to physically arrive there |
| dust that crosses / loops | hard | crossing wires and powering the neighbouring block is exactly why humans use "block markers" - colour different parts in different blocks so you can still read your own circuit afterwards |

## Component notes worth memorising

**Repeater.**
- `delay` 1-4 = **2, 4, 6, 8 game ticks**. `properties:{delay:"4"}` is the slowest.
- A repeater fed from its **side** by another repeater or a comparator **locks**: it holds its
  state and stops responding, and it will not run its scheduled update either - so a lock is how you
  **freeze a state** (a door held open, a memory cell). Build the side feed deliberately; a stray
  wire next to a repeater can latch it and look like a bug.
- Latching is the standard cure for an observer/piston pair that "keeps running": break the loop
  with a locked repeater instead of adding more parts.

**Comparator.**
- Three ports: input (back), output (front, the arrow), two sides. **The back faces the player when
  placed** - so on a blueprint, think about which way the arrow has to point and pass `facing`.
- Modes: output torch off = **compare** (output = input, but 0 if either side is *higher* than the
  input); output torch on = **subtract** (output = input minus the stronger side, floored at 0).
  `interact_at` the comparator to flip the mode - verify with `inspect_block`.
- **Reading a container**: point the input at a chest/hopper/furnace and the comparator outputs a
  strength proportional to how full it is. The wiki's own worked example: a hopper holding **41 of a
  64-stack in slot 1 plus 1 item in each of the other 4 slots** reads **3**. A comparator outputs 0
  for an empty container, so "chest empty?" is a real, checkable condition, and that is the hook a
  simple item sorter hangs from. (With non-stackable items in the hopper the minimum reading becomes
  much higher - the usual filler choice puts the floor at 12 - so a sorter built for stackables and
  one built for tools do not share arithmetic.) Full sorter work is `storage_and_sorting` territory -
  do not build a big one without the numbers in front of you.
- A comparator **ignores pulses of 2 game ticks or less** - which is why it, not a repeater, is the
  right part to clean up an observer's 2-tick pulse.

**Piston.**
- Powered from the back or from a block behind it; its **facing is what it pushes**. It cannot push
  obsidian, bedrock or the usual "immovable" list, and a single push moves a limited number of
  blocks (the working number everyone builds to is 12) - **a push that moves more than your design
  allows simply fails silently**; check the moved row with `inspect_block` after the first test.
- **Java semi-connectivity (QC)** is real: a piston (and a dispenser/dropper) can also be activated
  by a mechanism in the cell **directly above itself** - the block above needs only to satisfy the
  normal activation rule, it does not have to be a conductor. It reads as "it fired for no reason"
  until you know the rule - so do not run a wire across the row above your pistons.
- Sticky pistons **pull the block back** on release; a plain piston does not. If a machine "leaves
  the block behind", you used the wrong piston.
- A piston that is already extended, or a piston head, is not a place to hang a lever.

**Observer.**
- **Face watches, output end emits.** The face **opposite the output end** is the eye. Its pulse is
  **strength 15 for 2 game ticks**, **2 game ticks after** the change.
- **An observer points sideways only** (like a piston, its facing is one of the four horizontals). It
  **cannot look up or down**, so it must sit *beside* the block it watches, at that block's own level
  - an observer placed on top of a cell does not watch what is above it.
- It responds to the block **state** changing (a block appearing, a crop growing), not to general
  block updates - which is why it is the sensor of choice for cane/bamboo/melon style farms.
- It is **not** a redstone conductor - it cannot be charged from outside and does not charge itself.
- Piston-pushed observers misbehave (a pulse when they arrive, or a suppressed one) - keep observers
  off moving blocks.

**Lever / button / torch / plate.**
- Lever: 15 while on. `interact_at` right-click toggles it; `inspect_block` shows `powered`.
- Button: wood **30 game ticks** (~1.5 s), stone **20** (~1 s), then off by itself - a free pulse
  generator for a dispenser.
- **A redstone torch burns out after being toggled 8 times inside 60 game ticks (3 s).** Never put
  a torch in a fast clock; that is how sorters and clocks "spontaneously" break.
- Pressure plate: no orientation, but the entity has to physically stand on it - good input for a
  mob-powered door, useless as a remote switch.

**Liquids are never `build`'s job.** `build` refuses to place water and lava by design ("dig the
basin and let the player pour it"). Pour with
`interact_at(button=right, x, y, z, item_id="minecraft:water_bucket")`, aimed at the block the water
should sit against. Water spreads **7 blocks horizontally** from a source, **one block per 5 game
ticks**, and flowing water sweeps away crops and loose redstone - so a flush channel and a circuit
must never share a level.

## Build order for any circuit

1. **Design on paper first, output backwards.** Start from what has to move (the piston), then the
   part that drives it, then the input. This is the community's own method and it avoids a circuit
   that does something you did not plan.
2. **Build the frame in one `build` call** - the solid blocks the parts will sit on, plus the
   components themselves. `build` takes an **ordered** op list where later ops overwrite earlier
   cells, so put the walls first and the components last. Up to **16384 cells** per call, and in
   survival the job is refused **up front** if any material is short, so put the whole mechanism in
   one call rather than building half of it.
3. **Place the item-only parts** (dust, torches, buckets) with `interact_at`.
4. **Verify before wiring the next stage**: `inspect_block` the piston/observer/repeater you just
   placed and read `facing`/`delay`/`powered`.
5. **Test with a real input**: toggle the lever, or step on the plate, then re-`inspect_block` and
   confirm the state changed downstream.
6. **Only then scale it** - copy the module along the axis, keeping the same `facing` strings.

## Traps, ranked by how often they waste an afternoon

- **Wrong facing** on a piston or observer (see above). #1.
- **Dust not arriving at the repeater's back.** A repeater accepts nothing on its front or sides
  except a lock signal.
- **A stray side signal latching a repeater.** The machine freezes instead of running.
- **Water on the circuit level** - farm flush water, a misplaced pool.
- **A redstone torch in a fast loop**, burned out after 8 toggles.
- **QC**: a wire one block above a piston row firing it.
- **Mixing up piston and sticky piston** - the classic debugging list includes exactly this
  ("confusing the sticky piston with the piston") together with a wrong repeater delay, a comparator
  left in the wrong mode, and a hopper pointing the wrong way. Check those four before you suspect
  the design.
- **Scaling before verifying.** One unit, tested, then copy. Never the other way round.

## What to load next

Whole farm machines built on these parts -> `simple_farms`; putting the output away and the
hopper/sorter side -> `containers` and `storage_and_sorting`; the blocks themselves and how to make
a mechanism look intentional -> `building_design`.
