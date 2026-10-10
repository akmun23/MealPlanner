# Bilka fase 6 – hele pakker og korrekt Lager

## Brugeroplevelse

Den eksisterende indkøbsliste beholder sit layout. Hver ingrediens kan vise en kort linje med Bilkas standardprodukt, anbefalet antal hele pakker og estimeret pris. Den detaljerede produktvælger forbliver på sin egen Bilka-side.

Når varen er markeret købt og brugeren trykker Til Lager, åbnes en særskilt dialog. Her vises standardvaren, og man kan vælge en anden husstandsgodkendt vare kun til dette køb eller angive en manuelt købt vare. Standardproduktet ændres aldrig automatisk.

Anbefalet antal pakker = ceil(max(0, behov - lager) / pakkestørrelse). Købt mængde = faktisk antal pakker × pakkestørrelse. Hele den købte mængde tilføjes Lager, ikke blot den manglende opskriftsmængde. Ved ukendt pakkestørrelse kræves et eksplicit mængdevalg.

Forventet Bilka-pris vises separat. Kun en pris, brugeren selv indtaster, registreres som faktisk betalt.

## Server og transaktionssikkerhed

En ny service-role-begrænset SQL-funktion, bilka_record_purchase, gemmer den pågældende uges vare, pakkeantal, mængde og eventuel faktisk pris i shopping_product_overrides og opdaterer pantry_items atomisk. En ekstra registrering for samme ingrediens/uge afvises for at undgå dobbelt lagerføring.

Edge-handlingerne shopping-context og record-purchase kontrollerer brugerens JWT og medlemskab af den relevante husstand. En valgt Bilka-SKU skal være husstandens standard eller et foreslået produkt til ingrediensen. Mængde og enheder valideres på serveren.

Når der ikke findes et Bilka-produkt, eller i lokal/offline-tilstand, bevares appens hidtidige manuelle Til Lager-funktion.

## Fase 5 – SQL-rettelse

Den tidligere merge af fase 5 indeholdt en beskadiget SQL-migrationsfil. Denne PR erstatter den med en intakt migration med korrekt afgrænset PL/pgSQL-funktion og præcis ét cron-job. Den korrigerede fase-5-migration er anvendt i Supabase. Cron-jobbet er særskilt deaktiveret, fordi dens hemmelige token endnu ikke er konfigureret.

CI kontrollerer fremover, at migrationsfilernes dollar-citater og cron-definition er intakte.

## Deployment efter CI

1. Kør fase-6-migrationen supabase/migrations/20261010150000_bilka_purchase_transaction.sql.
2. Kør supabase/tests/bilka_phase6_contract.sql og sikkerhedskontroller.
3. Deploy bilka-prej med index.ts, prej.ts, discovery.ts, discovery_server.ts, catalog_server.ts, manual_search_server.ts og shopping_server.ts, med JWT-verifikation aktiveret.
4. Kontrollér i appen: behov 1,2 kg; lager 0; Bilka-pakke 1 kg -> 2 pakker -> 2 kg i Lager; dobbelttryk må ikke føre til 4 kg.
5. Kontrollér alternative engangsvarer, ukendt pakkestørrelse, faktisk betalt pris, enhedsfejl og to familiemedlemmer.

Ingen fase-6-migration eller deployment udføres, før du har verificeret den nye PR.
## Stabilitet og UI/UX-gennemgang (før frigivelse)

- Produktregistrering er nu på RPC-tilladelseslisten, og en Deno-regressionstest kontrollerer både, at transaktionen kan kaldes, og at andre privilegerede RPC'er forbliver blokerede.
- Fase-6-migrationens SQL er kontrolleret mod Supabase i en eksplicit transaktion med rollback. Funktionen kunne oprettes, og adgangsrettigheder for authenticated/service_role var korrekte; efter rollback blev det verificeret, at den ikke er installeret i produktion.
- Indkøbsliste: behov og Lager vises på én linje; Bilka-info højst én linje. Normal lagerregistrering fungerer fortsat uden Bilka-netforbindelse; en kompakt fejltekst giver mulighed for genindlæsning.
- Købsdialog: kun det aktuelle produkt vises først. Alternative produkter ligger i en højdebegrænset dropdown, og dialogens indhold er rullebart på små skærme. Knaptekster er forkortet.
- Produktvælger: billedkort med tre tekstlinjer, sammenlignelig pris og fast billedpladsholder. Manuel mærkesøgning er foldet sammen som standard; sorteringsvalg kan scrolle vandret uden at presse layoutet ud.
- Gem standardprodukt: knappen fremkommer inde i det valgte produktkort, så brugeren ikke skal rulle til slutningen af hele listen.
- Performance: indkøbsliste henter katalog og uge parallelt. Kataloget indekseres én gang, og både indkøbslisten og produktvælgeren kan hente **kun de relevante ingrediens-ID'er**. Åbning af appen bruger ingen Prej-kvote.
- Netværk: HTTP-kald har nu forbindelses-, socket- og samlet timeout, så en langsom API ikke kan få skærmen til at vente ubegrænset.
- Serverkontrol: ingredienser markeret 'Købes aldrig' kan ikke registreres som Bilka-køb, selv via direkte API-kald.

### Manuel UI-accept før bred frigivelse

Test på mindst én smal Android-skærm (ca. 320–360 dp) og med forstørret systemtekst. Kontrollér at ingen produktkort eller knapper ligger oven på andre, og at indkøbsrækkerne forbliver korte. Test rulle- og tastaturadfærd i købsdialogen samt dropdown med ti alternativer. Brug en stor ingrediensliste (100+ ingredienser), mange produktforslag, et produkt uden billede, forkerte enheder, ukendt pakkestørrelse og manglende netværk. Kontrollér korrekt dansk beløbsformat, og at ingen bruger ser rå SQL/API-fejl eller personlige API-nøgler.

Test et konkret køb i to enheder: 1,2 kg mangler, produkt 1 kg, 2 hele pakker registreres til 2 kg. Dobbelttryk/gentaget anmodning må ikke lægge 4 kg på Lager. Bekræft at et engangsalternativ ikke ændrer standardproduktet. Dette kræver Android-enhed/emulator og den fase-6-deployerede RPC efter grøn CI; det er endnu ikke udført.
