# epm-agent-script
Groovy-script for Oracle EPM Integration Agent.

Dette dokumentet gjelder `BefExtract.groovy` og beskriver hvordan innfiler leses, transformeres og klargjøres for innlasting i Oracle EPM Account Reconciliation.

## Oversikt

- Scriptet kjører som preprosessering (`BefExtract`) i agentflyten.
- Kjøring styres av bindvariabelen `Rule`.
- Hver regel leser filer fra `inbox`, skriver resultat til agentens outputfil og arkiverer kildefil i `arkiv`.
- Base path bestemmes automatisk fra `user.dir`:
  - `/data/ar/dev`
  - `/data/ar/prod`

## Lokasjon og drift

### Scriptlokasjon
1. Logg inn på EPM-server.
2. Gå til miljørot (`/data/ar/dev` eller `/data/ar/prod`).
3. Legg scriptet i `scripts`.

### Starte og stoppe EPM Integration Agent
Kjør fra riktig miljømappe.
1. start agenten
2. stoppe agenten
```bash
/data/ar/dev/bin/epmagent.sh /data/ar/dev/config/agentparams.ini
/data/ar/dev/bin/stopagent.sh EPMAGENT
```

```bash
/data/ar/prod/bin/epmagent.sh /data/ar/prod/config/agentparams.ini
/data/ar/prod/bin/stopagent.sh EPMAGENT_PROD
```

### Manuell flytting av filer
1. Flytt filer fra `\\adeo.no\appl\AdraMatch` til `F:` eller `W:`.
2. Bruk for eksempel WinSCP fra VDI ved behov.

## Regelstyring i `BefExtract.groovy`

- Scriptet bruker `ruleHandlers` (map av `Rule` -> closure).
- Hvis `Rule` ikke finnes i `ruleHandlers`, feiler kjøringen med `Unknown rule`.
- Nummereringen under matcher kommentarene i scriptet (1-14).

## Regler: input, matching, outputformat

### 1. `CAMT053_LOAD`
- **Inputmappe:** `inbox/bank/camt053/`
- **Arkivmappe:** `inbox/bank/camt053/arkiv/`
- **Filmatching:** XML-filer der innhold/namespace inneholder `camt.053`
- **Output (CSV-header):**
  - `KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5`
- **Merknad:** Kan håndtere flere filer i samme kjøring og slå dem sammen i output.

### 2. `BANK_LOAD`
- **Inputmappe:** `inbox/bank/`
- **Arkivmappe:** `inbox/bank/arkiv/`
- **Filmatching:** Filnavn som inneholder `tdnb`
- **Output (CSV-header):**
  - `KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5`

### 3. `OEBS_BANK_LOAD`
- **Inputmappe:** `inbox/OracleGL`
- **Arkivmappe:** `inbox/OracleGL/arkiv/`
- **Filmatching:** Filnavn som inneholder `GL2BETTY`
- **Output (semikolon-header):**
  - `Konto-Number;Reference1;Reference2;Debit;Credit;Date;Content1;Content2;Amount`

### 4. `Tiltak_Daglig_Bestilling`
- **Inputmappe:** `inbox/tiltak/daglig/`
- **Arkivmappe:** `inbox/tiltak/daglig/arkiv/`
- **Filmatching:** Filnavn som inneholder `Tiltak_Daglig`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Belob;Valuta;Organisasjon;Bedrift;Lopenummer;Referanse`

### 5. `Tiltak_Daglig_Faktura`
- **Inputmappe:** `inbox/tiltak/daglig/`
- **Arkivmappe:** `inbox/tiltak/daglig/arkiv/`
- **Filmatching:** Filnavn som inneholder `Tiltaksfaktura_Daglig`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Belob;Valuta;Organisasjon;Bedrift;Lopenummer`

### 6. `Tiltak_Tilsagn_Arena_LT`
- **Inputmappe:** `inbox/tiltak/daglig/LT/tilsagn`
- **Arkivmappe:** `inbox/tiltak/daglig/LT/tilsagn/arkiv/`
- **Filmatching:** Filnavn som inneholder `LT_tiltak_avstemming_dag` eller `TILSAGN`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Bedrift;Organisasjon;Lopenummer`

### 7. `Tiltak_Tilsagn_Arena_LT-refusjon`
- **Inputmappe:** `inbox/tiltak/daglig/LT/refusjon`
- **Arkivmappe:** `inbox/tiltak/daglig/LT/refusjon/arkiv/`
- **Filmatching:** Filnavn som inneholder `LT_tiltak_avstemming_dag` eller `REFUSJON`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Bedrift;Organisasjon;Lopennummer`

