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
* **Tiltak daglig bestilling filer** -> `Tiltak_Daglig`
* **Tiltak daglig faktura filer** -> `Tiltaksfaktura_Daglig`
* fyll ut resten her

Når filen ligger i riktig mappe kan du gå inn i avstemmingsløsningen og trigge jobben for å laste data inn i **_Oracle EPM Account Reconciliation_** under **Datautveksling** og kjøre riktig jobb.