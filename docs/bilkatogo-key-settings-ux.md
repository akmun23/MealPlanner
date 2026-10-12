# Gør Prej API-nøgle og BilkaToGo-søgeindstillinger tilgængelige

## Bekræftet årsag

I `master` åbnede **Indstillinger → Bilka-produkter og priser** ved en fejl kun
`AppPage.BilkaProducts`. Den personlige Prej API-nøgle var gemt i en separat
`BilkaApiSettingsScreen`, som brugeren kun kunne nå ad omveje. Produktoversigten havde intet
tydeligt link til denne skærm. Derudover delte nøgleformularen
`busy` med to efterfølgende serverforespørgsler (produktimport og nightly), så brugeren kunne blive
blokeret fra at indsætte en nøgle i lang tid.

## Ændring

- **Indstillinger → BilkaToGo** ligger umiddelbart under husstandens navn. Den har to selvstændige
  valg: **Prej API-nøgle og søgeindstillinger** og **Mine BilkaToGo-produkter**.
- Synlig genvej til **Prej API-nøgle og søgeindstillinger** fra både produktoversigten og en konkret
  ingrediens' produktvælger.
- Tilbageknappen returnerer til den korrekte af de tre indgange.
- Skærmen hedder **BilkaToGo · Indstillinger**, og personlig nøgle er den første sektion. Brugere
  med en allerede gemt nøgle får en tydelig, fuldbredde **Tilføj / udskift min Prej API-nøgle**.
- Brugeren kan også åbne nøgleformularen under initial statusindlæsning, uden at efterfølgende
  katalog-/natlige kald låser den. En påbegyndt nøgleindtastning bliver ikke lukket, blot fordi en
  statusforespørgsel bliver færdig. Formularen lukkes først efter gemt nøgle.
- Lokal syntakskontrol `BilkaKeyInputRules` med test af gyldige og afviste Prej-formater. Knap
  aktiveres kun når hele nøgleformatet er korrekt, og fejlbesked står lige under feltet.
- Status- og gemmefejl står direkte under nøglekortet fremfor efter alle produktsøge- og
  prisopdateringsafsnit.
- Eksisterende `BilkaPrejClient.saveKey`/Edge `set-key` gør fortsat krypteret Vault-lagring og
  Prej-validering; nøglen lagres aldrig i Room, preferences, kildekode eller GitHub.

## Manuelle regressionstjek efter Android CI

1. På en telefon: fra **Indstillinger** kan ny og eksisterende bruger gå direkte til Prej-feltet og
   tilbage til Indstillinger.
2. Fra produktoversigten og ingrediensens produktvælger åbnes samme skærm. Tilbageknappen går
   tilbage til henholdsvis oversigten og den samme ingrediens.
3. Under langsom status-/katalognetforbindelse er **Tilføj / udskift** tilgængelig; halvfærdig
   indtastning forsvinder ikke, når statusforespørgslen returnerer.
4. Ugyldig nøgle viser hjælpetekst og deaktiverer gemning. Ved gyldig nøgle kaldes eksisterende
   service og kvote/verifikation respekteres (inkl. staged nøgle ved udtømt kvote).
5. Begge husstandsmedlemmers nøgler forbliver adskilt. Ingen virkelige nøgler bruges i testene.

## Rollout

Android-only: ingen databaseændring eller Edge-deployment. En ny Android-appbuild er nødvendig for
at vise navigationen. PR'en skal testes af brugeren; der skal **ikke** afventes CI.
