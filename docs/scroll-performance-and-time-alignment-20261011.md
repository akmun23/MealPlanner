# Scrolling og stabil "Tid"-placering

Denne ændring adresserer to konkrete problemer rapporteret på telefonen:

1. **"Tid" flugter ikke** med de øvrige oplysninger på dagkort. Kategori-/sandwichmærker wrap'er uforudsigeligt, og når tidsfilteret deler række med dem, bliver layoutet visuelt skævt. Nu er mærker i en selvstændig FlowRow og tidsgrænsen i sin egen række med en venstrestillet label og et højrestillet filter. Også når mærker optager flere linjer, står tidsvælgeren konsekvent samme sted i sit eget layout. På færdigregistrerede dage skjules filteret fortsat.
2. **Hakkende scrolling:** efter husstanden er indlæst, begyndte appen at forudindlæse op til **150 billeder** (alle opskrifter og ingredienser) efter kun 2,5 sekunder. Den baggrundsaktivitet kunne konkurrere med de faktiske skærmbilleder om netværk, billeddekodning og CPU/netværk under en fling. Denne brede prefetch er nu fjernet, så Coil kun indlæser de billeder, der bliver brug for på skærmen. Desuden flyttes signering/opslag af billed-URL'er til `Dispatchers.IO`, så preferences/URL-opslag ikke udføres i UI-dispatcheren.

Billedcache i Coil er uændret: billeder, der allerede er indlæst, kan fortsat genbruges fra hukommelse/disk. **Trade-off:** ved første visning kan et billede vise placeholder et øjeblik længere, men uden baggrundsarbejde for 150 andre billeder.

## Dokumentér effekten på telefonen

`macrobenchmark/src/main/java/com/example/mealplanner/macrobenchmark/MealPlannerBenchmark.kt` måler nu hurtig scrolling med `FrameTimingMetric` på **Madplan, Opskrifter, Lager og Indkøb**. `macrobenchmark/README.md` beskriver sammenligning af varme/kolde billedcaches og relevante percentiler. Kør på en fysisk enhed med fyldte lister og samme data/OS. En debug-APK og en release-lignende APK er ikke sammenlignelige.

## Begrænsninger

GitHub Actions-minutterne er opbrugt. Ingen Android-build, Compose-screenshot-test eller frame-time-benchmark er kørt for denne PR. Ændringerne er kontrolleret statisk for balance i Kotlin-blokke og duplikerede imports, men performanceforbedringerne er en **konkret hypotese, ikke målt resultat**. En APK og fysisk enhedstest er nødvendig for at sige, at hakket er væk. Release-optimering/R8 er **ikke** blevet aktiveret, da projektets README dokumenterer en tidligere opstartsfejl med optimeret release.

## Manuel validering
- Madplan med nul/én/flere kategorier, sandwichdag og lange kategorinavne: Tid står i en separat ensartet række.
- Færdigregistreret dag: hverken tidvælger eller redigeringsmuligheder vises.
- Hurtig scrolling fra toppen af opskrifter/lager og gennem dagkort: sammenlign før/efter på samme telefon og med samme billed-cachetilstand.
- Kontroller cloud-signerede fotos efter netværksskifte, offline-funktion og app-genstart.
