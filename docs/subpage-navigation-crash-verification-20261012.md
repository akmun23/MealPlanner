# Undermenu-crash: fejlsøgning og godkendelseskriterier

## Symptom og rækkevidde

Brugeren rapporterer, at appen crasher ved åbning af **alle typer undermenuer**:
Indstillinger, vis/rediger opskrift, ny opskrift, rediger/tilføj ingrediens.
`MealPlannerApp`s hovedfaner er anderledes opbygget; de berørte
undermenuer benytter alle `SubpageScaffold` med Material3
`Scaffold`/`TopAppBar` og en AutoMirrored tilbagepil.

**Rodårsagen er ikke bevist.** Hverken crash-stacktrace fra installeret
release eller en gennemført fysisk release-test var tilgængelig ved ændringen.
R8 er en mulighed, ikke en verificeret årsag; om fejlen også rammer Debug
skal undersøges.

## Hvad PR'en konkret ændrer

- Menunavigation ændrer Compose-state synkront uden en unødvendig
  `scope.launch` for simple `page = ...`-opgaver; swipe-/pageranimationer
  bevarer den coroutine, de faktisk kræver.
- Fjerner forældede enum `values()`-opslag til fordel for `entries`.
- Android Compose UI-test kontrollerer rendering af fælles
  `SubpageScaffold`, titel, indhold og tilbageknap.
- JVM-test forsøger eksplicit at linke pilikonet brugt i alle undermenuer.
- R8-optimeret makrobenchmark `subpageNavigationSmoke` åbner Indstillinger,
  Ny opskrift og Ny ingrediens, kontrollerer fælles topbar og går tilbage,
  **uden at gemme, slette, registrere køb eller foretage ændringer**.

Dette er en reproduktions-/regressionstestramme og stabilitetsoprydning,
**ikke dokumentation for et fjernet release-crash**.

## Få den reelle exception fra telefonen

På testtelefonen med APK'en der faktisk crasher:

1. Tilslut Android Studio via USB debugging.
2. Start Logcat og filtrér på `package:com.mealplan.mealplanner` og
   `AndroidRuntime` eller søg efter `FATAL EXCEPTION`.
3. Åbn en af de berørte undermenuer, og gem **den første** crash-stacktrace
   med exceptionstype, `Caused by` og tilhørende linjer.
4. Alternativt umiddelbart efter fejlen:
   ```bash
   adb logcat -b crash -d > mealplanner-crash.txt
   ```
   Gennemse filen for andre apps, brugeridentifikatorer eller tokens, og
   fjern dem før deling. Del aldrig adgangsnøgler, cookies eller private
   Supabase data.
5. Hvis sporene er R8-obfuskerede, brug `mapping.txt` fra **den eksakt samme
   releasebuild**. En mapping fra en anden version kan give forkert forklaring.

## Release-lignende navigationstest

**Brug kun en ekstra testtelefon eller en installation, der er korrekt
sikkerhedskopieret**. Benchmark-varianten er signeret med debugnøgle og kan
ikke installeres oven på en Play-signeret version uden at fjerne den gamle app.
Fjern IKKE den eneste produktionsinstallation eller dens lokale Room/outbox.

Installér testbuilden med en forberedt husstand og kør:

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.mealplanner.macrobenchmark.MealPlannerBenchmark#subpageNavigationSmoke
```

Den skal åbne alle tre nævnte skærme og returnere uden process crash.
Makrobenchmarken måler en anden undersøgelsestype end scrolling; dens
hovedformål her er, at teste en release-lignende R8-variant på fysisk telefon.

## Release-gate

Udrul ikke til produktion før:

- [ ] `:app:assembleRelease`, `:app:bundleRelease` og relevante tests består.
- [ ] **Både** R8-release og Debug afprøves, inkl. Settings, recipe detail,
      recipe editor, ingredient editor, nye opskrifter og ingredienser.
- [ ] Back-navigation samt fortsat login, Room/offline outbox, sync og
      Supabase-serialisering virker.
- [ ] Et reelt release-Logcat-spor identificerer den oprindelige exception,
      og en korreleret rettelse er efterprøvet i det optimerede build.
- [ ] Ingen nulstilling af appdata eller tab af ventende synkronisering.

Den eksisterende `app/src/androidTest` kører almindeligvis mod
Debug. Den alene er **ikke** evidens for, at en R8-optimeret Play-release
er fejlfri.
