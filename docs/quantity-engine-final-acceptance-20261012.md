# MealPlanner – mængdemotor, lagerpakker og Bilka (accepttest / release)

Dato: 12. oktober 2026. Den udviklede kode er ikke lig med en installeret opdatering.
Skel mellem **GitHub-merge**, **Supabase-migration**, **Android-build** og **udrulning**.

## Planlagt funktion

1. Ingrediensens **fysiske basis** forbliver g, ml eller stk. – gamle PACKAGE-data konverteres aldrig stiltiende.
2. Bekræftede, ingrediensspecifikke profiler forbinder f.eks. én pakke med 200 g eller to spsk. paprika med 12 g. Skøn er synligt mærket.
3. Opskrifter fryser brugerens indtastede enhed, mængde og fysiske faktor ved oprettelse. En ny indkøbspakke ændrer ikke gamle opskrifter.
4. Lageret kan indeholde særskilte fysiske pakker (åbnede/uåbnede, bedst før, rester), og gamle tal bevares som én **ukendt emballage**-lot.
5. Bilka-køb er event-ID-baserede, så to reelle køb samme uge er mulige, men ét netværks-retry ikke krediterer lager to gange.

## Release-rækkefølge (høj prioritet)

- Tag **eksport og verificeret backup** af produktionsdata, inkl. recipes, ingredients, recipe_ingredients, pantry_items, shopping_product_overrides, før schema-ændringer.
- Udfør den nye SQL-migration på **isoleret Supabase staging** med et realistisk eksempeldatasæt; kør rollback-kontrakttesten `supabase/tests/quantity_engine_lots_contract.sql` og de eksisterende Bilka-testkontrakter. Kontrollér RLS som både husstandsmedlem, ikke-medlem og service_role.
- Kontrollér at den gamle APK kan **læse og opdatere** en opskrift med frossen visningsenhed uden at miste metadata. Kontroller, at gammel lagerredigering og gammelt Bilka-køb ikke opretter dobbeltsummer.
- Anvend migrationsskema på produktion **før** distribution af en APK, som forespørger `ingredient_unit_conversions` og `pantry_lots`. Ved fejl skal deployment standse, ikke fortsætte blindt.
- Kør fra Android Studio lokalt:
  `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark`
  og verificér release-signering til Play særskilt (aldrig debug-key).
- Test derefter på to telefoner, først i en **testhusstand**, dernæst som kontrolleret opgradering. Undgå samtidige uopgraderede klienters ændring af fysiske base-dimensioner.

## Brugeraccept: mobilapp

- [ ] **Madplan:** Tryk/hold `Flyt` på en ulåst ret, se flydende kort og tydelig kilde/destination. Knappen rører ikke kontakten `Spiser hjemme` selv på lille skærm. Slip, byt retter, scroll og annullér. Vælg låst/spist dag: ingen ændring.
- [ ] **Opskrift med historisk pakke:** Tilføj en MASS-ingrediens `skinkestrimler`, bekræft `1 pakke = 200 g`, gem en opskrift med `1 pakke`. Opskriften viser stadig 1 pakke; fysisk behov er 200 g.
- [ ] **Ny pakkestørrelse:** Skift ingrediensens fremtidige profil til `1 pakke = 150 g`. Gammel opskrift forbliver 200 g / 1 pakke. Nyt køb skal foreslå 2 pakker ved tomt lager (300 g samlet) og 1 pakke, hvis der allerede er mindst 50 g.
- [ ] **Gram uden pakke:** Gem `300 g pasta`, selv om en købsstandard er 500 g pr. pakke. Retten skal vise 300 g, og behovet må ikke blive 500 g.
- [ ] **Krydderier:** Bekræft (eller anslå) antal gram pr. spsk. for paprika. Gem `2 spsk.` og kontrollér både grambehov og synlig `omregning anslået`, når profil er et skøn. Uden profil må konvertering ikke opfindes.
- [ ] **Styk/hele enheder:** Ved chili/hvidløg skal `pakke -> antal` eller `hoved -> fed` kræve en konkret bekræftelse og aldrig gættes.
- [ ] **Rediger opskrift:** Ret kun navn/noter/fremgangsmåde. Samme gamle mængde og enhed forbliver. Vælg en ny enhed bevidst: den nye enhed vises, mens det fysiske behov bevares.
- [ ] **Genstart og offline:** Opret en opskrift, ændr en profil og rediger lager uden internet, genstart appen, forbind igen. Begge telefoner får opdateringer, ingen opskriftsenheder forsvinder.
- [ ] **Lagerpakker:** Tilføj 2 × 500 g pasta, åbn den ene, brug 200 g og kontrollér at første pakke nu har 300 g, mens den lukkede stadig har 500 g. Markér bedst før; tidlig udløbsdato bruges først blandt lige åbningsstatus.
- [ ] **Gammelt lager:** Tidligere 300 g beholdning bliver én lot mærket `ukendt emballage`, ikke ekstra 300 g oveni. Tøm og redigér på en tidligere APK uden fordobling eller negative rester.
- [ ] **To telefoner/konflikter:** Åbn samme pakke på telefon A og B, ændr restmængder forskelligt, synkronisér. Ingen tavs overskrivning af nyere data; konflikter skal kunne genindlæses og afklares.
- [ ] **Bilka-køb:** Køb en 150 g-pakke, kontrollér én lagertilvækst. Køb en ny pakke af samme ingrediens samme uge: endnu én lagertilvækst. Gentag det *samme* købs-ID via retry: ingen ekstra lagerpost.
- [ ] **Andre funktioner:** Planlæg uge, historik/spist ret, manuel fælles indkøbsliste, låste dage, frokost, Bilka/Prej søgning, billedvisning, kategorier og backup fungerer fortsat.

