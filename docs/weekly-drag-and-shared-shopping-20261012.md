# 1.4.0 – Flyt retter mellem dage og fælles manuel indkøbsliste

## Brug

### Madplan

- På en ulåst dag med en planlagt ret findes et **flyttehåndtag** i dagskortets overskrift.
- **Hold og træk** håndtaget til den dag, retten skal serveres. Listen kan scrolle langs kanten under træk.
- **Tryk** på håndtaget for at vælge destinationsdag fra en dialog (tilgængelighed og sikker fallback).
- Retterne **bytter plads**. Hvis destinationsdagen var tom, flyttes retten dertil, og oprindelsesdagen bliver tom.
- Låste og spiste dage samt dage markeret **Ikke hjemme** kan ikke være kilde eller destination. Brug daglåsen først ved behov.
- Den særlige sandwichdag følger med, hvis den flyttes. Dagenes øvrige indstillinger, såsom tidsgrænse og hjemme/ude-tilstand, følger kalenderdagen.
- Købslisten nulstilles **ikke** ved rene dagsbyt: ugeforbruget af ingredienser er uændret.
- Flytningen sendes som én eksisterende `SetWeekState`-mutation til husstanden og fungerer med offline-outbox.

### Indkøb

- Åbn **Indkøb → Tilføj vare**. Skriv f.eks. **Skyr**.
- Hvis ingrediensen allerede findes, bruges den eksisterende post. Ellers vælg **vægt, volumen, stk. eller pakke/dåse** og tilføj den som ny fælles ingrediens.
- Der oprettes **ingen ekstra dublet** hvis varen allerede er på indkøbslisten. Ønsker du at tilvælge en vare, der allerede findes men er fravalgt, kan du tilføje den igen.
- En eksisterende ingrediens med `Køb aldrig` skal først ændres i ingrediensindstillingerne; serveren afviser bevidst registrering af sådanne køb.
- Ved manuelt tilføjede varer uden en kendt pakkestørrelse kræver lagerregistrering en eksplicit **faktisk købt mængde**; appen foreslår ikke automatisk f.eks. 1 gram.
- En manuel vare kan afkrydses som **købt** og derefter lægges på lager som de øvrige indkøbsvarer. Den viser `Tilføjet manuelt` i stedet for en falsk, beregnet opskriftsmængde.
- Tryk **Fjern fra indkøb** for at fjerne en manuel vare fra den valgte uges liste; selve ingrediensen slettes **ikke** fra ingrediensbiblioteket.
- Valget er gemt i husstandens eksisterende `shopping_inclusions` og synkroniseres til partnerens telefon gennem realtime og offline-køen.
- Oprettelse af en ny ingrediens plus tilføjelse til indkøbslisten sker i **samme lokale transaktionsbatch**, og oprettelsen synkroniseres før indkøbsmarkeringen.
- **Ugespecifikt:** Indkøbsmarkeringer og manuelt tilføjede varer er knyttet til den valgte uge. De indgår ikke automatisk i næste uges liste. De bevares dog ved regenerering, dagsflytning og normale ændringer i **samme uge** (for ingredienser, som er uafhængige af opskriftsbehov).

## Arkitektur / databeskyttelse

- Ingen Supabase-migration er nødvendig. `shopping_inclusions` har allerede `household_id`, `weekly_plan_id`, `ingredient_id` og `included`. En eksplicit `included = true` for en ingrediens uden opskriftsbehov vises som en manuel række.
- `ShoppingItem.manualOnly` er en **UI-afledt** egenskab; ingen ny databasekolonne kræves.
- Eksisterende `QueuedMutation.SetShoppingIncluded`, `SetShoppingChecked` og `UpsertIngredient` genbruges. `ClearShopping` efterfølges af bevarende eksplicitte inclusion-mutationer.
- Hvis en manuel vare også bliver anvendt i en opskrift, vises den **én gang** som almindelig opskriftsvare. En manuelt tilføjet ingrediens, der ikke længere findes, vises ikke som en spøgelsesrække.
- Den eksisterende Bilka-købsregistrering og serverens dubletbeskyttelse ændres ikke.

## Test

Pure Kotlin JUnit-regressiontests: `SharedShoppingAndWeekMoveTest`, der kontrollerer:
1. Fredagsburrito flyttes til mandag, og den tidligere mandagsret skifter til fredag.
2. Låste, ude- og tomme kildedage kan ikke flyttes.
3. Sandwichdagen følger med.
4. Manuelt tilføjet skyr vises kun ved eksplicit `included = true`, ikke som en falsk opskriftsmængde.
5. Ingen dubletter eller rækker for slettede ingredienser.

Kør i Android Studio:
```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleBenchmark
```

Manuel test på en **testhusstand med to enheder**:
1. Opret en madplan med mindst to middagsretter, inklusive én senere i ugen.
2. Træk en senere ret til i dag, kontroller swap og indkøbsmængder. Gentag ved tryk på flyttehåndtag.
3. Forsøg at trække en låst/spist/ude-dag; handlingen skal afvises.
4. Tilføj en ny vare `Skyr` fra enhed A, og kontroller at den dukker op på enhed B efter synkronisering.
5. Markér som købt på enhed B; kontroller ændringen på A. Test `Fjern fra indkøb`.
6. Slå net fra på enhed A, opret endnu en vare, slå net til og kontrollér at vare og markering synkroniseres én gang.
7. Genplanlæg samme uge; manuelt tilføjede, opskriftsuafhængige varer bør bevares. Skift derefter uge; listen er uafhængig.
8. Kontrollér Debug og release-lignende R8 `benchmark` på særskilt emulator. Ingen afinstallering af Play-appen med ikke-synkroniserede Room/outbox-data.

## Release-status

`versionName=1.4.0`, `versionCode=11` – kontrollér at versionCode 11 er ledig i Play Console.

**Koden er ikke verificeret med et Android SDK/device build i dette værktøjsmiljø.** GitHub Actions har tidligere fejlet, før de første buildtrin startede. Kræv Android Studio Debug/JUnit/build samt manuel drag-and-drop og synkronisering på to enheder før produktionsudrulning.
