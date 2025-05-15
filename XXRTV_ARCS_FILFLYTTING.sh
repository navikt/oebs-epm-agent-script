#!/bin/bash
# Aktiver nullglob slik at ikke-matchende mønstre utvides til en tom liste i stedet for literal string
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s nullglob

ROOT_DIR="/app01/ARCS-Q"
DEST_ROOT_DIR="/data/ar/dev/inbox"
LOGG_FIL="/home/apploebsq1/ARCS/fil_overforing.log"
DEST_USER="epmdrift"
DEST_HOST="155.55.63.41"

# BANK
SRC_BANK="$ROOT_DIR/BANK"
ARKIV_BANK="$ROOT_DIR/ARKIV_BANK"
DEST_BANK="$DEST_ROOT_DIR/bank"
ARKIV_BANK_DEST="$DEST_BANK/arkiv"

# GL
SRC_GL="$ROOT_DIR/OracleGL"
ARKIV_GL="$ROOT_DIR/ARKIV_GL"
DEST_GL="$DEST_ROOT_DIR/OracleGL"
ARKIV_GL_DEST="$DEST_GL/arkiv"


# Grensesnitt
SRC_GRENSESNITT="$ROOT_DIR/Tilbakekreving"
ARKIV_GRENSESNITT="$SRC_GRENSESNITT/Arkiv"
DEST_GRENSESNITT="$DEST_ROOT_DIR/grensesnitt"
ARKIV_GRENSESNITT_DEST="$DEST_GRENSESNITT/arkiv"

#Tiltak
SRC_TILTAK="$ROOT_DIR/Tiltak"
ARKIV_TILTAK="$SRC_TILTAK/Arkiv"
#Tiltak Daglig
DEST_TILTAK_DAGLIG="$DEST_ROOT_DIR/tiltak/daglig"
ARKIV_TILTAK_DAGLIG_DEST="$DEST_TILTAK_DAGLIG/arkiv"
DEST_TILSAGN="$DEST_TILTAK_DAGLIG/arena/tilsagn"
DEST_TILSAGN_ARKIV="$DEST_TILSAGN/arkiv"
DEST_REFUSJON="$DEST_TILTAK_DAGLIG/arena/refusjon"
DEST_REFUSJON_ARKIV="$DEST_REFUSJON"
DEST_ANNULLERT="$DEST_TILTAK_DAGLIG/arena/tilsagn/annullert"
DEST_ANNULLERT_ARKIV="$DEST_ANNULLERT/arkiv"
# Tiltak Maanedlig
DEST_TILTAK_MAANEDLIG="$DEST_ROOT_DIR/tiltak/maanedlig"
ARKIV_TILTAK_MAANEDLIG_DEST="$DEST_TILTAK_MAANEDLIG/arkiv"

log_message() {
    local DATO
    DATO=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$DATO] $1" >> "$LOGG_FIL"
}

check_directory_exists() {
    local DIR=$1
    if [ ! -d "$DIR" ]; then
        log_message "Mappe $DIR eksisterer ikke. Avslutter."
        exit 1
    fi
}


file_exists_in_remote() {
    local FIL_NAVN="$1"
    local DIR1="$2"
    local DIR2="$3"
    ssh "$DEST_USER@$DEST_HOST" "if [ -f '$DIR1/$FIL_NAVN' ] || [ -f '$DIR2/$FIL_NAVN' ]; then echo 'Eksisterer'; fi"
}

prosesser_0kb_fil() {
    local FIL=$1
    local ARKIV_DIR=$2
    local REMOTE_ARKIV_DIR=$3
    local FILNAVN

    FILNAVN=$(basename "$FIL")
    log_message "Fil $FILNAVN er på 0KB. Flyttes direkte til arkivet."
    mv "$FIL" "$ARKIV_DIR/"
    if [ $? -eq 0 ]; then
        log_message "Arkivert $FILNAVN i $ARKIV_DIR."
    else
        log_message "Arkivering feilet for $FILNAVN i $ARKIV_DIR."
        return 1
    fi
    ssh "$DEST_USER@$DEST_HOST" "mkdir -p $REMOTE_ARKIV_DIR && touch $REMOTE_ARKIV_DIR/$FILNAVN"
    if [ $? -eq 0 ]; then
        log_message "Opprettet $FILNAVN i $REMOTE_ARKIV_DIR på remote."
    else
        log_message "Oppretting feilet for $FILNAVN i $REMOTE_ARKIV_DIR på remote."
    fi
}

