# Bilka fase 4: adskilte produktskærme og manuelt valg

## Navigation

Indstillinger → **Bilka-produkter og priser** åbner en **selvstændig oversigt** (ny `BilkaProductsScreen`).
Her kan man søge efter ingredienser og filtrere på *Alle*, *Klar*, *Valgte* og *Mangler*.
Et tryk på en ingrediens åbner en **egen produktvælger-side** (ny `BilkaProductPickerScreen`), med normal tilbage-navigation. De eksisterende madplans-, opskrifts-, lager- og indkøbsvisninger ændres ikke.

Import og Prej-nøgler administreres i den eksisterende, separat navigerbare `BilkaApiSettingsScreen`. Dens lange ingrediensliste er erstattet af en kort forklaring og en tydelig knap tilbage til produktoversigten. Ingen forslag bliver mast ind i indstillinger.

## Produktvalg

- Op til 10 automatisk fundne produkter pr. ingrediens fra tidligere importer.
- Mærke, produktnavn, pakningsstørrelse, pakkepris, sammenlignelig enhedspris, tilbud og valgfri billedreference.
- Vis produktfotos med Coil når der er gyldige URL'er; vis fast, ensartet placeholder når billedet mangler, fejler eller fjernes.
- Sortering på laveste kg-/literpris **kun hvis enhederne er sammenlignelige**, ellers efter forslagets relevans. Filter på mærke/produktnavn.
- Det aktuelt gemte produkt er klart markeret. Brugerens valg gemmes **kun** ved knappen *Gem som fast produkt* / *Skift standardprodukt*.
- To husstandsmedlemmer ser samme standardprodukt. Ingen automatisk omlægning af SKU ved nye priser, tilbud eller sortering.
- **Mangler mit mærke?** En manuel Prej-søgning (højst ét nyt kald pr. søgning; gratis, hvis det ligger i 7-dages søgecache) på en enkelt ingrediens. Søgeresultater bliver kun kandidater, aldrig et automatisk standardvalg.

## API og datasikkerhed

Edge Functionen får to nye handlinger:
- `catalog`: efter JWT- og husstandsmedlemskontrol returneres kun husstandens kandidat- og standardrelationer, samt tilhørende produkter.
- `choose-product`: kræver et konkret produkt-ID, som er et *SUGGESTED* kandidatmatch for denne ingrediens. Gemmer præcis det valgte ID i `ingredient_bilka_defaults`.
- `manual-search`: kræver husstandsmedlemskab og konkret ingrediens i husstanden. Bruger brugerens egen Prej-key via Vault og atomisk kvotereservation. Lagrer kun Bilka-produkter, max 10 kandidater pr. forespørgsel, og genbruger cache.

Nøgler opbevares ikke på telefonen, i koden eller i GitHub. Den manuelt gemte nøgle fra tidligere samtale er endnu **ikke live-valideret** mod Prej, eftersom et rigtig kald skal udføres af en autentificeret bruger. Appen har allerede knappen *Kontrollér min gemte nøgle*, som bruger én budgetteret API-request.

### Produktbilleder og rettigheder

Prej returnerer til tider URL'er til billeder fra tredjepart, men giver ikke i sig selv licens til at vise dem. Inden bred distribution skal brugsretten til billedvisningen være afklaret. Implementeringen har fallback, når der ikke kan vises et billede; produktnavn, pris og valg fungerer uden foto. Se https://prej.dk/vilkaar-api.

## Test og release

- [ ] Android CI: Kotlin-unit tests af sortering, inkompatible enhedspriser, valg af eksakt SKU og API-datamapping.
- [ ] Deno CI: `catalog_test.ts` og `manual_search_test.ts`, plus tidligere faser. Fake API; ingen virkelig Prej-kvote brugt af testene.
- [ ] Efter godkendt CI: Deploy ny version af `bilka-prej` med `index.ts`, `prej.ts`, `discovery.ts`, `discovery_server.ts`, `catalog_server.ts`, `manual_search_server.ts`, med JWT-verifikation.
- [ ] Intet nyt databaseskema i fase 4; tidligere migreringer er nok.
- [ ] Manuelt med to husstandsmedlemmer: åbn oversigt, søg/match, vælg mærke + størrelse, kontroller delt standard og prisopdatering uden produktskift.
- [ ] Med personlig Prej-nøgle: verificér `/v1/stats` fra appen, og udfør ét manuelt Bilka-søgekald. Dokumentér faktisk svar og API-kvote; test aldrig ved at hardcode eller logge nøglen.

Fase 5 er natlige prisopdateringer og indkøbsintegration hører først til fase 6.
