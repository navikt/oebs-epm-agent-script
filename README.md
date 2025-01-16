# oebs-epm-agent-script
Groovy script for bruk i EPM Integration Agent for konvertering av filer til lesbart format av Oracle EPM cloud Account Reconciliation

## Brukerveiledning

### Script lokasjon
1. Logg inn på applikasjonsserver
2. Logg inn på **EPM-server**
3. naviger til `/data/ar/dev` eller `/data/ar/prod`
4. Scriptet skal ligge i mappen `scripts`

### Starte og stoppe EPM Integration Agent
1. Starte agenten fra **EPM-server** `/data/ar/dev/bin` kjør kommando: `./epmagent.sh /data/ar/dev/config/agentparams.ini`
2. Stoppe agenten fra **EPM-server** `/data/ar/dev/bin` kjør kommando: `./stopagent.sh <navn på agent>` for dev er navnet på agenten `EPMAGENT` for prod er navnet `EPMAGENT_PROD`

### Manuell flytting av filer
1. Må flytte filer fra `\\adeo.no\appl\AdraMatch` til `F:`eller `W:`
2. Kan bruke **_WinSCP_** fra **_Oracle Management Desktop_** og flytte filer med drag and drop

### Innlasting av filer
Filer skal legges i mappen `inbox` denne mappen har forskjellige undermapper basert på hvilken type avstemming og filtype det gjelder.

* **Bankfiler** -> `tdnb#.txt`
* **OracleGL filer** -> `OracleGL`
* **Grensesnitt filer** -> `OB04_AR.dat`
* **Grensesnitt fag filer** -> `familie-tilbake-p.csv`, `fptilbake-p.csv`,`k9-tilbake-p.csv`
* **Tiltak daglig og LT-daglig bestilling filer** -> `Tiltak_Daglig`
* **Tiltak daglig faktura og LT-daglig filer** -> `Tiltaksfaktura_Daglig`
* **Tiltak avstemming daglig filer og LT-daglig** -> `tiltak_avstemming_dag.csv`, `LT_tiltak_avstemming_dag.csv`
* **Tiltak månedlig filer** -> `Tiltak_Maanedlig`, `tiltak_avstemming_mnd`
* **LT-Tiltak mnd** -> Ikke implementert

Når filen ligger i riktig mappe kan du gå inn i avstemmingsløsningen og trigge jobben for å laste data inn i **_Oracle EPM Account Reconciliation_** under **Datautveksling** og kjøre riktig jobb.

### Jobber som skal kjøres for avstemmingstyper
Disse navnene er hentet fra produksjon og kan avvike noe fra test, hvis du er usikker kan du sjekke regelnavnene for integrasjonene i `ACCREC` og sammenligne med funksjonene i `groovy scriptet`.

Jobbene finner du ved å gå inn i applikasjonen, trykke på `dataintegrasjon -> datautveksling`

* **Bank** -> **BANK_LOAD** og **OEBS_BANK_LOAD**
* **Grensesnitt** -> **Grensesnitt_Fagsystem_Load** og **Grensesnitt_Sendt_Load**
* **Tiltak daglig** -> **Tiltak_Tilsagn_ARENA1**, **Tiltak_RefKrav_Arena**, **Tiltak_Daglig_Bestilling1** og **Tiltak_Daglig_Faktura**
* **Tiltak daglig annulertlinjer** -> **Daily Annullert - Tiltak_Tilsagn_Arena** denne er litt spesiell og er ikke 100% i bruk
* **Tiltak månedlig** -> **Tiltak_Mnd_Arena1** og **Tiltak_Mnd_OeBS**
* **LT-Tiltak** -> Ikke implementert