# Bilka fase 3 – produktimport og delt søgecache

Fase 3 gør knappen **Indstillinger → Bilka – min Prej API-nøgle → Hent manglende produkter**
funktionsdygtig. Den henter **kandidater**, ikke brugerens standardvarer. Brugeren vælger et konkret
mærke/produkt/pakkestørrelse i fase 4. Ingen billeder eller produkter vises i en fuld produktvælger
endnu.

## Hvordan importen sparer de 25 API-kald

1. Læs husstandens ingredienser, undtag "Købes aldrig" og ingredienser, som allerede har valgt et
   eksakt Bilka-produkt.
2. Genbrug kandidater, som allerede er fundet i husstanden. Andre husstandes private valg genbruges
   ikke.
3. Gruppér nært beslægtede ingrediensnavne i fælles Prej-søgninger, eksempelvis **Mel + Pizzamel →
   mel**, **Tomat + Cherrytomater + Tomatpuré → tomat** og **Løg + Rødløg + Hvidløg → løg**.
4. Genbrug resultater fra en **fælles serverstyret 7-dages søgecache**, så to husstandsmedlemmer
   ikke behøver at gentage søgninger.
5. Hent op til **100 produktresultater pr. tekstsøgning** fra Prej med \`chain=bilka\`. Vælg kun
   produkter med en konkret Bilka-pris; aldrig BilkaToGo-priser.
6. Match lokale produkter konservativt mod ingrediensnavn. Gem **op til 10 forslag pr. ingrediens**
   og vis mærke, navn, pris, størrelse og eventuel billed-URL i produkttabellen til fase 4.
7. Hvis en bred søgning ikke gav kandidater, forsøger backend en **mere specifik ingredienssøgning**
   inden for det resterende budget.
8. Hvis en søgning har mere end 100 resultater, erklærer vi den **ikke** fejlagtigt "ingen match":
   den får status \`INCOMPLETE\` og kræver præcisering i fase 4.

Prej har **ikke** et dokumenteret endpoint til 50 selvstændige søgninger i ét kald; deres 500-ID
batch-endpoint kan kun hente kendte produkter. Gruppering forbedrer førstegangsimporten, men antal
nødvendige kald afhænger af hvor mange ingredienser der kan dækkes af samme søgning.

### Kvotegrænser

- Højst **8 nye Prej-søgninger pr. tryk** på importknappen (af hensyn til tid og fejlhåndtering).
- Højst **18 kvotereservationer pr. UTC-dag** til produktimport, gennem fase-2 RPC'en
  \`prej_reserve_call\`.
- Kvotegrænsen for hele brugerkontoen er fortsat højst **20 kald/dag**, og de resterende 5 af Prejs
  gratis 25-kalds kvote reserveres som buffer.
- Key-validering, import og prisopdateringer deler den samme **personlige** kvotetæller. Ingen
  samling af brugernøgler eller rotationsskift.
- \`requestsUsed\`, \`fromCache\`, \`pending\`, \`ready\`, \`noMatch\`, \`incomplete\` og
  \`stopReason\` vises som importstatus. Tryk igen for at fortsætte, når kvoten tillader det.
- **Ingen baggrundsfortsættelse** og ingen natlig scheduler i fase 3. Det kommer først i fase 5.

## Arkitektur

- Ny migration \`supabase/migrations/20261010123000_bilka_search_cache.sql\`.
- \`bilka_search_cache\`: global intern backend-cache med product-ID'er, total, truncation og udløb.
  RLS og ingen klientgrants.
- Eksisterende \`bilka_discovery_jobs\`: en række pr. husstands-ingrediens, status og forsøg. Ny
  \`INCOMPLETE\` status.
- Eksisterende \`ingredient_bilka_candidates\`: godkendelseskrævende kandidater; afviste kandidater
  genoprettes ikke automatisk.
- Eksisterende \`bilka_products\`: fælles Bilka-produktdata med mærke, emballage, pris og
  billedreferencer.
- \`supabase/functions/bilka-prej/discovery.ts\`: testbar gruppering og konservativ score.
- \`supabase/functions/bilka-prej/discovery_server.ts\`: API-kvoter, cache, gemning, fallback og
  status.
- Edge actions \`discover\` og \`discovery-status\`; kræver JWT og bekræftet husstandsmedlemskab.
- Android viser status og importknap i \`BilkaApiSettingsScreen\`.

## Inden deployment

1. Se GitHub Actions: Deno typecheck + test, Android unit tests + build, startup smoke.
2. Migrér Supabase \`20261010123000_bilka_search_cache.sql\`, og kør
   \`supabase/tests/bilka_phase3_contract.sql\`. Databaseændringen er additiv og røber ingen
   hemmeligheder.
3. Deploy Edge Function med **JWT-verifikation aktiveret** og med \`index.ts\`, \`prej.ts\`,
   \`discovery.ts\`, \`discovery_server.ts\`.
4. Test med en personlig Prej-nøgle fra appen. Kvoten **bruges kun hvis en bruger bevidst trykker
   importknappen**. CI-testene bruger kun syntetiske data og fake fetch.
5. Verificér, at samme vare kan foreslås til flere ingredienser uden at vælge en standard, og at
   priser fra BilkaToGo udelades.
6. Verificér delvist importerede lister, knappen "tryk igen", kvoteudløb og cachegenbrug mellem de
   to husstandsmedlemmer.

**Ikke omfattet:** Et egentligt billedbaseret produktvalg, manuel præcisering for \`INCOMPLETE\` og
individuel afvisning/valg af SKU. Dette er fase 4.

## Brugerflade og tilgængelighed

Skærmen åbnes fra **Indstillinger → Bilka-produkter og priser**, ikke fra en teknisk "API"-menu. Den
guider brugeren igennem:

1. **Forbind din Prej-konto:** Status er *Ikke tilføjet*, *Gemt, men ikke kontrolleret* eller
   *Forbundet*. En gemt nøgle kan kontrolleres uden genindtastning via `verify-key`, som først
   reserverer og bruger ét kvoteret Prej-kald. Nøglen vises aldrig i klartekst.
2. **Find Bilka-varerne:** Ét stort, tydeligt tryk med antallet af allerede valgte varer, forslag
   klar til gennemgang og ingredienser, der mangler søgning. Backend stopper efter højst 8 søgekald
   eller ca. 25 sekunder, gemmer fundene og lader brugeren fortsætte. UI forklarer forskellen mellem
   *Ingen forslag* og *Kræver præcisering*.
3. **Se dine ingredienser:** Navngivne statusrækker, fx "Mel — 3 forslag klar" og "Kartofler —
   Mangler søgning". Første 12 vises, resten kan foldes ud. Brugeren ser, at forslag **ikke** er
   automatiske produktvalg.

Når ingen API-nøgle er forbundet, er søgeknappen deaktiveret med en forklaring. Forskellen på
brugerens personlige Prej-konto og husstandens fælles produktvalg fremgår tydeligt. Faste
produktvalg og lager ændres ikke i fase 3.

### Manuel test for intuitivitet

- Første login uden Prej-nøgle → brugeren forstår, hvor nøglen indsættes, og hvorfor søgning endnu
  ikke kan startes.
- Forhåndsgemte nøgler (fx indsat gennem Vault) → vis *Nøglen skal kontrolleres* samt knap til
  validering, uden ny indtastning.
- Efter første import → vis konkrete ingrediensnavne og antal forslag, ikke kun tekniske tællere.
- Ved stop pga. kvote/tidsgrænse → vis, at fundene er gemt, og hvordan man senere fortsætter.
- Ved manglende eller mere end 100 søgeresultater → vis præcisering/ingen match tydeligt og lov ikke
  et usynligt automatisk valg.
- Ved skift mellem partnere → begge ser fælles fundne produkter; kun ejeren af nøglen kan
  kontrollere/udskifte sin nøgle.

**Bemærk:** Ingen automatiske Prej-kald sker, bare fordi skærmen vises. Status læses fra Supabase,
og en API-kvote bruges kun ved brugerinitieret validering/import eller prisopdatering.
