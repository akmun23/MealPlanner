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