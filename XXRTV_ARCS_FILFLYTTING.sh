#!/bin/bash
# Aktiver nullglob slik at ikke-matchende mønstre utvides til en tom liste i stedet for literal string
shopt -s nullglob

ROOT_DIR="/app01/ARCS-Q"
DEST_ROOT_DIR="/data/ar/dev/inbox"
LOGG_FIL="bank_overforing.log"
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
SRC_TILTAK_ARKIV="$SRC_TILTAK/Arkiv"
#Tiltak Daglig
DEST_TILTAK_DAGLIG="$DEST_ROOT_DIR/tiltak/daglig"
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

    for FIL in "$SRC_BANK"/tdnb*.txt_D*; do
        [[ -e "$FIL" ]] || continue
        local FILNAVN
        FILNAVN=$(basename "$FIL")

        if [ -f "$ARKIV_BANK/$FILNAVN" ]; then
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv."
            stat -c '%i %n' "$FIL" "$ARKIV_BANK/$(basename "$FIL")"
            mv -v "$FIL" "$ARKIV_BANK"
            continue
        fi

        if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_BANK" "$ARKIV_BANK_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_BANK eller $ARKIV_BANK_DEST på remote. Flytter til lokalt arkiv."
            stat -c '%i %n' "$FIL" "$ARKIV_BANK/$(basename "$FIL")"
            mv "$FIL" "$ARKIV_BANK/"
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

        mv -v "$FIL" "$ARKIV_BANK"
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til $ARKIV_BANK."
        else
            stat -c '%i %n' "$FIL" "$ARKIV_BANK/$(basename "$FIL")"
            log_message "Feilet arkivering av $FILNAVN."
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

    for FIL in "$SRC_GL"/GL2BETTY-*--1.lst; do
        [[ -e "$FIL" ]] || continue 
        local FILNAVN
        FILNAVN=$(basename "$FIL")

        if [ -f "$ARKIV_GL/$FILNAVN" ]; then
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Flyttes til arkivet."
            continue
        fi

        if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_GL" "$ARKIV_GL_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_GL eller $ARKIV_GL_DEST på remote. Flytter til lokalt arkiv."
            mv "$FIL" "$ARKIV_GL/"
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

        mv "$FIL" "$ARKIV_GL/"
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til $ARKIV_GL."
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
              log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Flytter til arkivet."
              continue
        fi

        if [[ $(file_exists_in_remote "$FILNAVN" "$DEST_GRENSESNITT" "$ARKIV_GRENSESNITT_DEST") == "Eksisterer" ]]; then
              log_message "Fil $FILNAVN eksisterer allerede på remote i $DEST_GRENSESNITT eller $ARKIV_GRENSESNITT_DEST. Flytter til lokalt arkiv."
              mv "$FIL" "$ARKIV_GRENSESNITT/"
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

        mv "$FIL" "$ARKIV_GRENSESNITT/"
        if [ $? -eq 0 ]; then
              log_message "Arkiverte $FILNAVN til $ARKIV_GRENSESNITT."
         else
              log_message "Feilet arkivering av $FILNAVN."
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
