# Android Studio: compileDebugKotlin, R8 og lokale Git-commits

## Kontrollerede compilefejl efter seneste commit

Den seneste commit på `master` før denne rettelse (`397115e0d`,
"Errors to be corrected") havde konkrete utilsigtede ændringer:

1. `MainActivity.kt` fjernede deklarationen af
   `openSyncConflictDialog`, mens `GlobalSyncOverlays(...,
   onConflict = openSyncConflictDialog)` stadig brugte den.
2. `MainActivity.kt` fjernede importen
   `androidx.compose.foundation.layout.windowInsetsPadding`, mens
   `Modifier.windowInsetsPadding(WindowInsets.statusBars)` stadig var i
   `SubpageScaffold`.
3. Ubrugte `ArrowBack`, `Icons`, `Icon`, `IconButton` og
   `ExperimentalMaterial3Api` imports/opt-ins var tilføjet, selv om
   `SubpageScaffold` allerede håndterer topbaren.
4. To brede `-keep ... icons.**`-regler var lagt ind uden en dokumenteret
   R8-rodsårsag. De kan bevare mange ellers ubrugte ikonklasser.
5. `.idea/deploymentTargetSelector.xml` var versionsstyret, inklusive
   værtsmaskinens enhedsvalg. Filen er nu ignoreret og fjernet fra Git.

Ingen af disse oprydningsændringer fjerner appfunktioner eller ændrer
lager/synkronisering/databasemigrationer.

## Kan debug indeholde R8?

Ja, man kan bede Gradle om shrinking på et debuggable build. Men
**fuld R8-optimering/obfuskering kører ikke som i release**, når
`isDebuggable=true` (nyere R8-versioner deaktiverer nogle optimeringer
for at bevare fejlsøgningsmuligheden).

Projektet har derfor to klare formål:

| Variant | Debuggable | Fuld R8 | Signering | Anvendelse |
|---|---:|---:|---|---|
| `debug` | Ja | Nej | Debugnøgle | Kotlin/Compose-debugging og breakpoints |
| `benchmark` | Nej | Ja, arver `release` | Debugnøgle | Logcat og release-lignende R8-crash |
| `unoptimized` | Nej | Nej | Debugnøgle | Sammenligning ved R8-fejl |
| `release` | Nej | Ja | Release/Play | Produktion |

Brug `benchmark` i stedet for at aktivere R8 i normal `debug`.
Det giver en mere pålidelig reproduktion af tidligere
`WindowInsetsHolder` -> `Function0` `ClassCastException` uden
at kompromittere hurtige debug-builds.

## Test fra Android Studios Terminal

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleBenchmark :app:assembleUnoptimized
```

På Windows kan du bruge `gradlew.bat` i stedet for `./gradlew`.

For release-lignende test i emulatoren: Vælg `app` → `benchmark`
under **Build Variants** og brug **Run** (ikke en debugger-session),
eller byg med ovenstående kommando og installér debug-signeret
`app/build/outputs/apk/benchmark/app-benchmark.apk` på en
**separat emulator**. Kontroller crash i Logcat:

```text
package:com.mealplan.mealplanner
```

Appen kan ikke debugges med breakpoints som `benchmark`, men
Logcat-feltet `FATAL EXCEPTION` forbliver nyttigt.

**Afindstallér aldrig produktions-/Play-versionen blot for at få
benchmark-buildet installeret.** De er normalt signeret med forskellige
certifikater. En afinstallation kan slette lokale Room/outbox-data,
som endnu ikke er synkroniseret.

## Håndtering af lokale commits

Før pull i Android Studio: Undersøg **Git → Commit → Local Changes**
og se efter uprøvede lokale ændringer. På terminalen:

```bash
git status
git log --oneline --decorate -8
git diff
git fetch origin
git log --oneline --left-right HEAD...origin/master
```

Hvis der er lokale ændringer, der skal bevares, så gem dem i en
midlertidig branch, commit eller `git stash push -u` før
`git pull`. Rebasér/merge derefter bevidst, og kontroller konflikter.
Undlad `git reset --hard`, `git clean -fd` eller manuel sletning
af appdata uden en verificeret backup.

## Begrænsning

Denne rettelse validerer **symboler, imports og lokal kildekodehygiejne**
ved statisk gennemgang. Der er ikke et Android SDK eller en fysisk
testtelefon i dette værktøjsmiljø; `compileDebugKotlin`,
JUnit/Compose-tests og R8-build er **ikke kørt her**.
