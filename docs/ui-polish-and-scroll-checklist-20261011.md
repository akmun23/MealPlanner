# UI/UX og scrolling – oktober 2026

## Afgrænsning
De vedhæftede Android-skærmbilleder viser store lodrette afstande og ujævnt justerede felter i **Madplan**, **Opskrifter** og **Rediger opskrift**. Dette er en afgrænset Compose-layout-/performance-PR uden ændringer i planlægningsregler, opskriftdata, Bilka/Prej-API eller Supabase.

## Ændringer
- Madplanens første store `LazyColumn`-element er opdelt i seks nøglede oversigtssektioner. Visning og måling af ikke-synlige sektioner udsættes ved scrolling, mens dagene fortsat bruger stabile nøgler.
- Mindre luft i madplansoversigt og dagkort. Registreret måltid og `Ret spist ret` står i én responsiv række.
- **Tid står konsekvent til højre i den øverste metadata-række**. Kategorier/sandwichmærker ligger i en separat FlowRow til venstre og brydes uafhængigt af tidsknappen. Statusmærkerne har mindst samme højde som tidsfilteret.
- Opskriftsfiltre grupperes med samme bredde og forkortelse af lange valgnavne frem for en skæv, overflowende række. Lagerfiltre har ensartet fuld bredde, og enhedsvælgeren i ingrediensmængder flugter lodret med tekstfeltet.
- I opskriftseditoren er hvert fremgangsmåde-trin et separat `itemsIndexed`-element med stabilt trin-id og en `contentType`. Kun synlige trin skal måles; et langt trin kan redigeres og placering, sletning og tilføjelse er bevaret.
- I opskriftsdetaljer bliver lange fremgangsmåder også nøglede lazy-items; lager-/indkøbsrækker har faste `contentType`-værdier til genbrug.
- `Kan laves`-udregninger caches uafhængigt af søgetekst. Ingredienser i redigering slås op via en afledt ID-indeksering i stedet for gentagne lineære søgninger.
- Miniaturebilleder anvender `AsyncImage` med bagvedliggende fallback frem for `SubcomposeAsyncImage`, der medfører ekstra subcomposition ved scrolling.
- Den faste `Gem opskrift`-bjælke skjules, mens tastaturet er åbent, så tekstfeltet får mere plads. Knappen vises igen når tastaturet lukkes.

## Kontrolpunkter i Android
1. Åbn **Madplan** på den aktuelle uge, forrige/næste uge; kontrollér at dagens kort kun vises i den aktuelle uge, og at ugeknapperne fortsat fungerer.
2. Kontrollér hjemmedage, sandwichdag, flyt sandwich, lås, hjemme-toggle, tidsfilter, reroll, skift ret, frokost og færdigregistrering. Et registreret måltid skal kunne rettes. **Tid skal forblive i øverste række til højre, både med nul, én og flere mærker, og ved høj skriftstørrelse.**
3. Åbn **Opskrifter** med lange kategorinavne og smal skærm; kontrollér justerede filtre, menuindhold, søgning og `Kan laves`. Kontrollér også at lagerfiltre er lige brede og at mængde + enhedsvælger er på linje i opskriftseditor.
4. Opret/redigér en opskrift med 20–50 trin og undertrin; scroll hurtigt, indtast i et trin, flyt op/ned, fjern, tilføj og gem. Kontrollér trinnumre og at fokus/tekst ikke springer til et andet trin efter flytning.
5. Med tastaturet åbent: kontrollér at trinfeltet er synligt uden at være dækket af Gem-bjælken. Skjul tastaturet, og kontrollér at Gem returnerer og at ugyldige felter stadig giver fejl.
6. Kontrollér stor skrifttype, mørkt tema samt liggende skærm og kort skærmhøjde.

## Performancevalidering
Ændringerne reducerer det antal komponenter, der lægges ud samtidigt, men er **ikke** en målt garanti for fravær af hakken. Sammenlign `master` og denne branch med samme Android-enhed, opskrifter og lagerdata. Registrér janky frames (f.eks. Perfetto/Frame Timeline eller Macrobenchmark `FrameTimingMetric`) under gentagen scrolling i Madplan, Opskrifter og en lang opskriftseditor; medtag både median, p95/p99 og antal langsomme frames.

## Drift og rollout
Kun Android-kode og dette dokument. Ingen migration/deployment i denne PR. Ændringerne fra tidligere PR #81 og #82 er allerede på `master`; Bilka/Prej Edge Functions kræver deres særskilte normale deployment, og en ny APK er nødvendig før telefonen viser UI-ændringerne.

## GitHub Actions – aktuelt blokeret
Ved første push til PR #83 og efterfølgende commits afsluttede **alle tre workflows** (Android build, Bilka Prej Edge checks, Startup release smoke) med `failure` efter ca. 2–3 sekunder. GitHub rapporterede **ingen runner, ingen jobtrin og ingen brugbare logs**. Dette er ikke en kompilatorfejl, der kan udlæses af denne kørsel. Kontrollér banneret øverst på hver mislykket GitHub Actions-run, især om der er et problem med konto-/organisationens Actions-adgang, kø/runner eller forbrug/billing. Når runnerproblemet er rettet, genkør workflows og udled først derefter eventuelle konkrete Kotlin-/Deno-fejl. CI og fysisk enhedstest er altså fortsat **ikke** bestået.
