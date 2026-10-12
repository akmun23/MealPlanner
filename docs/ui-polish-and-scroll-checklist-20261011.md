# UI/UX og scrolling – oktober 2026

## Afgrænsning

De vedhæftede Android-skærmbilleder viser store lodrette afstande og ujævnt justerede felter i
**Madplan**, **Opskrifter** og **Rediger opskrift**. Denne PR forbedrer Compose-layout/performance
og tilføjer låsning af færdigregistrerede måltider. Bilka/Prej-API og Supabase-skema ændres ikke.

## Ændringer

- Madplanens første store `LazyColumn`-element er opdelt i seks nøglede oversigtssektioner. Visning
  og måling af ikke-synlige sektioner udsættes ved scrolling, mens dagene fortsat bruger stabile
  nøgler.
- Mindre luft i madplansoversigt og dagkort. Overflødig status og redigeringshandlinger for allerede
  spiste måltider er fjernet.
- **Tid står konsekvent til højre i den øverste metadata-række**. Kategorier/sandwichmærker ligger i
  en separat FlowRow til venstre og brydes uafhængigt af tidsknappen. Statusmærkerne har mindst
  samme højde som tidsfilteret.
- Opskriftsfiltre grupperes med samme bredde og forkortelse af lange valgnavne frem for en skæv,
  overflowende række. Lagerfiltre har ensartet fuld bredde, og enhedsvælgeren i ingrediensmængder
  flugter lodret med tekstfeltet.
- I opskriftseditoren er hvert fremgangsmåde-trin et separat `itemsIndexed`-element med stabilt
  trin-id og en `contentType`. Kun synlige trin skal måles; et langt trin kan redigeres og
  placering, sletning og tilføjelse er bevaret.
- I opskriftsdetaljer bliver lange fremgangsmåder også nøglede lazy-items; lager-/indkøbsrækker har
  faste `contentType`-værdier til genbrug.
- `Kan laves`-udregninger caches uafhængigt af søgetekst. Ingredienser i redigering slås op via en
  afledt ID-indeksering i stedet for gentagne lineære søgninger.
- Miniaturebilleder anvender `AsyncImage` med bagvedliggende fallback frem for
  `SubcomposeAsyncImage`, der medfører ekstra subcomposition ved scrolling.
- Den faste `Gem opskrift`-bjælke skjules, mens tastaturet er åbent, så tekstfeltet får mere plads.
  Knappen vises igen når tastaturet lukkes.

## Kontrolpunkter i Android

1. Åbn **Madplan** på den aktuelle uge, forrige/næste uge; kontrollér at dagens kort kun vises i den
   aktuelle uge, og at ugeknapperne fortsat fungerer.
2. Kontrollér hjemmedage, sandwichdag, flyt sandwich, lås, hjemme-toggle, tidsfilter, reroll, skift
   ret, frokost og færdigregistrering. Et registreret måltid skal kunne rettes. **Tid skal forblive
   i øverste række til højre, både med nul, én og flere mærker, og ved høj skriftstørrelse.**
3. Åbn **Opskrifter** med lange kategorinavne og smal skærm; kontrollér justerede filtre,
   menuindhold, søgning og `Kan laves`. Kontrollér også at lagerfiltre er lige brede og at mængde +
   enhedsvælger er på linje i opskriftseditor.
4. Opret/redigér en opskrift med 20–50 trin og undertrin; scroll hurtigt, indtast i et trin, flyt
   op/ned, fjern, tilføj og gem. Kontrollér trinnumre og at fokus/tekst ikke springer til et andet
   trin efter flytning.
5. Med tastaturet åbent: kontrollér at trinfeltet er synligt uden at være dækket af Gem-bjælken.
   Skjul tastaturet, og kontrollér at Gem returnerer og at ugyldige felter stadig giver fejl.
6. Kontrollér stor skrifttype, mørkt tema samt liggende skærm og kort skærmhøjde.

## Spiste måltider og datalås

- Ved `Færdig – registrér lagerforbrug` tilføjes historik, lagerforbrug og lås til samme
  offline-synkroniseringsbatch, når ugen er aktiv.
- En allerede spist dag viser **ikke** "Spist og registreret", "Ret spist ret" eller
  redigeringsknapper i madplanen. Den låste dag har kun den almindelige lås i dagoverskriften.
