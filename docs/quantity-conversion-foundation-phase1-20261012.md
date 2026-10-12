# Konverteringssystem: teknisk plan og fase 1 (1.5.0)

Dette er **fase 1**, ikke den færdige funktion til krydsdimensionelle
enheder eller individuelle lagerpakker. Hold denne PR separat fra
drag-and-drop (PR #94). Fasen indfører en fælles konverteringsmotor,
frosne opskriftsmængder, serverlager og testgrundlag.

## Fastlagte produktregler

1. Opskriften **viser den enhed, forfatteren valgte**, f.eks.
   `1 pakke pasta` eller `300 g pasta`.
2. Dens **fysiske behov ændrer sig aldrig**, når standardvaren skifter
   fra en pakke på 500 g til en på 400 g.
3. Lageret skal på sigt kende **både total fysisk beholdning og hver
   konkret pakke**, inklusive rester i åbnede pakker.
4. `spsk. -> g`, `pakke -> stk.` og `hoved hvidløg -> fed` må
   **ikke gættes**. En omregningsfaktor skal være bekræftet for
   ingrediensen eller produktet; skøn markeres som skøn.
5. Opgradering af eksisterende `PACKAGE`-/`COUNT`-ingredienser til en
   ny fysisk grundenhed må **ikke** omfortolke deres gamle tal uden
   eksplicit brugerbekræftelse og auditerbar migrering.

## Implementeret i fase 1

- `QuantityUnit`: udvideligt inputordforråd med eksisterende
  `MeasurementUnit`-navne samt `GARLIC_CLOVE` og `GARLIC_HEAD`.
- `QuantityConversionEngine`: præcise konverteringer inden for
  MASS/VOLUME/COUNT/PACKAGE og eksplicitte ingrediensspecifikke brofaktorer
  på tværs af dimensioner. Ingen automatisk spsk. = g eller pakke = stk.
- `RecipeQuantityRules.freeze`: bevarer fysisk `amountBase`,
  `displayAmount`, `displayUnitCode`, `basePerDisplayUnit` og
  `conversionApproximate`. Sidstnævnte markerer en vejledende faktor.
- Opskriftseditoren **bruger stadig den eksisterende sikre gruppe af
  valgbare enheder**; ved gemning lagres den valgte enhed og faktoren.
  Opskriftens detaljevisning og efterfølgende redigering genskaber den
  valgte enhed, også når ingrediensens foretrukne enhed ændres.
- Cloud `recipe_ingredients` og Room-cache/Room backup bevarer
  visningsfelterne. `RecipeRequirementRules.combineIngredients` må
  kun sammenlægge visningsfelter, når alle dubletter har samme validerede
  enhed/faktor; ellers bevares udelukkende den korrekte fysiske total.
- Gamle recipes med kun `amountBase` fortsætter med at virke og vises
  i deres nuværende foretrukne enhed; den oprindeligt indtastede enhed
  kan **ikke rekonstrueres pålideligt**, hvis den ikke blev gemt.
- Hvis en gammel klient senere ændrer `amount_base`, uden at dens
  visningsfelter følger med, forkastes den inkonsistente
  visningssnapshot ved læsning; fysisk mængde har forrang.

## Supabase-migration / release-rækkefølge

SQL: `supabase/migrations/20261012160000_recipe_quantity_display_snapshots.sql`.

Tilføjer nullable `display_amount`, `display_unit_code`,
`base_per_display_unit` samt `conversion_approximate` med default
`false` til `public.recipe_ingredients` og datavalidering.

**Migreringen skal først anvendes og valideres i et test-/staging-
Supabase-projekt**, derefter i det rigtige projekt FØR nye klienter
gemmer opskrifter. Den er **kun committet som SQL**; ændrer ikke
automatisk produktionsdatabasen ved merge. Kontroller også at ældre
APK'er kan læse rækker med de nye ekstra kolonner, før release.

Der er ingen Room-schemaændring; det er serialiserede valgfri felter i
den eksisterende Room-snapshot-payload.

## Fase 2: redigerbare omregningsprofiler og tværsdimensionelle enheder

Før næste klientændring:

- Serverlagrede `ingredient_unit_conversions` knyttet til husstand,
  ingrediens og enhedskode. Felter: factor-to-legacy-base, exact/estimated,
  bekræftelseskilde, ændringstidspunkt og version.
- Produktrelation med mængde/størrelse i en typebestemt dimension,
  så Bilka 150g/200g ikke blot bliver tal i `PACKAGE`-dimensionen.
- Udvid opskriftseditoren: f.eks. `1 pakke skinkestrimler` (behov 200g)
  og `300g pasta` (behov 300g), samt `2 spsk. paprika`, når
  paprikaens gram/spsk.-faktor er bekræftet.
- Enhedsskift i editoren skal konvertere **samme fysiske behov** og må
  ikke ændre tal blindt. Indtastning af en ny mængde gør det nye valg
  eksplicit. Ukendt brofaktor blokerer kun beregning, ikke navigation.
- Offline outbox og realtime-konflikthåndtering for omregningsprofiler;
  en faktors historiske version må aldrig forandre en eksisterende
  opskrifts fastfrosne mængde.

## Fase 3: lagerbatcher / pakker med restmængder

Planlagt datamodel:

- `pantry_lots`: UUID, husstand, ingrediens, valgfrit konkret produkt,
  købskilde/transaktion, oprindelig base-mængde, resterende base-mængde,
  åbnet/forseglet, evt. bedst-før og kronologi, lot-version.
- Lagerets total beregnes **fra lot-restmængderne**, med mulighed for en
  migreret `ukendt emballage`-lot for de gamle totals.
- Madlavning bruger en deterministisk strategi (åbnede/først udløbende
  før uåbnede, med synligt forslag) og opdaterer både lot og samlet
  visning i én transaktion. Negative rester og dobbelte køb afvises.
- Bilka `bilka_record_purchase` skal fortsat sikre idempotent
  bogføring ved netværksretry, samtidig med at en ny lot oprettes
  atomisk. Indfør evt. unik transaktionsnøgle for indkøb flere gange
  i én uge, da den nuværende RPC er én-per-ingrediens/uge.
- Gammel `pantry_items.amount_base` må ikke længere have uafhængig
  betydning som en ekstra, separat beholdning efter migreringen.

## Fase 4: sikker migrering af gamle PACKAGE/COUNT og køb

- Vis krævede afklaringer pr. ingrediens, fx
  `1 gammel pakke skinkestrimler = ? g`.
- Migrér **samtidigt** opskriftbehov, forudsætninger, lager, købskvitteringer
  og relaterede basesummer til ny fysisk grundenhed, hvor det er sikkert.
- Hvis omregning mangler, bevar tidligere tal i en tydeligt mærket
  legacy-lot/legacy-dimension frem for at gætte.
- Lav backup og dataintegritetskontrol før og efter migrering, og sørg
  for at klienter med forskellige schema-versioner ikke skriver mod
  hinanden uhensigtsmæssigt.

## Validering før merge

Fra Android Studio (Windows):
```powershell
gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
gradlew.bat :app:assembleBenchmark
```

Test også på **to husholdningsenheder**, herunder offline/redigering/
genstart/tilslutning og Play-signering. Vigtige regressionsscenarier:

1. Lav opskrift med **1 pakke** (gammel `PACKAGE`-ingrediens) og en
   anden med **300 g** (MASS); efter gem og genstart vises de som valgt.
2. Skift kun ingrediensens foretrukne enhed og kontroller at
   opskriftsvisningen er identisk; `amountBase` uændret.
3. Test `200g` frozen requirement, der fortsat vises `1 pakke`,
   selv om en **ny** 150g-pakke vælges.
4. En gammel recipe uden `display_*` skal læses uden crash; fysisk
   lager/indkøb er uændret.
5. Slet og genskriv opskrifter, prøv dubletter, Room-restart og
   offline-outbox; visningsmetadata må ikke falde væk eller fejlagtigt
   sammenlægge forskellige visningsenheder.
6. SQL-migration skal verificeres, inden Cloud-funktionaliteten testes
   eller APK'en installeres på husstandens produktionskonto.

**Ingen Android SDK, Gradle-runner eller fysisk testtelefon er tilgængelig
i ChatGPT-værktøjsmiljøet.** Ingen build eller to-enhedstest hævdes
bestået. Version 1.5.0 / versionCode 13 – kontrollér at koden er ledig
i Play Console før en eventuel upload.
