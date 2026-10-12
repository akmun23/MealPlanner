# Fase 2a – sikker enhedsomregning og planlægning af hele købspakker

**Afhængighed:** Denne ændring bygger på den åbne PR #95. Den er ikke en færdig brugerfunktion til konverteringsprofiler, og den må ikke deployes isoleret mod produktionsskemaet.

## Funktioner indført i denne isolerede kodeleverance

- `QuantityPlanningRules.selectableUnits` viser kun enheder med en kendt faktor til ingrediensens **eksisterende** base.
- `QuantityPlanningRules.changeDisplayUnit` er en eksplicit ændring af **visningsenhed**, ikke mængde. `amountBase` forbliver identisk. Hvis den oprindelige mængde var beregnet med en usikker faktor, forbliver `conversionApproximate` sand, selv efter skift til gram.
- `QuantityPlanningRules.planPackages` beregner manglende fysisk mængde, antal hele pakker, indkøbt base og muligt overskud. F.eks. kræver en frossen opskriftsmængde på 200 g **to aktuelle 150 g-pakker**, hvis lageret er tomt; opskriften viser stadig `1 pakke`.
- For `QuantityType.PACKAGE` uden fysisk base nægtes fysisk indkøbsberegning; historiske pakker må ikke omfortolkes.
- Når flere konkurrerende profiler angiver samme enhed, afvises beregningen frem for vilkårligt at vælge den første. En databaseunikhed og konfliktstrategi skal følge.
- JUnit-tests dækker enhedsskift, anslåede faktorer, ukendte profiler, forsyning fra lager, ændret pakkestørrelse og konflikter.

## Ikke implementeret i denne fase 2a

Ingen profiloprettelse i UI, database-/Room-persistens, offline outbox, husstandssynkronisering, ny Bilka-servervalidering eller individuelle lagervarepakker. Ingen direkte kobling til checkout: indkøbsforslagene er rene beregninger og må **ikke** bruges til lagerkreditering uden servervalidering.

## Næste fase 2b

1. Beslut tabel og fysisk enhedsfelt for `ingredient_unit_conversions`; adskil ingrediens-defaults fra konkrete produktpakninger og historiske snapshots.
2. Implementér staging-testet RLS og konfliktsikker persistens med revisions-/husstands-ID samt lokal offlinekø.
3. Tilføj editor, hvor enhedsændring kalder `changeDisplayUnit`, og ændring af antal laver et nyt `freeze`. Manglende faktor kræver en eksplicit brugerbekræftelse, ikke en automatisk omregning.
4. Først derefter forbindes fysisk planlægning til indkøbslisten og Bilka, med tilsvarende servervalidering og transaktionssikker idempotens.

## Test- og sikkerhedsgate

Kør `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark` med den relevante Supabase staging-konfiguration. Kræv både ældre klienter og synk på to testtelefoner, inden mængdeændringerne bliver en produktionsegenskab. Der er endnu ingen grøn Android-build; GitHub Actions fejler indtil videre uden udførte trin.

## Kvalitetssikring og UI-finpudsning

- Opskriftsdetaljer med en **anslået** fysisk omregning viser nu teksten `(omregning anslået)`, også efter et efterfølgende enhedsskift.
- Omregningskonflikter behandles som manglende faktor (fail closed), så fremtidige offline-merge-fejl ikke kan vælge en vilkårlig pakkestørrelse.
- Kilde- og testfiler er gennemgået med syntaktisk delimiterkontrol. Det **erstatter ikke** Kotlin-kompilering eller en kørt JUnit-suite.
