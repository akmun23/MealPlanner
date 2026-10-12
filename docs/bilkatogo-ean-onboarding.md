# BilkaToGo: EAN-stregkodeopslag og billigere første gennemgang

## Prej API: vigtigt om kvoter

Kilde: https://prej.dk/api/dokumentation og https://prej.dk/api/data

- `GET /v1/scan/{gtin}` tager **ét** EAN/GTIN-nummer pr. kald.
- `GET /v1/products/batch?ids=...` tager op til **500 Prej-produkt-ID'er**, **ikke 500 EAN-numre**.
- Prej tilbyder ikke et dokumenteret endpoint til *batch-scan af flere ukendte EAN'er*.
- Prejs egen edge-cache reducerer deres serverarbejde, men **koster stadig API-kvote**. Kun vores
  lokale `bilka_search_cache` springer Prej-kald helt over.
- MealPlanners lokale kvoteloft er 20 kald/dag pr. verificeret bruger; en første gennemgang med 60
  helt nye og ukendte EAN'er kræver ca. 60 Prej-kald, medmindre nogle koder kan genbruges fra vores
  lokale cache.

## Implementering i denne PR

- Nyt **EAN/stregkodefelt** i den eksisterende BilkaToGo-produktvælger (ved siden af almindelig
  navnesøgning).
- Backend `ean-lookup` via `/v1/scan/{gtin}`; validerer GTIN-8, UPC-A (12), EAN-13 og GTIN-14
  **inkl. GS1-kontrolciffer**.
- Backend sikrer autentificeret husstandsmedlemskab, tilgængelig ingrediens og Prej's eksisterende
  budgetreservation og kvoteafslutning.
- Alle EAN-resultater kontrolleres mod **`prices[].chain_gtins` hos
  `bilkatogo`** – ikke blot en tilsvarende EAN i produktets generelle
  `gtins` eller en pris i en anden kæde. Prej kan samle varianter under ét produkt-ID; et generelt
  produktmatch er ikke et sikkert ToGo-match.
- Prisen vises kun, hvis Prej leverer en rigtig BilkaToGo-pris på den konkrete kædes stregkode.
  Usikre/ukendte opslag giver i stedet brugerbesked om at fortsætte med navnesøgning.
- Positive opslag caches i 7 dage; negative opslag i 1 dag. Cachen er fælles på tværs af husstande,
  da EAN→offentligt produkt ikke er private brugerdata, men **valget** af produkt forbliver
  husstands- og ingrediensafgrænset.
- Almindelig navnesøgning gemmer nu også op til **100 verificerede kæde-EAN-opslag** fra Prejs
  returnerede produktlister i cachen. Derfor kan ét nyt navneopslag gøre mange efterfølgende
  EAN-opslag gratis. Ingen ekstra Prej-kald.
- EAN-opslag tilføjer højst en produktkandidat til den konkrete ingrediens; ændrer **aldrig**
  husstandens gemte faste SKU uden eksplicit brugerbekræftelse.
- Intet nyt SQL-skema nødvendigt: den eksisterende, service-role- beskyttede `bilka_search_cache`
  genbruges i eget EAN-navnerum.

## Testomfang

`supabase/functions/bilka-prej/barcode_test.ts` tester kontrolcifre, kæde-EAN-forveksling,
standard/indpakket scan-respons, lokalt cache-hit på tværs af ingredienser, negative opslag,
manglende pris/anden kæde, fuldførelse af Prej-budget og spærring før netværk ved ugyldigt EAN.
Testene er tilføjet GitHub Actions' Edge-checks.

**Ingen nye live Prej-opslag** blev udført under udvikling; de automatiserede tests bruger kun
simulerede provider-responser. Korrekt integration mod live `/v1/scan/{gtin}` bør verificeres med en
reel BilkaToGo-stregkode efter godkendte CI-checks, på et tidspunkt hvor der er kvote til mindst ét
kald.

## Udrulning efter godkendt PR

1. Squash-merge til `master`.
2. Deploy hele `bilka-prej` runtime inkl. **`barcode_server.ts`**
   samt alle øvrige runtimefiler; fortsat `verify_jwt=true`.
3. Ingen migration eller natlig-worker ændring påkrævet.
4. Installer en ny Android APK for at se EAN-feltet.
5. Gør et ægte EAN-opslag på en verificeret personlig Prej-nøgle, kontrollér kæde-EAN og pris, og
   gentag med 0 nye Prej-kald.

**Ingen automatisk EAN-massesøgning**; nye EAN-numre koster fortsat ét opslag hver, og systemet
opfinder ikke billigere Prej-funktioner.
