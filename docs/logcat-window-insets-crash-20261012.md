# Logcat-diagnose: crash ved alle undermenuer, 2026-10-12

## Faktisk exception fra den installerede app

To Logcat-udtræk indeholder i alt fem `FATAL EXCEPTION: main`
(i log-udtrækkene ca. 14:05–14:12):

```text
java.lang.ClassCastException:
  androidx.compose.foundation.layout.WindowInsetsHolder$Companion$$ExternalSyntheticLambda0
  cannot be cast to kotlin.jvm.functions.Function0
    at com.example.mealplanner.MainActivityKt.MealPlannerApp(MainActivity.kt:620)
```

Alle fem har samme signatur. Crashen opstår under
`androidx.compose.runtime.RecomposeScopeImpl.compose`, når brugeren
skifter til undermenuer. Stacktracen angiver kun de kompileringstilpassede
linjenumre i den **installerede** app; de er ikke nødvendigvis identiske
med linjenumre i nyere commits.

Dette **afkræfter hypotesen om fem uafhængige skærmfejl**. Fejlen er
en forkert typecast af en Compose-fremstillet WindowInsets-lambda til
en nul-argument Kotlin-lambda.

## Kodegennemgang / sandsynlig mekanisme

I `MealPlannerApp` blev `WindowInsets.isImeVisible` tidligere
evalueret i betingede Compose-grene, når hovedfanen var åben og når
de nederste overlays blev placeret. Umiddelbart derefter blev
`SyncStatusBadge(onConflict = { ... })` oprettet omkring den af
Logcat angivne linje 620.

`WindowInsets.isImeVisible` kalder
`WindowInsetsHolder.current()` og involverer `remember`-kald
internt. En forkert funktion gemt eller genbrugt på samme
kompositionsposition ville kunne give netop den observerede
`WindowInsetsHolder...` -> `Function0`-cast.

**Denne mekanisme er en stærk kildekodehypotese, ikke bevist årsag
til selve compiler-/runtime-misforholdet.** Kotlin Compose-compiler,
D8/R8 og betinget `remember` er mulige udløsere. Ingen bestemt
upstream-fejl er påvist.

## Målrettede ændringer i 1.3.2 / versionCode 10

1. Læs `WindowInsets.isImeVisible` **ubetinget én gang** i
   `MealPlannerApp`, før den dynamiske `page`-gren.
2. Brug den gemte Boolean til både tab-bundnavigation og overlay-indryk.
3. Flyt snackbar og synkroniseringsbadge til den separate
   `BoxScope.GlobalSyncOverlays`-composable, som har egne
   kompositionsgrupper.
4. Giv `SyncStatusBadge` en husket, stabil
   `onConflict`-callback i stedet for en ny lambda oprettet direkte
   efter betinget WindowInsets-kode.
5. Flyt `WindowInsets.isImeVisible` til før den betingede
   save-bar i `RecipeEditorScreen` (samme types af kompositionsrisiko).
6. Udvid `subpageNavigationSmoke` til fem gentagne åbne/lukke-cyklusser
   gennem Indstillinger, og derefter Ny opskrift og Ny ingrediens.

**Ingen lager-/indkøbs- eller Supabase-transaktioner er ændret** af
denne specifikke crashrettelse. Rettelser fra version 1.3.1 bevares.

## Verifikationsprocedure og sikker release-gate

* Før upload: bekræft, at Play Console ikke allerede har anvendt
  `versionCode=10`.
* Kompilér fra samme commit med korrekte, virkelige `local.properties`
  uden at committe hemmelige tokens:
  ```bash
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :app:bundleRelease \
    :app:assembleUnoptimized :app:assembleBenchmark \
    :macrobenchmark:assembleBenchmark --stacktrace
  ```
* Kontrollér den endelige AABs obfuskering/symbolmetadata:
  ```bash
  python3 scripts/check_release_bundle_metadata.py \
    app/build/outputs/bundle/release/app-release.aab
  ```
* **Afvikl på testtelefon/afgrænset internt Play-spor:**
  `MealPlannerBenchmark#subpageNavigationSmoke` og
  `MealPlannerBenchmark#existingItemSubpagesSmoke`. Skift også
  manuelt mellem eksisterende opskrifter, editorer, Indstillinger,
  Synkronisering og Sikkerhedskopi flere gange; kontrollér
  soft keyboard visning og Gem-knapperne. Begge benchmarktests
  er tænkt som read-only.
* Hvis samme `ClassCastException` fortsætter, sammenlign
  `:app:assembleUnoptimized` med den samme kildekodes R8-optimerede
  `:app:assembleBenchmark` på en **separat testinstallation**
  for at afgøre om R8 er medvirkende. Begge bruger debug-signatur,
  men må ikke installeres hen over en Play-signatur, hvis det
  kræver afinstallation. Bevar Room/offline outbox og back up først.
* Logcat ved fortsat crash:
  ```bash
  adb logcat -b crash -d > mealplanner-132-crash.txt
  ```
  Fjern personfølsomme oplysninger inden deling og brug eventuel
  præcis release-`mapping.txt` til retrace.

### Status

Kodepatch og regressionstest er skrevet; ingen Android SDK eller
testtelefon er tilgængelig i værktøjsmiljøet. **Hverken releasekompilering
eller runtime-forbedring kan godkendes, før ovenstående køres.**
At ændre GitHub-kode ændrer ikke appen, der allerede er installeret.
