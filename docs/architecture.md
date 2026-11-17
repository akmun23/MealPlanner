# Architecture

This is the longer version of the overview in the [README](../README.md). It covers the three parts
that took the most thought: how state and sync work, the quantity model, and the planner.

## State and sync

### Local data

`MealPlannerState` holds everything the UI reads for the current household: recipes, ingredients,
pantry lots, the week plan, the shopping list and meal history. Screens only read this state and
call methods on it.

`RoomSnapshotCache` persists it. The household is stored as one JSON snapshot, and the
frequently-read parts (ingredients, recipes, pantry lots) are also stored as indexed rows, so the
app can open and read them without decoding the whole snapshot. Room is at version 3 and the schema
files are in `app/schemas`. Migrations never drop pending edits: if one is missing the app fails
loudly instead of clearing the queue.

### Writing changes

A change takes three steps, all in one Room transaction:

1. Update the household snapshot and the indexed rows.
2. Add a `QueuedMutation` to the outbox table (`pending_mutations`).
3. Schedule a WorkManager job that needs a network connection.

`QueuedMutation` is a sealed interface with one case per kind of change (`UpsertRecipe`,
`SetWeekState`, `SaveIngredientConversions` and so on). Mutations that belong together, such as
creating an ingredient and adding it to the shopping list, share a batch id and are sent in order.
Retries use exponential backoff.

### Conflicts

Each queued batch remembers the snapshot it was based on. Before sending, the worker compares that
with the server. If another phone has changed the same data in the meantime, the batch is marked as a
conflict (shown in the sync status page) and is not applied. Nothing is overwritten silently.

Some writes go through the database instead of plain upserts, because a retry must not do the work
twice:

- Finishing a meal subtracts stock and records the meal in a single transaction, using a receipt
  row so a repeated request is a no-op.
- Recording a purchase uses a client-generated event id for the same reason. A network retry reuses
  the id, while a second real purchase gets a fresh one.
- Editing an ingredient's conversions carries an expected revision (compare-and-set).

### Incoming changes

`CloudMealRepository` subscribes to Postgres changes over Supabase Realtime and maps each table to a
sync domain (ingredients, recipes, pantry, week, shopping, history). Only the affected domain is
reloaded. `SyncGenerationTracker` drops a reload that finished after a newer local edit, so a slow
response cannot undo what you just did.

## Quantities

Cooking and shopping disagree about units, so the model keeps three things apart:

- **Physical amount.** Stored per ingredient in one base unit: grams, millilitres, pieces or packs.
  Everything arithmetic runs on this.
- **Display.** A recipe stores the unit and number its author typed ("2 tbsp") next to the physical
  amount, frozen at save time. Changing a product's pack size later does not change old recipes.
- **Conversions.** A household can confirm a bridge for one ingredient, such as "1 pack = 200 g" or
  "1 clove = 5 g". Fixed factors (g to kg, ml to l) are used inside a dimension. Across dimensions
  nothing is converted unless a confirmed bridge exists. Garlic cloves and garlic heads have no
  built-in conversion on purpose.

Pantry stock is a list of real packages (lots), with opened or sealed state and best-before date.
Old totals from before lots existed become a single "unknown packaging" lot. Consuming stock
follows a first-in-first-out order, and empty lots are pruned.

When a shopping line is matched to a store product, the number of packs to buy is
`ceil(max(0, need - stock) / packSize)`. Floating-point noise at an exact boundary (for example
`0.1 + 0.2` against `0.1`) is snapped away within a few ULPs so it does not add a phantom pack, while
a real shortfall still rounds up.

## Planner

`WeeklyPlanner.generate` is a randomised search that scores a whole week:

1. Filter to dinner recipes that are enabled for rotation and whose required pantry items are in
   stock, and respect each day's prep-time limit.
2. Build a few hundred candidate weeks. Each day picks from the eligible recipes with a softmax over
   an incremental score plus a little noise.
3. Improve each candidate with a short local search (swapping days), skipping locked days.
4. Score the finished week and drop duplicates that only differ by day order.
5. Pick the final plan from the best candidates with another softmax.

The score is a weighted sum (see `PlannerWeights`): pantry coverage, ingredient reuse, penalties for
repeated recipes, proteins and categories (stronger on adjacent days), recency over 7, 14 and 30
days, how often a dish was eaten in the last 60 days, and a small bonus for keeping a day unchanged.
The `jitter` weight sets how adventurous the softmax is, and zero always takes the best week.

Pantry coverage is capped per recipe, so a 20-ingredient recipe cannot beat a small one just by being
big.

## Testing

| Layer | Where | What it covers |
|---|---|---|
| Unit | `app/src/test` | rules, planner, sync queue and Room against Robolectric, wire parsing, backups |
| UI smoke | `app/src/androidTest` | Compose tests for search, unit chips and sub-page navigation; compiled in CI, run on a device |
| Edge Functions | `supabase/functions/**/*_test.ts` | quota handling, auth, search ranking, price updates |
| Database | `supabase/tests/*.sql` | RLS and RPC behaviour, each script runs in a transaction and rolls back |
