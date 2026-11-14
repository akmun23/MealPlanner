# Shopping extras, weekly price estimate, planner variety (14 Nov 2026)

## Extra amounts on the shopping list
Any line can ask for more than the recipes need ("Ekstra mængde", e.g. milk for
coffee). Before, an ingredient that recipes already needed could not be added as a
manual line.

- Stored in a new table `shopping_extras` (one row per week and ingredient), not in
  `shopping_inclusions`: regenerating a week deletes inclusions, an extra must survive that.
- The extra is part of the line's required amount, so "Mangler" and Bilka package counts
  include it. An extra for an ingredient no recipe needs becomes a manual line with that
  amount; an explicitly excluded ingredient stays hidden.
- Offline-first like the other shopping data (outbox mutation `shopping_extra`).
  Older app versions ignore the table. If the table is not deployed yet the list still
  loads, but saving an extra stays pending.

**Deploy order:** apply `supabase/migrations/20261115090000_shopping_extras.sql` to the
project first, then release the app. Check it with
`supabase/tests/shopping_extras_contract.sql` on staging (rolls back its fixtures).

## Weekly price estimate
The shopping summary shows "Estimeret pris: ca. X kr" and how many lines it is based on
and how many have no price. A line is priced from its standard Bilka product (packages
needed x price), or, once bought, from the paid amount, or from the bought product's
current price x packages when no amount was entered. Lines without a product are counted
as "uden pris", never as free. `ShoppingPriceEstimate.kt`.

## Planner variety
See the commit messages for the measurements. In short: the pantry bonus is a share of
the recipe in stock (not a sum per ingredient), the "Tilfældighed" setting now works in
score points and the final week is sampled from every candidate, ingredient reuse counts
less (12 -> 6), and a new "Spist ofte de sidste 60 dage" weight counts how many times a
dish was eaten recently (the recency penalties only look at the latest meal). With few
recipes the limit is structural: 7 different dinners a week and two weeks of "recently
eaten" penalties leave only a handful of fresh dishes, so they come up most weeks.
