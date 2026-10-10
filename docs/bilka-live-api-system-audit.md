# Bilka – faktisk live API- og systemkontrol (10. oktober 2026)

## Verificeret med personlig Prej-nøgle

Efter brugerens udtrykkelige tilladelse blev den **eksisterende nøgle hentet server-side fra Supabase Vault** og udelukkende anvendt i HTTPS-kald direkte til `https://api.prej.app`. Nøglen blev aldrig returneret i værktøjsoutput, tilføjet til repository, sendt til en tredjeparts testtjeneste eller gemt i testdata.

Kald blev budgetteret via `prej_reserve_call` og registreret via `prej_complete_call`. Fire rigtige kald blev udført, alle med **HTTP 200** og et forventet `products[]`-JSON-svar:

| Forespørgsel | Resultat | Bemærkning |
| --- | ---: | --- |
| `chain=bilka&q=mel&limit=5` | 1.201 match, 5 modtaget, alle 5 havde Bilka-pris | Meget bred; bl.a. produkter uden relevans for opskrifter |
| `chain=bilka&q=hvedemel&limit=10` | 0 match | Bilka-sortimentet hos Prej er ikke nødvendigvis komplet |
| `chain=bilka&q=mælk&limit=10` | 55 match, 10 modtaget | Blandede dagligvarer, legetøj og nonfood |
| `chain=bilka&q=mælk&grocery=1&limit=10` | 36 match, 10 modtaget | Prejs officielle kategori-filter indsnævrer sortimentet, men er ikke alene garanti for fødevare-relevans |

Begge API-kald med `grocery=1`-relevant forespørgsel og almindelige forespørgsler returnerede strukturer, som matcher appens datamodel med numerisk id/pris, `prices[]`, `chain_slug`, billeder og enheder. Det korrekte Bilka-prisfelt aflæses fra den konkrete prispost – aldrig produktets pris i en anden kæde.

Efter de vellykkede autentificerede kald blev brugerens eksisterende `prej_user_credentials.verified_at` opdateret via **`prej_store_key(user_id,prej_read_key(user_id))` internt i SQL**. Nøglen var ikke synlig i resultatet. Nøglen er verificeret på live-databasen: én verificeret aktiv konto. Fire Prej-kald er registreret som reserverede og afsluttede i den personlige dagskvote; ingen andre brugerkvoter benyttet.

## Rettelse på grundlag af live-data

Prej beskriver `grocery=1` i [officiel API-dokumentation](https://prej.dk/api/dokumentation). Det udelader produktkategorier som tøj, legetøj, elektronik, bolig og fritid, men Prejs kategoriopdeling omfatter stadig enkelte nonfood-varer. Derfor skal vores konservative ingrediensmatcher og det **udtrykkelige manuelle valg** stadig anvendes.

- Både automatisk produktimport (`discovery_server.ts`) og manuel søgning (`manual_search_server.ts`) bruger nu `chain=bilka&grocery=1`.
- Søgecachen bruger nyt versionsmærke `q:bilka:grocery1:` i stedet for `q:`, så gamle ufiltrerede resultater ikke genbruges efter deployment.
- Enhedstests kontrollerer kæde + dagligvarefilter på de faktiske sammensatte URL'er, cache-versionsnøglen og fortsat udelukkelse af BilkaToGo.

## Samlet backend-kontrol

- **PR #68 (fase 7)** blev merget med 3/3 grønne checks: Android build, Bilka Prej Edge checks og Startup release smoke.
- Supabase release-readiness migration er anvendt; SQL-kontrakt bestået. Syv manglende indeks for relevante Bilka-/indkøbsrelationer er oprettet.
- `bilka-prej` Edge Function version 7 er deployet med JWT-verifikation.
- Et direkte HTTPS POST uden gyldigt login fik **HTTP 401**. Personlige API-nøgler er fortsat isoleret i Vault.
- `bilka_record_purchase` er service-only; `authenticated`-rollen kan ikke kalde den direkte. Den eksisterende rollback-integrationstest dokumenterer korrekte pakker og afvisning af dobbeltregistrering.
- Natlig prisopdatering er **fortsat deaktiveret**. Scheduler-readiness returnerer false, og appen viser dette, indtil en operatør har opsat separat token og aktivt slået cron til.
- Fire kvoterede Prej-kald, fire afsluttet.
- På kontroltidspunktet: **0 valgte standardprodukter, 0 gennemførte køb, 0 indlæste katalogprodukter**. Disse er ikke fejl i sig selv, men betyder at et reelt Android-end-to-end-køb ikke endnu kan verificeres uden at en bruger vælger en vare.

## Performance, UX og begrænsninger

Produktvælgeren og indkøbslisten bruger målrettede katalogforespørgsler og indeks; almindelig listelæsning koster 0 Prej-kald. Lagerbilleder vises i 60 × 60 dp, produktbilleder i 76 × 76 dp med miniaturedekodning. Den eksisterende købsdialog er højdebegrænset, og indkøbslisten bevarer korte varerækker. CI har bekræftet Android-kompilering og Deno-tests, men der er **ikke** foretaget fysisk/emulator-baseret skærmbenchmark, performanceprofilering eller visuel accepttest på 320 dp skærm. Dette må ikke markeres som bestået uden en reel test.

Supabase sikkerhedsadvisor har to WARN for eksisterende `create_household`/`join_household` SECURITY DEFINER-funktioner (bevidste husstandsflow), samt en WARN om at [beskyttelse mod lækkede passwords](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection) er slået fra. Service-only tabeller med RLS og ingen offentlige policies giver INFO, hvilket er tilsigtet. Performance-advisor fandt ingen aktuelle unindexed-FK-advarsler efter fase 7; “unused indexes” er INFO og kan forventes i et endnu lille datasæt.

### Releasekriterier for dette hotfix

- [ ] GitHub CI grøn på denne nye PR.
- [ ] Merge, deploy alle ni Bilka Edge runtimefiler med `verify_jwt=true` (indeks og nøgle er allerede i produktion).
- [ ] Udfør et komplet Android-forløb med ét **eksisterende Bilka-produkt**, valgt manuelt: ingrediens → søgning → fast vare → indkøb → 2 hele pakker → Lager. Ingen automatisk standardændring eller dobbelt køb.
- [ ] Test på 320/360 dp, stor tekst og tastatur; ingen overlappende eller uforholdsmæssigt lange sider.
- [ ] Hvis varen ikke findes i Prejs Bilka-katalog, vises tomt resultat og mulighed for at fortsætte med manuel Lager-funktion; **ingen skjult overgang til BilkaToGo**.
- [ ] Aktiver **ikke** natlig cron uden den separate hemmelighed, en verificeret Prej-konto og eksplicit bruger-tilvalg.

Ingen hemmeligheder, private nøgler, bruger-ID'er eller leverandørheaders er indsat i dette dokument.
