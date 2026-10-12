# Bilka fase 5 – automatisk daglig prisopdatering

Fase 5 indfører **frivillig** opdatering af Bilka-priser hver morgen efter Prejs katalogopdatering.
Den ændrer aldrig brugerens standardprodukt, mærke, pakkestørrelse eller billede. Ingen Prej-opslag
er nødvendige for blot at åbne appen.

## Brugerflade

**Indstillinger → Bilka-produkter og priser → Automatisk opdatering af priser**.

- Til/fra-knap på brugerens **egen** Prej-konto og den aktuelle husstand; som udgangspunkt **fra**.
- Knappen kan kun aktiveres, når brugerens Prej-nøgle er verificeret.
- Status viser seneste kørsel og forklarer, at der kun opdateres priser for allerede valgte varer.
- Hvis brugeren fjerner sin nøgle, deaktiverer serveren automatisk alle den brugers
  baggrundstilladelser.
- To familiemedlemmer deler produktvalg, men hver har sin egen kvote. En planlagt opdatering bruger
  kun en eksplicit godkendt brugers nøgle; der roteres aldrig automatisk mellem deres nøgler.

## Backend og sikkerhed

- Migration `supabase/migrations/20261010140000_bilka_nightly_prices.sql` opretter:
    - `prej_nightly_preferences`: per bruger/husstand, opt-in som standard false.
    - `bilka_nightly_runs`: atomisk én claim pr. husstand pr. dato i lokal dansk tid (ikke pr.
      UTC-dato).
    - `bilka_update_prices_only(jsonb)`: service-role-beskyttet RPC der **kun** opdaterer prisfelter
      på eksisterende `bilka_products` poster; `ingredient_bilka_defaults` berøres aldrig.
    - `pg_cron` + `pg_net` schedule hver time, men kun mellem **05:00 og 07:00 i
      Europe/Copenhagen**; sommer/vintertid tilpasses automatisk. Flere opkald er sikre pga.
      once-per-day claims; maksimum tre husstande behandles pr. worker-kald.
- Edge Function `bilka-nightly-refresh` med `verify_jwt=false`, beskyttet af en separat tilfældig
  **cron-only-token** i `x-mealplanner-cron-token` som sammenlignes via SHA-256; bruger- og
  service-role JWT accepteres ikke som alternativ adgang.
- Worker vælger højst én **verificeret og aktivt tilvalgt** konto pr. husstand. Hvis brugerens kvote
  er brugt, stoppes opdateringen; en partners nøgle benyttes **ikke** automatisk som reserve.
- Henter op til **500 kendte produkt-ID'er pr. batch**; op til to batches pr. husstand pr. dag.
  Ingen ny produktsøgning foretages.
- Fase-2 kvotereservationen `prej_reserve_call` sikrer højst 20 egne kald/dag per Prej-konto; både
  manuelt kald, import, verificering og cron deler dette budget.
- Kun `prices[]` for kæden **bilka** opdateres. Ikke BilkaToGo. URL og billede forbliver uændret.
- Resultater/status lagres uden API-nøgler, tredjepartsfejlsvar eller produktvalg.

## Deployment **efter** grøn CI

1. Gennemgå migrationen og kør den i Supabase:
   `supabase/migrations/20261010140000_bilka_nightly_prices.sql`.
2. Kør `supabase/tests/bilka_phase5_contract.sql` i et testmiljø. Kontroller, at `pg_cron` og
   `pg_net` er aktiveret.
3. Deploy `supabase/functions/bilka-prej` med JWT-verifikation til personlig til/fra-indstilling, og
   deploy den **selvstændige** `supabase/functions/bilka-nightly-refresh` med `verify_jwt=false`.
   Dennes tre filer er `index.ts`, `nightly.ts`, `prej.ts`.
4. Opret en **ny** tilfældig token lokalt ved hjælp af en sikker tilfældighedsgenerator (fx
   `openssl rand -hex 32`). **Indsæt aldrig værdien i GitHub, appen, migrationsfiler, logs eller
   denne dokumentation.**
5. Gem samme token i Supabase Edge Function secret med miljøvariabelnavnet
   `BILKA_NIGHTLY_CRON_TOKEN` **og** krypteret i Supabase Vault under navnet
   `bilka_nightly_cron_token`. Værdien må kun administreres via sikre hemmelighedskontroller.
   Cron-SQL'en læser den direkte fra Vault ved udførelse, så token ikke ligger som klartekst i
   `cron.job`.
6. Kontroller den brugerinitierede opt-in fra appen, og test med en personlig verificeret
   Prej-nøgle. **Skemalagt opdatering kører slet ikke**, før begge cron-secrets er oprettet og
   brugeren har slået funktionen til.
7. Kontrollér `bilka_nightly_runs` for antal opdaterede produkter og status; kontroller
   `prej_api_usage` for forbrug.

### Tests

- `supabase/functions/bilka-nightly-refresh/nightly_test.ts`: København sommertid/vintertid,
  fravalgt samtykke, ugyldig nøgle, husstandsmedlemskab, én konto pr. husstand, én reservation pr.
  dato, pris-only data og ingen kald uden for vinduet.
- `supabase/tests/bilka_phase5_contract.sql`: service-only adgang og en rollback-fixture, der
  beviser, at produkt-ID, mærke, pakke og billede ikke ændres af prisopdatering.
- GitHub Actions: Deno typecheck og tests; Android-build og UI-integration i eksisterende workflows.

### Afgrænsning og driftsstatus

Den aktuelle aktive database har **ingen valgte Bilka-standardprodukter** og **ingen verificerede
Prej-nøgler** pr. fase-start, men har én gemt uverificeret nøgle. Derfor kan natlige Prej-prisopslag
**ikke** demonstreres i produktion endnu. Den eksisterende appknap `Kontrollér min gemte nøgle`
validerer først kontoen med ét personligt budgetteret Prej-kald; dernæst skal brugeren vælge varer
og aktivere natlig opdatering.

Ingen automatisk genkørsel ved fejl i samme lokale døgn; en fejl vises i kørselsloggen, og der
forsøges igen næste dag. Det undgår skjult forbrug af brugerens API-kvote. Fase 6 handler om indkøb,
pakkeantal og lagerregistrering.
