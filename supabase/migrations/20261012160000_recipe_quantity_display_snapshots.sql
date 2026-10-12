-- Add presentation-only snapshots to recipe ingredient requirements.
-- Physical calculations continue to use recipe_ingredients.amount_base.
-- Existing rows are deliberately NOT backfilled with guessed package sizes.
-- Run before installing clients which save display_amount/display_unit_code.
alter table public.recipe_ingredients
    add column if not exists display_amount double precision,
    add column if not exists display_unit_code text,
    add column if not exists base_per_display_unit double precision,
    add column if not exists conversion_approximate boolean not null default false;

-- All presentation fields are optional for legacy recipes. A complete new
-- snapshot must contain positive finite factors/amounts and a safe unit code.
-- Do not constrain amount_base to snapshot conversion here: older APKs may
-- still update physical amounts without touching the presentation columns.
do $body$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.recipe_ingredients'::regclass
          and conname = 'recipe_ingredient_display_snapshot_valid'
    ) then
        alter table public.recipe_ingredients
        add constraint recipe_ingredient_display_snapshot_valid
        check (
            (
                display_amount is null
                and display_unit_code is null
                and base_per_display_unit is null
            ) or (
                display_amount > 0 and display_amount <= 10000000000
                and base_per_display_unit > 0
                and base_per_display_unit <= 1000000000
                and display_unit_code ~ '^[A-Z][A-Z0-9_]{0,39}$'
            )
        );
    end if;
end
$body$;

comment on column public.recipe_ingredients.display_amount is
    'Frozen user-authored recipe amount; does not track later product pack sizes';
comment on column public.recipe_ingredients.display_unit_code is
    'Unit chosen by recipe author at save time, e.g. PACK or GRAM';
comment on column public.recipe_ingredients.base_per_display_unit is
    'Confirmed conversion factor at save time, NOT a live product default';
comment on column public.recipe_ingredients.conversion_approximate is
    'Whether the frozen recipe conversion factor was estimated, not exact';
