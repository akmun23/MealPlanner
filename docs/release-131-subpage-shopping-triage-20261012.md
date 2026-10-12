# 1.3.1 (versionCode 9) – undermenu-crash og købt mængde

## Rapporterede symptomer

- **Alle undermenuer crasher**, inklusive Indstillinger, redigering af opskrift og ingrediens, samt oprettelsesflow. Den konkrete AndroidRuntime-stacktrace fra den installerede APK er **ikke tilgængelig**. Koden er ændret som en isoleret kompatibilitetsundersøgelse, **ikke som bekræftet crashfix**.
- Efter at have markeret indkøb som købt, kan brugeren ikke tilføje den faktisk købte mængde på Lager. Avocado lykkedes; flere andre produkter fejler eller har inaktiv knap.

## Bekræftede fejl i indkøbskoden

- `BilkaPurchaseDialog` kunne vælge et tidligere produkt-ID, der **ikke fandtes blandt de kompatible produkter**, så `currentProduct == null` og gem-knappen forblev deaktiveret.
- `BilkaQuantityKind` kendte kun MASS og VOLUME. COUNT og PACKAGE blev afkodet som ukendt `null`, hvilket udviskede en faktisk typekonflikt mellem leverandør og ingrediens.
- Manuelle køb kunne starte med en **tom varetekst**, mens serveren krævede 2–160 tegn. Knappen forblev dermed inaktiv uden en forklarende fejl.
- Købsdialoger kunne foreslå `0`, når lageret dækkede behovet eller indkøbslistens beregnede behov var `0`. En positiv mængde kunne stadig skrives manuelt, men det var uklart.
- `refreshBilkaChoices()` havde ingen `finally`: en uventet fejl, der kastede uden for `Result`, kunne efterlade `bilkaLoading=true` og skjule muligheden for at tilføje flere køb.
- Den cloud-baserede indkøbsdialog blev kun brugt, når varen havde katalogforslag/default. En vare uden katalogforslag gik tidligere direkte til lokal `addPantryAmount`, selv om serverens atomare, dubletbeskyttede `record-purchase` **understøtter manuelt navngivne produkter**.

## Rettelser i denne branch

- Vælg kun et kompatibelt produkt-ID, som faktisk findes i kataloget, ellers manuel registrering, der tydeligt viser navnefeltet.
- Foreslå ingrediensnavnet i manuelt navngivne køb og forklar eksplicit, hvilke felter der blokerer Gem.
- Registrér **alle cloud-køb** via serverens atomare RPC, også ved tomt/fejlet katalog eller shopping-context efter brugerens eksplicitte manuelle bekræftelse. Serveren afviser dobbelte køb for samme ingrediens og uge; manglende netværk må ikke udløse en usikker lokal lagerkreditering. Lokal-only tilstand bevarer direkte lagerregistrering.
- Brug positiv mængde i den valgte enhed og validér mængden **efter** konvertering til grundenhed; forklar ugyldige værdier.
- Sørg for, at indlæsningsindikatoren nulstilles efter forventede såvel som uventede fejl, men lad coroutine-cancellation gå videre.
- Afgrænset UI-workaround: alle undermenuer deler nu en simpel Compose topbjælke med tilgængelig Tilbage-knap; undgår `TopAppBar` samt den genererede AutoMirrored-pil, der kun blev initialiseret på undermenuerne. **Den præcise crashårsag er ikke verificeret.**
- `versionCode=9` og `versionName=1.3.1` identificerer næste testartefakt. Bekræft, at versionskode **9 endnu ikke er brugt i Play Console**; ellers skal den hæves yderligere.

## Verifikation

- JUnit-cases tilgængelige i `PurchaseDialogRulesTest` og `BilkaWireParsingTest`.
- Syv isolerede Kotlin-tjek af de samme rene købsregler blev kørt med lokal `kotlinc` 1.9 og bestod. Dette **kompilerer ikke Android-modulet**, og de officielle JUnit-/instrumenterede tests er endnu **ikke kørt**.
- Der var ingen Android SDK eller fysisk Android-enhed i udviklingsmiljøet; hidtidige GitHub Actions-jobs fejlede, inden buildtrinnene kunne starte. Intet dokumenteret R8-runtime-pass.
- Ingen Supabase-migration eller produktionsdatabaseændring. Serverens eksisterende `bilka_record_purchase` er undersøgt og understøtter manuelle varer med et navn på 2–160 tegn og et positivt basebeløb.
- **Ingen test af en allerede installeret APK**; en merge på GitHub ændrer ikke automatisk installerede versioner.

## Før intern Play-test og bred udgivelse

1. Kontrollér produktionsprojektets `SUPABASE_URL` og `SUPABASE_PUBLISHABLE_KEY`; CI's placeholder credentials er **ikke** produktionsnøgler.
2. Byg `:app:assembleRelease :app:bundleRelease :app:testDebugUnitTest`, og kør UI-smoke-test af `SubpageScaffold` samt release-lignende `subpageNavigationSmoke` på separat enhed.
3. Installer **som opdatering via samme signatur/Play Internal Testing**; fjern ikke den tidligere app eller dens Room/outbox-data. Tjek Androids **Appinfo → Version: 1.3.1** på telefonen, før en rettelse bedømmes.
4. Test Indstillinger, Opskriftsdetalje, Ny/Redigér opskrift, Ny/Redigér ingrediens, Sikkerhedskopi, Synkronisering og Madplanindstillinger. Gå tilbage og test ændringer, scroll og tastatur.
5. Markér én ikke tidligere købt, men relevant vare. Indtast konkret positiv mængde og eventuelt betalt pris. Test både varer med kendt pakkestørrelse, manglende pakkestørrelse og ingen Bilka-katalogmatch. Kontrollér ét nyt lagerkredit og nul dubletter. Gentag **ikke** et tvivlsomt køb bare for at teste.
6. Hvis undermenuen stadig crasher, få `AndroidRuntime`-`FATAL EXCEPTION` fra den installerede 1.3.1 samt den tilhørende `mapping.txt`. Vælg den reelle Play-uploadede versions mapping, ikke en fra et andet build. Fjern mulige personlige oplysninger fra loggen før deling.

**Release-status: IKKE runtime-valideret og derfor ikke godkendt til bred udrulning.**