### 8. `Tiltak_Tilsagn_Arena_Annul`
- **Inputmappe:** `inbox/tiltak/daglig/arena/tilsagn/annullert`
- **Arkivmappe:** `inbox/tiltak/daglig/arena/tilsagn/annullert/arkiv/`
- **Filmatching:** Filnavn som inneholder `tiltak_avstemming_dag`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer`

### 9. `Tiltak_RefKrav_Arena`
- **Inputmappe:** `inbox/tiltak/daglig/arena/refusjon/`
- **Arkivmappe:** `inbox/tiltak/daglig/arena/refusjon/arkiv/`
- **Filmatching:** Filnavn som inneholder `tiltak_avstemming_dag`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer`

### 10. `Tiltak_RefKrav_Arena_Tilsagn_Godkjent`
- **Inputmappe:** `inbox/tiltak/daglig/arena/tilsagn/`
- **Arkivmappe:** `inbox/tiltak/daglig/arena/tilsagn/arkiv/`
- **Filmatching:** Filnavn som inneholder `tiltak_avstemming_dag`
- **Output (semikolon-header):**
  - `Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer`

### 11. `Tiltak_Manedlig_OEBS`
- **Inputmappe:** `inbox/tiltak/maanedlig/`
- **Arkivmappe:** `inbox/tiltak/maanedlig/arkiv/`
- **Filmatching:** Filnavn som inneholder `Tiltak_Maanedlig`
- **Output (semikolon-header):**
  - `Referanse;Dato;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer`

### 12. `Tiltak_Manedlig`
- **Inputmappe:** `inbox/tiltak/maanedlig`
- **Arkivmappe:** `inbox/tiltak/maanedlig/arkiv/`
- **Filmatching:** Filnavn som inneholder `tiltak_avstemming_mnd`
- **Output (semikolon-header):**
  - `Dato;Referanse;Year;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer`

### 13. `Grensesnitt`
- **Inputmappe:** `inbox/grensesnitt`
- **Arkivmappe:** `inbox/grensesnitt/arkiv/`
- **Filmatching:** Filnavn som inneholder `OB04`
- **Output (CSV-header):**
  - `ref1,vedtaksID,belop_file,dato_overfort,person_nr,FOM,TOM,ytelseskonto,ref2,dato,kostnadssted,ansvarssted,ref3,rente1,rente2,ref4,fagsystem_ID`

### 14. `Grensesnitt_fag`
- **Inputmappe:** `inbox/grensesnitt`
- **Arkivmappe:** `inbox/grensesnitt/arkiv/`
- **Filmatching:** Filnavn som inneholder `tilbake-p` eller `navi`
- **Inputencoding:** `UTF-8`
- **Output (semikolon-header):**
  - `avsender;vedtakId;fnr;vedtaksdato;fagsakYtelseType;tilbakekrevesBruttoUtenRenter;skatt;tilbakekrevesNettoUtenRenter;renter;erOmgjøringTilIngenTilbakekreving`

## Arkivering

- For hver behandlet fil kopieres innhold til regelens `arkiv`-mappe.
- Deretter slettes originalfilen fra inputmappe.
- Hvis sletting feiler, logges warning.

## Kjøring i EPM

1. Legg fil i riktig `inbox`-mappe for aktuell `Rule`.
2. Start/bruk integrasjon i Oracle EPM Datautveksling.
3. Kjør jobben som setter riktig `Rule`-verdi.
4. Verifiser output og at originalfil er flyttet til `arkiv`.

## Feilsøking

- **Unknown rule**: `Rule`-verdien finnes ikke i `ruleHandlers`.
- **Directory not found**: feil miljøbase (`/data/ar/dev` eller `/data/ar/prod`) eller manglende mappe.
- **Date parsing-feil**: inputformat matcher ikke forventet format for regelen.
- **Ingen filer plukket opp**: filnavn matcher ikke regelens mønster.
