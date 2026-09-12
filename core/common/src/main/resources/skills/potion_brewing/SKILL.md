---
name: potion_brewing
description: Brew potions from scratch - the brewing-stand GUI loop, the full 1.20.1 ingredient tree, modifiers (redstone/glowstone/gunpowder/dragon breath), and which potions actually matter for the Nether, deep mining, the ocean and the dragon fight. Load before a Nether trip or when the owner asks for potions, fire resistance, or a weakness potion to cure a villager.
---

# Skill: potion_brewing

Potions are the cheapest insurance in the game: **Fire Resistance** turns the Nether and the Y -59
lava layer from lethal into routine, and **Slow Falling** removes the single most common way to die
in the End.

## Equipment

- **Brewing stand** = 1 blaze rod + 3 cobblestone. Needs to be placed with `build`, then opened.
- **Fuel is blaze powder**: one powder powers **20 brews** and the stand keeps the energy.
  That means `blaze_rods` is a prerequisite for brewing at all (or buy powder from a cleric).
- **Glass bottles** (3 glass = 3 bottles). Fill them from any water source or from a cauldron
  (`interact_at` a water bucket onto the water, then fill; a full cauldron fills 3 bottles).
- **Every brew takes exactly 20 seconds**, no matter what goes in.

## The chain

```
water bottle  + nether wart        ->  awkward potion   (no effect, the base)
awkward       + one ingredient     ->  the effect potion
effect potion + redstone           ->  same effect, longer
effect potion + glowstone          ->  same effect, level II, shorter
effect potion + gunpowder          ->  splash potion (throwable, area effect)
splash        + dragon's breath    ->  lingering potion (a cloud; 1/4 the duration)
```

**Nether wart is renewable**: it grows on soul sand in any dimension, needs **no light and no
water**, and one found patch multiplies forever. It generates in Nether fortress stairwells and
bastion treasure rooms. Harvest it with a Fortune tool for more.

## Ingredient -> effect (1.20.1)

| ingredient | potion | duration | what it does |
|---|---|---|---|
| blaze powder | **Strength** | 3:00 | +3 melee damage (II doubles it) |
| glistering melon | **Healing** | instant | restores 4 HP |
| ghast tear | **Regeneration** | 0:45 | 1 HP every 2.5 s |
| magma cream | **Fire Resistance** | 3:00 | **immunity to fire, lava, blaze fireballs, magma blocks, campfires** |
| sugar | Swiftness | 3:00 | ~+20% speed |
| rabbit's foot | Leaping | 3:00 | higher jump, 1 less fall damage |
| pufferfish | **Water Breathing** | 3:00 | no oxygen loss underwater |
| golden carrot | Night Vision | 3:00 | see in the dark and underwater |
| phantom membrane | **Slow Falling** | 1:30 | no fall damage, slow descent |
| turtle shell | Turtle Master | 0:20 | Slowness IV + Resistance III (very tanky, very slow) |
| spider eye | Poison | 0:45 | 1 damage / 1.25 s, never lethal |
| fermented spider eye + water bottle | **Weakness** | 1:30 | melee damage down - this is the cure potion |

**Corrupting with a fermented spider eye** (on an effect potion, not on water): night vision ->
invisibility, healing -> harming (6 instant damage), regeneration -> poison, swiftness -> slowness,
leaping -> slowness. **Strength cannot be corrupted in Java.** Leaping II and Swiftness II cannot be
corrupted either. (Fermented spider eye = spider eye + brown mushroom + sugar.)

Modifier numbers to remember: Fire Resistance goes 3:00 -> **8:00** with redstone; Strength goes
3:00 -> 1:30 at level II; lingering potions last a quarter of the drinkable version.

Undead are the exception to everything: poison and regeneration do nothing to them, and **healing
potions damage them**.

## Brewing through the GUI

1. `interact_at(button=right, x, y, z)` on the brewing stand.
2. `inspect_gui` - three bottle slots, one ingredient slot, one fuel slot (the indices are in the
   listing), plus your own inventory. The `data values` line is the brew progress.
3. Load it up in ONE call:
   `transfer moves=[{from:<bottle>, to:<bottle slot>}, {from:<nether wart>, to:<ingredient slot>},
   {from:<blaze powder>, to:<fuel slot>}]` - fill all three bottle slots, they brew together.
4. `close_gui`, then `set_timer` for ~25 s (20 s brew + margin). Walking away is correct - the
   stand does not need you.
5. When the timer fires, reopen it, `inspect_gui` to confirm, and `transfer` the finished bottles
   out. Then load the next ingredient for the same bottles.
- Brewing one ingredient at a time means three brews for a full stand: bottles are reusable, so
  always brew in threes.

**Drinking**: `eat(minecraft:potion)`. `eat` matches by item id only and cannot tell two potion
types apart, so **carry only the potion you intend to drink** - leave the rest in a chest, or brew
and drink at the stand. Splash potions are not drunk: `interact_at(button=right,
item_id=minecraft:splash_potion)` throws it (aim at a point, or leave the coordinates null to throw
straight ahead).

## What to brew for what

| job | potions |
|---|---|
| **Entering / crossing the Nether** | Fire Resistance 8:00 (x2), Regeneration, healing food - lava seas and ghast fireballs are the two killers |
| **Blaze spawner** (`blaze_rods`) | Fire Resistance 8:00, splash Healing for the group window |
| **Deep mining at Y -59** (`underground_mining`) | Fire Resistance 8:00 - that band is a lava layer |
| **Ocean work** (monument, shipwrecks) | Water Breathing 8:00 + Night Vision |
| **Dragon fight** (`dragon_combat`) | **Slow Falling** (the void is the real boss), splash Healing II, Strength II, Turtle Master for the perch |
| **Curing a zombie villager** (`villager_trading`) | Weakness (brewed from a water bottle) + a golden apple |

## Materials checklist

blaze powder (blaze rods), nether wart (fortress/bastion, then grow on soul sand), magma cream
(magma cubes in the Nether), ghast tear (ghasts), glistering melon (8 gold nuggets + melon slice),
golden carrot (8 gold nuggets + carrot), pufferfish (catch one with the `fish` tool), phantom
membrane (phantoms), rabbit's foot (rabbits), turtle scute x5 (turtle helmet), sugar (sugar cane),
spider eye (spiders), brown mushroom + sugar for fermented eyes, gunpowder (creepers, ghasts).

## What to load next

The Nether trip itself -> `nether_entry`, then `blaze_rods` for the blaze powder that makes all of
this possible; the fights -> `combat_basics`; curing the villager -> `villager_trading`.
