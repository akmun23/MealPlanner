# Natlig prisopdatering: aktiveret og autentificeret fra Vault

## Produktionsstatus, 11. oktober 2026

- `bilka-nightly-refresh` **version 3**, `ACTIVE`, `verify_jwt=false`.
  Det er nødvendigt for pg_cron-serverkald, men hvert kald **kræver fortsat** den særskilte
  kryptografisk stærke cron-token. Kald uden token er live-testet og returnerer **401**.
- `mealplanner-bilka-prices` er **aktivt**, eksisterende tidsplan `0 * * * *`.
  SQL sender kun HTTP til Edge ved lokal time **05, 06 eller 07** i `Europe/Copenhagen`.
  Selve Edge worker kontrollerer desuden lokal tid for hver kørsel.
- Den separate cron-token genereres inde i Supabase med
  `extensions.gen_random_bytes(48)`, krypteres direkte i Vault under
  `bilka_nightly_cron_token` og optræder **aldrig** som literal i GitHub, Edge
  kode eller migrationen.
- En service-role-only RPC `bilka_nightly_token_digest()` returnerer kun SHA-256,
  **aldrig selve token**. Edge sammenligner denne hash med den præsenterede
  cron-bearer i konstant tid; tidligere manuelt Edge-env `BILKA_NIGHTLY_CRON_TOKEN`
  er ikke længere nødvendig.
- `public.bilka_nightly_scheduler_ready()` = **true**. Appens opt-in-lås
  ophæves dermed uden at fjerne sikkerheds- eller brugerautorisation.
- Der er **én** aktiveret husstand/bruger-kombination med **verificeret** Prej-nøgle
  og **ét** fast valgt produkt. Nøglen på partnerens konto er afventende
  verificering og må **ikke** bruges til natlige API-kald.
- Ingen natlige kørsler var registreret på aktiveringstidspunktet. Den
  **første faktiske kørsel** og antallet af opdaterede priser er endnu ikke
  verificeret; dette kræver observation efter næste 05:00-vindue.

## Hvordan det virker

1. pg_cron vækker jobbet hver hele time; kun time 05-07 København sender
   en HTTPS-anmodning til Edge (også ved sommer/vintertid).
2. Cron læser sin token fra Vault og sender den som
   `x-mealplanner-cron-token`; Edge henter kun tokenens digest via intern
   Supabase service-rolle.
3. Efter autorisation vælger worker én allerede **eksplicit aktiveret** og
   verificeret Prej-bruger pr. husstand. Kun allerede valgte produkter
   indgår. Uverificerede konti og ikke-tilmeldte konti ignoreres.
4. Kald reserveres atomisk i brugerens Prej-kvote (maksimum 20/dag).
   Eksisterende statusrækker forhindrer flere betalte opdateringer
   pr. husstand og lokal dato.
5. Worker opdaterer **kun prisrelaterede felter** for de tilsvarende
   Bilka/BilkaToGo-SKU'er. Mærke, pakkestørrelse, billede, husstandens
   foretrukne produkt, indkøb og lager forbliver urørt.

## Kontroller og aktivering

- SQL-migration i dette PR: `20261011123000_bilka_nightly_vault_auth.sql`,
  **allerede anvendt** på produktion og dens kontrakttest bestået.
- Edge-filer `index.ts`, `nightly.ts`, `prej.ts`, `auth.ts` **allerede
  deployet**, JWT-parametret er med vilje `false` kun på denne
  dedikerede maskin-endpoint med header-auth.
- Rigtig HTTPS negativtest uden token gav 401, ikke 200/503. Ingen Prej
  API-kald udført i forbindelse med deploy/aktivering.
- SQL med `cron.alter_job(...,active:=true)` aktiverede et eksisterende job.
  Den verificerede kontos eksisterende husstand blev derudover
  tilvalgt gennem `prej_nightly_preferences`. Ingen personlige UUID'er
  eller API-nøgler indsat i repo.
- Efter aktivering bekræftede en databasekontrol ét aktivt job,
  ét verificeret tilvalg, ét fast valgt produkt og `scheduler_ready=true`.

## Drift og rollback

**Status (ingen hemmeligheder):**
```sql
select public.bilka_nightly_scheduler_ready();
select jobname, active, schedule from cron.job where jobname='mealplanner-bilka-prices';
select count(*) from public.bilka_nightly_runs;
```

**Deaktiver planlægningen akut (påvirker ikke gemte produkter):**
```sql
select cron.alter_job(
  job_id := (select jobid from cron.job where jobname='mealplanner-bilka-prices'),
  active := false
);
```

Hvis en bruger slår natlige opdateringer fra i appen, skal
`prej_nightly_preferences.enabled` fortsat respekteres. Fjern ikke
autorisationsheader, service-role-permissioner, kvoteloft eller
én-gang-dagligt-registreringen.

**Tilbageværende verificering:** Første planlagte udførsel mellem 05:00
og 07:59 dansk tid, status `COMPLETE`/evt. `FAILED` i
`bilka_nightly_runs`, korrekt prisdato og kvoteforbrug. Ingen live
autoriseret request blev sendt manuelt efter deploy; adgang uden token
er verificeret afvist.