- Låsen håndhæves af `MealPlannerState` for ændring af ret, hjemme/ude, tidsfilter,
  sandwichplacering samt tilfældig genrulning. Også historiske poster, der ikke har fået et
  `lockedDays`-flag af ældre appversioner, er beskyttet.
- Når en uge har mindst én spist ret, er `Ny madplan` og daglig `Ny ret` spærret.
  `Lås ugens genplanlægning op` kræver bekræftelse, og allerede spiste dage forbliver låst. Lås den
  enkelte dag op separat for rettelser.
- Oplåsning af et afsluttet måltid kræver bekræftelse via dagslåsen eller fra historik.
  Historikrettelser ændrer **ikke** tidligere lagerforbrug automatisk. Oplåsninger varer kun i denne
  session/viste uge; næste appstart låser igen.
- Ugegrænser er datoafhængige: en registrering søndag spærrer ikke for generering i den følgende
  uge. Nye JUnit-tests dækker ugedatoer og at `WeeklyPlanner` bevarer en spist og låst ret.

### Manuelle regressionsscenarier

1. Færdigregistrér mandag. Kontrollér at dagens knap og statusteksten forsvinder, låseikonet bliver
   låst, og at `Skift ret`, tidsfilter, hjemme-toggle og `Ny ret` ikke kan ændre mandag.
2. Kontrollér at `Ny madplan` er låst for ugen. Vælg `Lås ugens genplanlægning op` og bekræft; prøv
   at generere igen. Mandagens spiste ret skal forblive uændret.
3. Lås mandag eksplicit op ved låseikonet, ret den planlagte ret, og kontrollér at
   historik/lagerforbrug stadig er særskilte. Luk og genåbn appen: dagen er låst igen.
4. Registrér en ret som spist søndag, skift til næste uge, og kontrollér at `Ny madplan` er aktiv
   der uden at ændre søndagens registrering.
5. Åbn Historik, prøv at redigere en spist dato, bekræft oplåsning og ret eventuelt historikken.
   Kontrollér at den låser igen, og at lagerbeholdningen ikke justeres automatisk.

## Performancevalidering

Ændringerne reducerer det antal komponenter, der lægges ud samtidigt, men er **ikke** en målt
garanti for fravær af hakken. Sammenlign `master` og denne branch med samme Android-enhed,
opskrifter og lagerdata. Registrér janky frames (f.eks. Perfetto/Frame Timeline eller Macrobenchmark
`FrameTimingMetric`) under gentagen scrolling i Madplan, Opskrifter og en lang opskriftseditor;
medtag både median, p95/p99 og antal langsomme frames.

## Drift og rollout

Kun Android-kode, en JUnit-testfil og dette dokument. Ingen migration/deployment i denne PR.
Ændringerne fra tidligere PR #81 og #82 er allerede på `master`; Bilka/Prej Edge Functions kræver
deres særskilte normale deployment, og en ny APK er nødvendig før telefonen viser UI-ændringerne.

## GitHub Actions – aktuelt blokeret

Ved første push til PR #83 og efterfølgende commits afsluttede **alle tre workflows** (Android
build, Bilka Prej Edge checks, Startup release smoke) med `failure` efter ca. 2–3 sekunder. GitHub
rapporterede **ingen runner, ingen jobtrin og ingen brugbare logs**. Dette er ikke en
kompilatorfejl, der kan udlæses af denne kørsel. **Sammenligning:** Workflows var grønne på `master`

11. oktober ca. kl. 13:07 UTC, men alle tre stoppede før runnerstart på `master` efter merge #81/#82
    ca. kl. 13:15 UTC. Der er ingen ændringer i de tre workflowfiler i PR #83. Ejeren har bekræftet,
    at GitHub Actions-minutterne er opbrugt. Derfor blev PR'en gennemgået statisk og merges med
    udtrykkelig accept af, at Android-build og enhedstest ikke kunne køres. Når der igen er
    tilgængelige minutter, bør alle workflows køres, og APK'en testes fysisk, før en ny version
    distribueres. Ejerens kontobilling kan ikke ændres via repository-kode. Hvis betaling/kvoter er
    i orden, kontaktes GitHub Support med run-id. Ingen workflow-ændring kan garantere runnerstart,
    når GitHub stopper jobbet før første step. Når runnerproblemet er rettet, genkør workflows og
    udled først derefter eventuelle konkrete Kotlin-/Deno-fejl. CI og fysisk enhedstest er altså
    fortsat **ikke** bestået.
