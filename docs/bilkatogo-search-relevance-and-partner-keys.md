# BilkaToGo – relevans, kompakt søgning og to personlige Prej-konti

## Grundprincip

Prej-kæden for **nye** indkøbsvarer er `bilkatogo`. Eksisterende fast valgte Bilka-produkter og
deres priser bevares som deres oprindelige, separate kæde. Appen må aldrig bytte en husstands fast
valgte SKU automatisk.

Automatiske Prej-massesøgninger er fortsat sat på pause på serveren. Søgning sker ved brugerens
udtrykkelige valg af én ingrediens, eller efter særskilt bekræftet bulkvalg med kendt kvoteforbrug.
Gemte, identiske forespørgsler kan genbruges uden ekstra leverandørkald.

## Denne PR

- Søgeresultater sorteres som standard med de **mest sandsynlige ingrediensmatch først**. Det er en
  lokal, deterministisk sammenligning af normaliserede danske varenavne og ingrediensnavne og koster
  **0 Prej API-kald**.
- Eksempel: `Fløde`, `Piskefløde` før `Flødebolle` og `Flødelikør`; `Chili` før `Chili tequila`;
  dyrkningsprodukter som `Agurkeplanter` nedprioriteres. Dette er kun et rangordningssignal og ikke
  et løfte om fødevareægthed.
- **Alle** returnerede varer er fortsat tilgængelige, også dårlige match, så brugeren altid kan
  vælge manuelt. Den permanente husstands-SKU er stadig øverst, og ingen ny SKU vælges automatisk.
- Til at begynde med vises **12 kort**, derefter kan 20 mere åbnes ad gangen, uden Prej-kald.
  Eksisterende sideindlæsning fra Prej er stadig brugerinitieret og kan koste ét kald pr. ny side.
- Sammenligning efter enhedspris foretages kun, når enhederne virkelig er ens. Rækkefølge og
  beregning memoiseres/forudberegnes for at mindske gentagen UI-arbejde og bevare flydende scroll.
- De korte søgeresultat-beskeder beskriver faktiske antal varer og priser – ikke alle som
  `relevante`, da rå søgning med vilje viser også irrelevante varer.
- **Serverens valg af fast produkt** kontrollerer, at varen findes med en ægte pris hos dens
  respektive kæde. BilkaToGo-produkter uden ToGo-pris er stadig synlige, men kan ikke gemmes som
  prissatte standardvarer, selv gennem et direkte API-kald.

## Tests

- Android `BilkaSelectionRulesTest.kt`: oprindelige enhedspris-/standard-SKU-tests samt tests for
  fløde, flødelikør, sødmælk, mælkebøttefjerner, dyrkningsprodukter, chili/tequila, komplette og
  blandede prisenheder.
- Deno `catalog_test.ts`: godkendt ToGo-produkt med pris gemmes; produkt med manglende pris eller
  forkert kæde afvises; eksisterende husstandsisolering bevares.
- GitHub Actions (Android build, release smoke, Bilka Edge tests) skal verificeres **af brugeren**
  før merge. Intet testflow skal bruge en privat Prej API-nøgle eller foretage live Prej-kald.

## To brugere og personlige Prej-nøgler

Ved gennemgangen er der **to** konti i husstanden, men kun **én** krypteret Prej-nøgle i Supabase
Vault. Den gemte nøgle tilhører allerede den konto, der har registreret den; den må **ikke**
kopieres til den anden konto, da hver konto har egen API-kvote, og brugeren har oplyst, at den
første er vedkommendes personlige nøgle.

For at knytte partnerens egen API-nøgle til den anden konto skal partneren logge ind på **sin**
konto og vælge **Bilka-indstillinger → Prej API-nøgle**. Funktionen gemmer nøglen kun i Vault og
verificerer den inden søgerettigheder gives; er kvoten opbrugt, kan nøglen stages som
ikke-verificeret frem til kvoten bliver genåbnet. Ingen nøgle er med i denne PR eller tilføjet i
databasen på vegne af partneren.

## Deployment

Efter CI: merge, deploy `bilka-prej` med alle aktuelle runtime-filer og JWT-verifikation slået til.
Ingen ny database-migration kræves for denne PR. Foretag gerne visuel QA på smal telefon med mange
resultater: 12 kort, "Vis flere" og kompakt kontrol med valgte varer. Natlig cron forbliver
deaktiveret.
