#!/bin/bash

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
DEST_GRENSESNITT="$DEST_ROOT_DIR/Grensesnitt"
ARKIV_GRENSESNITT_DEST="$DEST_GRENSESNITT/arkiv"

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

    if mv "$FIL" "$ARKIV_DIR/"; then
        log_message "Arkivert $FILNAVN i $ARKIV_DIR."
    else
        log_message "Arkivering feilet for $FILNAVN i $ARKIV_DIR."
        return 1
    fi

    if ssh "$DEST_USER@$DEST_HOST" "mkdir -p $REMOTE_ARKIV_DIR && touch $REMOTE_ARKIV_DIR/$FILNAVN"; then
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
            log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Flyttes til arkivet."
            mv "$FIL" "$ARKIV_BANK/"
            continue
        fi

        if [[ $(file_exists_in_remote_multiple "$FILNAVN" "$DEST_BANK" "$ARKIV_BANK_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_BANK eller $ARKIV_BANK_DEST på remote. Flytter til lokalt arkiv."
            mv "$FIL" "$ARKIV_BANK/"
            continue
        fi

        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_BANK" "$ARKIV_BANK_DEST"
            continue
        fi

        if scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_BANK/"; then
            log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_BANK."
        else
            log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_BANK."
            continue
        fi

        if mv "$FIL" "$ARKIV_BANK/"; then
            log_message "Arkiverte $FILNAVN til $ARKIV_BANK."
        else
            log_message "Feilet arkivering av $FILNAVN."
        fi

    done
}

process_tsp_files() {
    for FIL in "$SRC_BANK"/tsp*.txt_D*; do
        [[ -e "$FIL" ]] || continue 
        local FILNAVN
        FILNAVN=$(basename "$FIL")
        log_message "Prosesserer tsp-fil $FILNAVN. Flyttes direkte til arkivet."

        if mv "$FIL" "$ARKIV_BANK/"; then
            log_message "Arkiverte $FILNAVN til $ARKIV_BANK."
        else
            log_message "Feilet arkivering av fil $FILNAVN."
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
            mv "$FIL" "$ARKIV_GL/"
            continue
        fi

        if [[ $(file_exists_in_remote_multiple "$FILNAVN" "$DEST_GL" "$ARKIV_GL_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_GL eller $ARKIV_GL_DEST på remote. Flytter til lokalt arkiv."
            mv "$FIL" "$ARKIV_GL/"
            continue
        fi

        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_GL" "$ARKIV_GL_DEST"
            continue
        fi

        if scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_GL/"; then
            log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GL."
        else
            log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GL."
            continue
        fi

        if mv "$FIL" "$ARKIV_GL/"; then
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

    for FIL in "$SRC_GRENSESNITT"/*; do
         [[ -e "$FIL" ]] || continue
         local FILNAVN
         FILNAVN=$(basename "$FIL")

         if [ -f "$ARKIV_GRENSESNITT/$FILNAVN" ]; then
              log_message "Fil $FILNAVN eksisterer allerede i lokalt arkiv. Flytter til arkivet."
              mv "$FIL" "$ARKIV_GRENSESNITT/"
              continue
         fi

         if [[ $(file_exists_in_remote_multiple "$FILNAVN" "$DEST_GRENSESNITT" "$ARKIV_GRENSESNITT_DEST") == "Eksisterer" ]]; then
              log_message "Fil $FILNAVN eksisterer allerede på remote i $DEST_GRENSESNITT eller $ARKIV_GRENSESNITT_DEST. Flytter til lokalt arkiv."
              mv "$FIL" "$ARKIV_GRENSESNITT/"
              continue
         fi

         if [ ! -s "$FIL" ]; then
              prosesser_0kb_fil "$FIL" "$ARKIV_GRENSESNITT" "$ARKIV_GRENSESNITT_DEST"
              continue
         fi

         if scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_GRENSESNITT/"; then
              log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GRENSESNITT."
         else
              log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_GRENSESNITT."
              continue
         fi
         
         if mv "$FIL" "$ARKIV_GRENSESNITT/"; then
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
