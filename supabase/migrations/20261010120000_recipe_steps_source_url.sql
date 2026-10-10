-- Structured main/sub recipe steps (ordered JSON) and a separate recipe URL.
-- Existing notes are retained and existing recipes default to zero steps.
alter table public.recipes
    add column if not exists instruction_steps jsonb not null default '[]'::jsonb,
    add column if not exists source_url text;

alter table public.recipes
    add constraint recipe_steps_array_check
    check (jsonb_typeof(instruction_steps) = 'array');

comment on column public.recipes.instruction_steps is
    'Ordered structured steps. Each item contains id, text and level (0 main, 1 substep).';
comment on column public.recipes.source_url is
    'Optional link to the original recipe; separate from personal notes.';
