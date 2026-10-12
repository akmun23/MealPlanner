# Play Console – manglende R8-mapping og native fejlretningssymboler

Play Console kan vise **to ikke-blokerende advarsler** for en App Bundle:

1. **"Der er ikke knyttet nogen fil til fjernelse af sløring"**: R8's `mapping.txt` mangler for den konkrete upload.
2. **"Denne App Bundle indeholder indlejret kode ... ikke uploadet fejlretningssymboler"**: native `.so`-biblioteker er med, men native debug-symboler er ikke tilgængelige i den uploadede AAB.

De to filer er forskellige; en R8-mapping er ikke et alternativ til native symboler.

## Hvad projektet gør fremover

`app/build.gradle.kts` bruger **R8 i `release`** (`optimization.enable = true`) og sætter **`release.ndk.debugSymbolLevel = "FULL"`**. Android Gradle Plugin skal derfor indlejre R8-mapping og *tilgængelige* native symboler automatisk i den nye `release` App Bundle.

R8-mapping findes lokalt her:

- `app/build/outputs/mapping/release/mapping.txt`

Native symbol-arkivet findes **hvis AGP kan udtrække symbolerne** her:

- `app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip`

Hvis et tredjepartsbibliotek allerede er blevet strippet, kan Gradle **ikke genskabe symboler**, selv med `FULL`. Tjek `--info`-log for `Unable to extract native debug metadata`. I de tilfælde kan Play-advarslen fortsat stå, og man må skaffe symboler fra leverandøren eller acceptere begrænset symbolikering. Pak **aldrig** blot strippede `.so`-filer i en ZIP og kald dem komplette debug-symboler.

## Kontrollér den præcise App Bundle før upload

```bash
./gradlew :app:bundleRelease --info
python3 scripts/check_release_bundle_metadata.py \
  app/build/outputs/bundle/release/app-release.aab
```

Tilpas AAB-filnavnet efter dit faktiske build-output. Python-kontrollen **fejler** ved manglende R8-mapping i den *færdige AAB*, og **advarer** hvis den indeholder native biblioteker uden indlejrede `.dbg`-filer. Den kan ikke certificere, at alle tredjepartsbibliotekers symboler er tilgængelige.

For manuelt at kontrollere indholdet:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep BUNDLE-METADATA
```

Forvent især `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`. Native metadata ligger under `BUNDLE-METADATA/com.android.tools.build.debugsymbols/` **når symboler kan udtrækkes**.

Gem `mapping.txt`, eventuel `native-debug-symbols.zip` og AAB'en til **den samme versionskode**. Mapping overskrives ved fremtidige builds; en mapping fra en *anden* release må aldrig uploades.

## Ret advarsler for et allerede uploadet artefakt

1. Åbn **Play Console → Test og udgivelse → App bundle explorer** (menunavne kan variere).
2. Vælg **præcis den App Bundle/versionCode**, der viser advarslen.
3. Under **Downloads → Assets** kan du uploade den originale `mapping.txt` samt eventuel `native-debug-symbols.zip` fra **den samme build**.
4. Hvis den uploadede AAB blev bygget *før R8 blev aktiveret*, er der typisk ingen meningsfuld R8-mapping at uploade. Du skal bygge en **ny release** med R8 slået til og en ny, endnu ikke anvendt `versionCode`.
5. Hvis du genererer en ny AAB, **kontrollér den først** med scriptet ovenfor og test appen på Play Consoles interne testspor. Dette projekt har tidligere oplevet opstartsnedbrud med en R8-optimeret release.

At skifte build-indstillinger på GitHub ændrer **ikke** en gammel App Bundle i Play Console.

Kilder:
- Google Play Help: https://support.google.com/googleplay/android-developer/answer/9848633
- Android native symbols: https://developer.android.com/build/include-native-symbols
- Android R8 mapping: https://developer.android.com/topic/performance/app-optimization/troubleshoot-the-optimization
