# Bilka fase 7 – frigivelseskontrol og sidste fejlrettelser

## Mål

Fase 7 gør den samlede Bilka-integration sikker og overskuelig før bred anvendelse. Fase 1–6 er
merget, og fase 6-databasen samt den autentificerede Edge Function er deployet. Denne fase
introducerer **ikke** en ny stor skærm eller et nyt flow.

## Hvad ændres?

1. **Manuel prisopdatering ændrer aldrig produktidentitet.** Den gamle genindlæsning upsertede hele
   `bilka_products`-rækken og kunne dermed ændre gemt mærke, pakkestørrelse eller billede. Nu henter
   den kun prisfelter til de valgte Bilka-ID'er gennem den eksisterende `bilka_update_prices_only`
   -RPC. Deno regressionstests beviser, at ingen identitetsfelter forekommer i opdateringspayload,
   og BilkaToGo udelades.
2. **Natlig prisopdatering lover kun det, der er konfigureret.** `bilka_nightly_scheduler_ready()`
   er service-role-only og kontrollerer, at cron-jobbet er aktivt og cron-token eksisterer i Vault.
   Serveren nægter nyt opt-in, indtil den er klar. Android viser `Afventer aktivering på serveren`,
   og til-knappen er deaktiveret, mens brugeren stadig kan slå et tidligere tilvalg fra.
3. **Hurtigere relationer.** Syv indeks dækker manglende fremmednøgler og opslag for
   ingredienskandidater, standardvalg, produktimport, indkøb per uge og natlige kørsler.
4. **Kortere brugerbeskeder.** Ingen forældet tekst om 'næste fase', og succesbeskeder fokuserer på
   næste handling i stedet for tekniske API-tal.

## Test gennemført ved udvikling

- Fase 6 er allerede deployet og bestod købstransaktion, rollback-fixture og service-role-kontrol.
- Fase 7-migrationen samt `supabase/tests/bilka_phase7_contract.sql` blev kørt i én
  database-transaktion med rollback. Den korrekte `false`-status for et deaktiveret cron-job,
  rettigheder og alle syv nye indeks blev valideret; **ingen** fase 7-ændring er deployet permanent.
- Unit-tests med mockede Prej-priser ligger i `price_updates_test.ts` og testes af GitHub Actions.
  Ingen faktisk personlig Prej-kvote er brugt.

## Release – efter grøn CI og din godkendelse

1. Merg fase 7-PR, anvend migration `20261010160000_bilka_release_readiness.sql` og kør
   `supabase/tests/bilka_phase7_contract.sql`.
2. Deploy `bilka-prej` med JWT-verifikation. Medtag **alle** runtimefilerne: `index.ts`, `prej.ts`,
   `discovery.ts`, `discovery_server.ts`, `catalog_server.ts`, `manual_search_server.ts`,
   `shopping_server.ts`, `rpc_allowlist.ts` og den nye `price_updates.ts`.
3. Genkontrollér adgangsrettigheder: ingen direkte brugertilgang til de interne Prej-, natlige-
   eller transaktionsfunktioner. Supabase Advisors har tidligere vist informationsadvarsler for
   service-only tabeller med RLS uden policies (bevidst), og advarsler ved `create_household`/
   `join_household` (bevidst medlemsflow), samt at leaked-password protection ikke er aktiveret.
   Ingen af disse ændres blindt under Bilka-udrulningen.
4. **Verificér nøglen i appen:** Min konto har én gemt, men uverificeret Prej-nøgle. Log ind som
   ejeren, åbn Bilka-indstillinger og tryk *Kontrollér min gemte nøgle*. Det bruger ét kvoteret
   Prej-kald til `/v1/stats`. Kaldet kan ikke udføres fra projektets CI uden brugerens
   autentificerede session.
5. Prøv én konkret søgning mod **Bilka, ikke BilkaToGo**. Sammenlign mærke, billede, pakkestørrelse
   og kædepris med Prej-svaret. Vælg varen på den separate produktside; standardproduktet må aldrig
   skifte af sig selv.
6. **End-to-end-køb:** Vælg en uge med et behov på 1,2 kg og en vare på 1 kg/pakke; køb 2 pakker.
   Lager skal få 2 kg. Prøv samme køb igen; ingen ny lagerførsel. Prøv et engangsalternativ, uden at
   husstandens faste SKU ændres. Prøv netværkstab og to samtidige brugere.
7. **Visuel Android-test:** Kontroller 320 og 360 dp bredde, stor systemskrift, tastatur åbent, ti
   produktalternativer, 100+ ingredienser og 4.000 px produktbilleder. Dialoger må være rullebare
   men aldrig udvide skærmen vandret eller skjule den primære handling. Den normale indkøbsliste
   skal bevare kompakte varerækker.
8. **Natlig opdatering:** Konfigurer samme stærke tilfældige cron-token i Supabase Edge Function
   secret `BILKA_NIGHTLY_CRON_TOKEN` og Supabase Vault `bilka_nightly_cron_token`. Hverken token
   eller personlige Prej-nøgler må tilføjes til GitHub, SQL-migrationer, Android eller logs. Aktivér
   derefter cron-jobbet separat. Den sidste migration **aktiverer ikke cron automatisk**. Brugeren
   skal selv tilvælge natlig opdatering, når nøglen er verificeret.

## Kendte begrænsninger

- Prejs dokumenterede API er verificeret mod den offentlige dokumentation på
  `https://prej.dk/api/dokumentation`; direkte autentificeret test med brugerens personlige nøgle
  kræver brugerens login.
- Da der endnu ikke findes valgte standardvarer eller verificerede Prej-nøgler i live-databasen, kan
  et fuldt Bilka-produktflow ikke dokumenteres som gennemført før brugeren har valideret sin nøgle
  og valgt en vare.
- GitHub build og unit-tests kan ikke alene bevise, at alle Android-layouts er visuelt korrekte.
  Bred distribution bør vente på den manuelle acceptance-test ovenfor.