process_tdnb_bank_files() {
    if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$DEST_BANK' ]"; then
        log_message "Mappen $DEST_BANK eksisterer ikke på $DEST_HOST."
        return
    fi

    for FIL in "$SRC_BANK"/*tdnb*.txt_D*; do
        [[ -e "$FIL" ]] || continue
        local FILNAVN NYTT_NAVN TIMESTAMP
        FILNAVN=$(basename "$FIL")
        sleep 1
        TIMESTAMP=$(date '+%Y%m%d%H%M%S')
        if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
        else
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
        fi

        if [[ "$ARKIV_BANK/$FILNAVN" =~ ^[0-9]+_ ]] || [ -f "$ARKIV_BANK/$FILNAVN" ]; then
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv."
            continue
        fi

        if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_BANK" "$ARKIV_BANK_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_BANK eller $ARKIV_BANK_DEST på remote. Flytter til lokalt arkiv $ARKIV_BANK/$NYTT_NAVN. cp output: $output."
            output=$(cp -v "$FIL" "$ARKIV_BANK/$NYTT_NAVN" 2>&1)
            if [ $? -eq 0 ]; then
                log_message "Arkiverte $FILNAVN til "$ARKIV_BANK/$NYTT_NAVN". cp output: $output."
                rm -v "$FIL"
            else
                stat -c '%i %n' "$FIL" "$ARKIV_BANK/$(basename "$FIL")"
                log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $status. Feilmelding: $output."
            fi
            continue
        fi

        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_BANK" "$ARKIV_BANK_DEST"
            continue
        fi

        scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_BANK/"
        if [ $? -eq 0 ]; then
            log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_BANK."
        else
            log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_BANK."
            continue
        fi

        output=$(cp -v "$FIL" "$ARKIV_BANK/$NYTT_NAVN" 2>&1)
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til "$ARKIV_BANK/$NYTT_NAVN". cp output: $output."
            rm -v "$FIL"
        else
            stat -c '%i %n' "$FIL" "$ARKIV_BANK/$(basename "$FIL")"
            log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $status. Feilmelding: $output."
        fi

    done
}

process_tsp_files() {
    for FIL in "$SRC_BANK"/*tsp*.txt_D*; do
        [[ -e "$FIL" ]] || continue
        local FILNAVN NYTT_NAVN TIMESTAMP
        FILNAVN=$(basename "$FIL")
        sleep 1
        TIMESTAMP=$(date '+%Y%m%d%H%M%S')
        if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
        else
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
        fi
        log_message "Prosesserer tsp-fil $NYTT_NAVN. Flyttes direkte til arkivet."
        output=$(cp -v "$FIL" "$ARKIV_BANK/$NYTT_NAVN" 2>&1)
        status=$?
        if [ $status -eq 0 ]; then
            rm -v "$FIL"
            log_message "Arkiverte $FILNAVN til "$ARKIV_BANK/$NYTT_NAVN". cp output: $output."
        else
            log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $status. Feilmelding: $output."
        fi
    done
}


process_gl_files() {
    if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$DEST_GL' ]"; then
        log_message "Mappen $DEST_GL eksisterer ikke på $DEST_HOST."
        return
    fi

    for FIL in "$SRC_GL"/*GL2BETTY-*--1.lst; do
        [[ -e "$FIL" ]] || continue 
        local FILNAVN NYTT_NAVN TIMESTAMP
        FILNAVN=$(basename "$FIL")
        sleep 1
        TIMESTAMP=$(date '+%Y%m%d%H%M%S')
        if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
        else
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
        fi

        if [[ "$ARKIV_GL/$FILNAVN" =~ ^[0-9]+_ ]] || [ -f "$ARKIV_GL/$FILNAVN" ]; then
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Flyttes til arkivet."
            continue
        fi

        if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_GL" "$ARKIV_GL_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_GL eller $ARKIV_GL_DEST på remote. Flytter til lokalt arkiv."
            output=$(cp -v "$FIL" "$ARKIV_GL/$NYTT_NAVN" 2>&1)
            if [ $? -eq 0 ]; then
                log_message "Arkiverte $FILNAVN til $ARKIV_GL."
                rm -v "$FIL"
            else
                log_message "Feilet arkivering av $FILNAVN."
            fi
            continue
        fi

        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_GL" "$ARKIV_GL_DEST"
            continue
        fi

        scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_GL/"
        if [ $? -eq 0 ]; then
            log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GL."
        else
            log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GL."
            continue
        fi

        output=$(cp -v "$FIL" "$ARKIV_GL/$NYTT_NAVN" 2>&1)
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til $ARKIV_GL."
            rm -v "$FIL"
        else
            log_message "Feilet arkivering av $FILNAVN."
        fi
    done
}

process_grensesnitt_files() {
    if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$DEST_GRENSESNITT' ]"; then
         log_message "Mappen $DEST_GRENSESNITT eksisterer ikke på $DEST_HOST."
         return
    fi

    for FIL in "$SRC_GRENSESNITT"/{*tilbake-p*.csv,OB04*.DAT}; do
        [[ -e "$FIL" ]] || continue
        local FILNAVN
        FILNAVN=$(basename "$FIL")

        if [ -f "$ARKIV_GRENSESNITT/$FILNAVN" ]; then
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Hopper over filen."
            continue
        fi

        if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_GRENSESNITT" "$ARKIV_GRENSESNITT_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede på remote i $DEST_GRENSESNITT eller $ARKIV_GRENSESNITT_DEST. Flytter til lokalt arkiv."
            output=$(cp -v "$FIL" "$ARKIV_GRENSESNITT/$NYTT_NAVN" 2>&1)
            if [ $? -eq 0 ]; then
                log_message "Arkiverte $FILNAVN til $ARKIV_GRENSESNITT."
                rm -v "$FIL"
            else
                log_message "Feilet arkivering av $FILNAVN."
            fi
            continue
        fi

        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_GRENSESNITT" "$ARKIV_GRENSESNITT_DEST"
            continue
        fi
        
        scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_GRENSESNITT/"
        if [ $? -eq 0 ]; then
            log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GRENSESNITT."
        else
            log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GRENSESNITT."
            continue
        fi

        output=$(cp -v "$FIL" "$ARKIV_GRENSESNITT/$NYTT_NAVN" 2>&1)
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til $ARKIV_GRENSESNITT."
            rm -v "$FIL"
        else
            log_message "Feilet arkivering av $FILNAVN."
        fi

    done
}
process_tiltak_files() {
    log_message "Daglig tiltak"
    process_daglig_tiltak_files
    log_message "Daglig Bestilling"
    process_daglig_bestilling_files
    log_message "Daglig Faktura"
    process_daglig_faktura_files
    log_message "Månedlig tiltak"
    process_manedlig_tiltak_files
}   

process_daglig_tiltak_files() {
    for REMOTE_DIR in "$DEST_TILSAGN" "$DEST_REFUSJON" "$DEST_ANNULLERT"; do
        if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$REMOTE_DIR' ]"; then
            log_message "log_message Mappen $REMOTE_DIR eksisterer ikke på $DEST_HOST. Hopper over daglig tiltak filer."
            return
        fi
    done

    for FIL in "$SRC_TILTAK"/*_dag.csv; do
        [[ -e "$FIL" ]] || continue
        local FILNAVN NYTT_NAVN TIMESTAMP
        FILNAVN=$(basename "$FIL")

        if [[ "$FILNAVN" == *_LT_* ]]; then
            log_message "Hopper over fil $FILNAVN fordi den ikke skal prosesseres da det er en LT-fil."
            continue
        fi
        sleep 1
        TIMESTAMP=$(date '+%Y%m%d%H%M%S')
        if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
        else
            NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
        fi

        if [ -f "$ARKIV_TILTAK/$FILNAVN" ]; then
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Hopper over filen."
            continue
        fi

        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_TILTAK" "$DEST_TILSAGN_ARKIV"
            prosesser_0kb_fil "$FIL" "$ARKIV_TILTAK" "$DEST_REFUSJON_ARKIV"
            prosesser_0kb_fil "$FIL" "$ARKIV_TILTAK" "$DEST_ANNULLERT_ARKIV"
            continue
        fi

        local all_transfers_successful=1

        # Definer arrays for remote-destinasjoner og tilhørende arkiv
        remote_dirs=( "$DEST_TILSAGN" "$DEST_REFUSJON" "$DEST_ANNULLERT" )
        remote_archives=( "$DEST_TILSAGN_ARKIV" "$DEST_REFUSJON_ARKIV" "$DEST_ANNULLERT_ARKIV" )

        # Loop gjennom indeksene i arrayene
        for i in "${!remote_dirs[@]}"; do
            REMOTE_DIR="${remote_dirs[$i]}"
            REMOTE_ARKIV="${remote_archives[$i]}"
            
            if [[ $(file_exists_in_remote "$FILNAVN" "$REMOTE_DIR" "$REMOTE_ARKIV") == "Eksisterer" ]]; then
                log_message "Fil $FILNAVN eksisterer allerede på remote i $REMOTE_DIR eller $REMOTE_ARKIV."
            else
                scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$REMOTE_DIR/"
                if [ $? -eq 0 ]; then
                    log_message "Overførte $FILNAVN til $DEST_USER@$DEST_HOST:$REMOTE_DIR."
                else
                    log_message "Kunne ikke overføre $FILNAVN til $DEST_USER@$DEST_HOST:$REMOTE_DIR."
                    all_transfers_successful=0
                fi
            fi
        done


        if [ $all_transfers_successful -eq 1 ]; then
            output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
            if [ $? -eq 0 ]; then
                log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK. cp output: $output."
                rm -v "$FIL"
            else
                log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $all_transfers_successful. Feilmelding: $output."
            fi
        else
            log_message "Feilet overføring av fil $FILNAVN. cp feilet med status $all_transfers_successful. Feilmelding: $output."
        fi

    done
}

process_daglig_bestilling_files() { 
    if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$DEST_TILTAK_DAGLIG' ]"; then
         log_message "Mappen $DEST_TILTAK_DAGLIG eksisterer ikke på $DEST_HOST."
         return
    fi

    for FIL in "$SRC_TILTAK"/Tiltak_Daglig*; do
         [[ -e "$FIL" ]] || continue
         local FILNAVN NYTT_NAVN TIMESTAMP
         FILNAVN=$(basename "$FIL")
         sleep 1
         TIMESTAMP=$(date '+%Y%m%d%H%M%S')
         if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
              NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
         else
              NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
         fi

         if [ -f "$ARKIV_TILTAK/$FILNAVN" ]; then
              log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Hopper over filen."
              continue
         fi

         if [ ! -s "$FIL" ]; then
              prosesser_0kb_fil "$FIL" "$ARKIV_TILTAK" "$ARKIV_TILTAK_DAGLIG_DEST"
              continue
         fi

         if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_TILTAK_DAGLIG" "$ARKIV_TILTAK_DAGLIG_DEST") == "Eksisterer" ]]; then
              log_message "Fil $FILNAVN eksisterer allerede på remote i $DEST_TILTAK_DAGLIG eller $ARKIV_TILTAK_DAGLIG_DEST. Flytter til lokalt arkiv."
              output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
              if [ $? -eq 0 ]; then
                   log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK med nytt navn $NYTT_NAVN. cp output: $output."
                   rm -v "$FIL"
              else
                   log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $? Feilmelding: $output."
              fi
              continue
         fi

         scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_TILTAK_DAGLIG/"
         if [ $? -eq 0 ]; then
              log_message "Overførte $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_TILTAK_DAGLIG."
         else
              log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_TILTAK_DAGLIG."
              continue
         fi

         output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
         if [ $? -eq 0 ]; then
              log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK med nytt navn $NYTT_NAVN. cp output: $output."
              rm -v "$FIL"
         else
              log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $? Feilmelding: $output."
         fi
    done
}

process_daglig_faktura_files() {
    if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$DEST_TILTAK_DAGLIG' ]"; then
         log_message "Mappen $DEST_TILTAK_DAGLIG eksisterer ikke på $DEST_HOST."
         return
    fi

    for FIL in "$SRC_TILTAK"/Tiltaksfaktura_Daglig*; do
         [[ -e "$FIL" ]] || continue
         local FILNAVN NYTT_NAVN TIMESTAMP
         FILNAVN=$(basename "$FIL")
         sleep 1
         TIMESTAMP=$(date '+%Y%m%d%H%M%S')
         if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
              NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
         else
              NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
         fi

         if [ -f "$ARKIV_TILTAK/$FILNAVN" ]; then
              log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Hopper over filen."
              continue
         fi

         if [ ! -s "$FIL" ]; then
              prosesser_0kb_fil "$FIL" "$ARKIV_TILTAK" "$ARKIV_TILTAK_DAGLIG_DEST"
              continue
         fi

         if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_TILTAK_DAGLIG" "$ARKIV_TILTAK_DAGLIG_DEST") == "Eksisterer" ]]; then
              log_message "Fil $FILNAVN eksisterer allerede på remote i $DEST_TILTAK_DAGLIG eller $ARKIV_TILTAK_DAGLIG_DEST. Flytter til lokalt arkiv."
              output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
              if [ $? -eq 0 ]; then
                   log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK med nytt navn $NYTT_NAVN. cp output: $output."
                   rm -v "$FIL"
              else
                   log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $? Feilmelding: $output."
              fi
              continue
         fi

         scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_TILTAK_DAGLIG/"
         if [ $? -eq 0 ]; then
              log_message "Overførte $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_TILTAK_DAGLIG."
         else
              log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_TILTAK_DAGLIG."
              continue
         fi

         output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
         if [ $? -eq 0 ]; then
              log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK med nytt navn $NYTT_NAVN. cp output: $output."
              rm -v "$FIL"
         else
              log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $? Feilmelding: $output."
         fi
    done
}


process_manedlig_tiltak_files() {
    if ! ssh "$DEST_USER@$DEST_HOST" "[ -d '$DEST_TILTAK_MAANEDLIG' ]"; then
         log_message "Mappen $DEST_TILTAK_MAANEDLIG eksisterer ikke på $DEST_HOST."
         return
    fi

    for FIL in "$SRC_TILTAK"/{*_mnd.csv,Tiltak_Maanedlig*}; do
         [[ -e "$FIL" ]] || continue
         local FILNAVN NYTT_NAVN TIMESTAMP
         FILNAVN=$(basename "$FIL")

        if [[ "$FILNAVN" == *_LT_* ]]; then
            log_message "Hopper over fil $FILNAVN fordi den ikke skal prosesseres da det er en LT-fil."
            continue
        fi

         sleep 1
         TIMESTAMP=$(date '+%Y%m%d%H%M%S')
         if [[ "$FILNAVN" =~ ^[0-9]+_ ]]; then
              NYTT_NAVN="${TIMESTAMP}_${FILNAVN#*_}"
         else
              NYTT_NAVN="${TIMESTAMP}_${FILNAVN}"
         fi

         if [ -f "$ARKIV_TILTAK/$FILNAVN" ]; then
              log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Hopper over filen."
              continue
         fi

         if [ ! -s "$FIL" ]; then
              prosesser_0kb_fil "$FIL" "$ARKIV_TILTAK" "$ARKIV_TILTAK_MAANEDLIG_DEST"
              continue
         fi

         if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_TILTAK_MAANEDLIG" "ARKIV_TILTAK_MAANEDLIG_DEST") == "Eksisterer" ]]; then
              log_message "Fil $FILNAVN eksisterer allerede på remote i $DEST_TILTAK_MAANEDLIG eller $ARKIV_TILTAK_MAANEDLIG_DEST. Flytter til lokalt arkiv."
              output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
              if [ $? -eq 0 ]; then
                   log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK med nytt navn $NYTT_NAVN. cp output: $output."
                   rm -v "$FIL"
              else
                   log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $? Feilmelding: $output."
              fi
              continue
         fi

         scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_TILTAK_MAANEDLIG/"
         if [ $? -eq 0 ]; then
              log_message "Overførte $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_TILTAK_MAANEDLIG."
         else
              log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_TILTAK_MAANEDLIG."
              continue
         fi

         output=$(cp -v "$FIL" "$ARKIV_TILTAK/$NYTT_NAVN" 2>&1)
         if [ $? -eq 0 ]; then
              log_message "Arkiverte $FILNAVN til $ARKIV_TILTAK med nytt navn $NYTT_NAVN. cp output: $output."
              rm -v "$FIL"
         else
              log_message "Feilet arkivering av fil $FILNAVN. cp feilet med status $? Feilmelding: $output."
         fi
    done
}

log_message "Starter overføring av Bank-fil."
check_directory_exists "$ARKIV_BANK"

process_tsp_files
process_tdnb_bank_files

log_message "Bankfil overføring og arkivering gjennomført."
log_message "**********************************************"
log_message "Starter overføring av GL-fil."
check_directory_exists "$ARKIV_GL"

process_gl_files

log_message "GL-fil overføring og arkivering gjennomført."
log_message "**********************************************"
log_message "Starter overføring av Grensesnitt-fil."
check_directory_exists "$ARKIV_GRENSESNITT"

process_grensesnitt_files

log_message "Grensesnitt-fil overføring og arkivering gjennomført."
log_message "**********************************************"
log_message "Starter overføring av Tiltaksfiler."
check_directory_exists "$ARKIV_TILTAK"

process_tiltak_files

log_message "Tiltak-fil overføring og arkivering gjennomført."
log_message "**********************************************"