## Ikke automatisk sikre legacy-migrationer

Eksisterende ingredienser i `PACKAGE`-dimension kan ikke konverteres til MASS uden en bekræftet faktor pr. ingrediens **og** en sammenhængende migrering af opskrifter, lager, købsregistreringer og gamle klienters writes. Den nye løsning holder disse tal i legacy-dimension og viser dem tydeligt i stedet for at gætte.

## Udrulnings- og fejlregler

- GitHub CI er efter projektejerens beslutning ikke en mergeblokering, men ingen kan kalde Android-build eller SQL-test bestået uden faktisk udførelse.
- Hvis migrationskontrakten fejler: ingen udrulning og ingen tvangs-merge af produktionsklient.
- Hvis et Bilka-køb giver netværksfejl: kontrollér event-ID/kvittering før ny kredit; brug samme ID ved retry.
- Hvis lagerpakker og total ikke stemmer: stop videre forbrug og sammenlign lot-rester, `pantry_items` og eventlog; ret ikke med et ekstra manuelt tillæg.

## Nye tværgående sikkerhedstests

1. **Konverteringskonflikt:** Telefon A gemmer pakkeprofil fra 200 til 150 g. Telefon B forsøger at gemme en forældet profil: ændringen skal afvises og genindlæses, ikke overskrive A. Offline-replay af samme event-ID må ikke øge revisionsnummeret flere gange.
2. **Samlet aftensmadstransaktion:** Afslut en ret, der bruger to ingredienser. Begge pakkers rester og historik skal opdateres sammen; hvis ingrediens nr. 2 mangler på serveren, må ingrediens nr. 1 heller ikke trækkes. Et retry med samme transaktions-ID er en no-op.
3. **Bilka-bekræftelse:** Registrér et køb, afbryd netværkssvaret, og kontrollér status på det **eksakte** nye købs-ID. Et tidligere køb af samme ingrediens må ikke få appen til at melde det nye køb som gennemført.
4. **Offline-operationer:** Genstart mens både profilændring og lagerjustering ligger i kø. Køens event-ID, forventet revision og mængder skal overleve og blive anvendt højst én gang.
5. **Database-sikkerhed:** Direkte INSERT/UPDATE/DELETE på `pantry_lots` og `ingredient_unit_conversions` fra en almindelig authenticated-klient skal afvises. Tilladte RPC-kald skal håndhæve medlemskab og revisionsnumre.

### SQL-installationsrækkefølge

1. `20261012160000_recipe_quantity_display_snapshots.sql` – opskriftssnapshots, profil- og lagergrundlag.
2. `20261012163000_conversion_profile_cas.sql` – revisionslås, receipts, realtime og RPC-only writes.
3. `20261012170000_atomic_meal_completion.sql` – alt-eller-intet-forbrug for måltider.

### Rollback-tests i staging

- `supabase/tests/quantity_engine_lots_contract.sql`
- `supabase/tests/recipe_snapshot_old_client_contract.sql`
- `supabase/tests/quantity_meal_atomic_contract.sql`

**Status 12. oktober 2026 efter integration:** Alle tre migrationsfiler er nu kørt på den tilsluttede Supabase-database. Før migreringen blev der taget en databaseintern, adgangsbeskyttet kopi af ingredienser, opskrifter, opskriftsingredienser, lager, Bilka-købsvalg, uger og historik. Kontrol efter migrering: 195 opskriftsingredienser bevaret; fysisk lagersum og gammel lagersum stemmer (16.286,25 basisenheder); ingen uoverensstemmende lagerposter. Alle tre SQL rollback-kontrakter er gennemført efter rettelse af en dobbelt-seedet legacy-lagerpost i testfiksturen. `bilka-prej` Edge Function blev opdateret til version 20. **En lokal Android Debug/Benchmark-build og fysisk test af 1.5.0 er fortsat ikke udført af assistenten.** Dette er derfor integrationsklar kildekode, ikke dokumentation for en bestået enhedstest eller Play-release.
