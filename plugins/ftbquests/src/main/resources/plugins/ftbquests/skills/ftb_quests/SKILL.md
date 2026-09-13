---
name: ftb_quests
description: Read the pack's FTB Quests book and advance the main line — which quests are unfinished, what the next ones need, and how much is still missing. Use when the player mentions quests, 任务书, FTB, the quest book, or asks what to do next in a modpack.
---

# Skill: ftb_quests

On a modpack the quest book **is** the main line: it says what the pack wants done next, and the
player's progress through it is what they mean by "how far along are we".

The `ftb_quests` tool reads that book — definitions *and* the team's progress — and hands you the
raw material to plan with. It never touches it (see "Read-only" below).

## First, get the tool definition

`ftb_quests` is deferred: call `find_tools(["ftb_quests"])` once to pull its definition back
before using it. The engine checks whether you have the definition, not whether you remembered it.

## The loop you are actually in

1. **`action=next`** — the quests whose dependencies are already satisfied, in book order, plus the
   ones started but not finished. Everything further out is listed as a count. Start here.
2. **`action=quest`** on the one you pick — objectives with the **exact amount still missing**
   ("have 37/64, still short 27"), which of its dependencies are met, and what it pays out.
3. **Go get it.** That is the rest of your toolbox: `scan_blocks` / `craft` / `mine` / `kill` /
   `locate_structure`. This skill only tells you *what* is missing, not how to get it.
4. **`action=progress`** before reporting to the player: chapter-by-chapter standing, so you can
   say more than "we did a quest".

Never work on a quest the book calls blocked. "Blocked (needs earlier quests)" names the earlier
quests right there in the result — do those first, or tell the player why not.

## What the states mean

- **doable** — dependencies satisfied, nothing done yet. These are the candidates.
- **in progress** — started but unfinished. Nobody needs to be *told* about it; it needs the
  missing materials. Prefer finishing over starting something new.
- **blocked (needs earlier quests)** — the objective has not even started counting. In the book's
  normal (`linear`) progression FTB does not count objectives until the dependencies are complete,
  so gathering the items early is wasted effort: the book ignores them.
- **blocked (no progress file, dependencies unknown)** — you have the definitions but the world has
  not been saved since, so nothing is recorded. Say that out loud instead of claiming the player
  has done nothing.
- **hidden** — technical quests the pack author hid. They are never recommended; do not dig for them.
- **(optional)** — does not block the main line. Mention it, let the player decide.
- **(repeatable)** — completable more than once; the progress file only records that it was ever
  completed, so read it as "done before", not "not allowed again".

## The trick that matters most

**Item objectives count from the player's own inventory**, not from chests. So "we have 200 iron in
the storage room" is not progress. When a quest wants items, they have to end up *on the player* —
and once the objective completes, the book takes them (**consume**) and pays out the reward.

The useful sentence to the player is therefore: *"the next quest wants 64 iron ingots and you are 27
short — I'll mine them and hand them over; keep them on you so the book counts them."*

## Read-only: the player claims, you never do

- The tool **cannot** claim rewards, complete a quest, or change any quest state — and should not.
  Rewards are the player's: reward commands run with elevated permissions, loot tables get rolled,
  and it is their book.
- A **checkmark** objective can be ticked by nobody but the player (it is a manual tick).
- When a quest is ready, say so: *"the quest 'X' is ready to claim — open the book and click it."*
- Never run `/ftbquests` commands to force progress. That is manufacturing the account.

## Honesty about staleness

Progress comes from the save file FTB writes **when the world saves** (autosaves are minutes
apart). Counts for part-finished objectives can therefore lag behind what just happened. The result
prints `snapshot Xm old` and, when it is old, a reminder to say so. **Pass that caveat on** instead
of presenting a stale count as current — and if a number looks impossible ("we just handed over 64
iron and it says 0"), that is usually an unsaved world, not a bug: have the player save, then re-read.

## When to stay out of it

- The player is not talking about quests and you have no main-line work pending: do not open the
  book to find yourself a job. Ask.
- The player is doing their own thing (building, exploring): quest progress is the pack's opinion,
  not an order. Mention the next quest at most once.
