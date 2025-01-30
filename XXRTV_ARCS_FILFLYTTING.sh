#!/bin/bash

ROOT_DIR="/app01/ARCS-Q/"
DEST_ROOT_DIR="/data/ar/dev/inbox"
LOGG_FIL="bank_overforing.log"  # Define log file path
DEST_USER="epmdrift"
DEST_HOST="155.55.63.41"

# BANK-mapper
SRC_BANK="$ROOT_DIR/BANK"
ARKIV_BANK="$ROOT_DIR/ARKIV_BANK"
DEST_BANK="$DEST_ROOT_DIR/bank"
ARKIV_BANK_DEST="$DEST_ROOT_DIR/bank/arkiv"

DATO=$(date '+%Y-%m-%d %H:%M:%S')

log_message() {
    echo "[$DATO] $1" >> "$LOGG_FIL"
}

check_directory_exists() {
    local DIR=$1
    if [ ! -d "$DIR" ]; then
        log_message "Mappe $DIR eksisterer ikke. Avslutter"
        exit 1
    fi
}

FIL_exists_in_remote() {
    local FIL_NAVN=$1
    local DEST_DIR=$2
    ssh "$DEST_USER@$DEST_HOST" "test -f $DEST_DIR/$FIL_NAVN && echo 'Eksisterer'"
}

prosesser_0kb_fil() {
    local FIL=$1
    local ARKIV_DIR=$2
    local DEST_DIR=$3

    FILNAVN=$(basename "$FIL")
    log_message "Fil $FILNAVN er på 0KB. Flyttes direkte til arkivet."

    mv "$FIL" "$ARKIV_DIR/"
    if [ $? -eq 0 ]; then
        log_message "Arkivert $FILNAVN i $ARKIV_DIR."
    else
        log_message "Arkivering feilet for $FILNAVN i $ARKIV_DIR."
        return
    fi

    ssh "$DEST_USER@$DEST_HOST" "mkdir -p $ARKIV_BANK_DEST && touch $ARKIV_BANK_DEST/$FILNAVN"
    if [ $? -eq 0 ]; then
        log_message "Opprettet $FILNAVN i $ARKIV_BANK_DEST på remote."
    else
        log_message "Oppretting feilet av $FILNAVN i $ARKIV_BANK_DEST på remote."
    fi
}

# Overfører og arkiverer tdnb-filer
process_tdnb_bank_files() {
    ssh "$DEST_USER@$DEST_HOST" "mkdir -p $DEST_BANK"
    if [ $? -ne 0 ]; then
        log_message "Oppretting av mappe: $DEST_BANK feilet på $DEST_HOST."
        return
    fi

    # Prossesser alle tdnb-filer i BANK-folder
    find "$SRC_BANK" -type f -name "tdnb*.txt_D*" | while read FIL; do
        FILNAVN=$(basename "$FIL")

        # Sjekk om fil finnes i destinasjonsmappen eller arkivet
        if [[ $(FIL_exists_in_remote "$FILNAVN" "$DEST_BANK") == "Eksisterer" ]] || \
           [[ $(FIL_exists_in_remote "$FILNAVN" "$ARKIV_BANK_DEST") == "Eksisterer" ]]; then
            log_message "Fil $FILNAVN eksisterer allerede i $DEST_BANK eller $ARKIV_BANK_DEST. Hopper over."
            continue
        fi

        # Håndter 0KB filer
        if [ ! -s "$FIL" ]; then
            prosesser_0kb_fil "$FIL" "$ARKIV_BANK" "$DEST_BANK"
            continue
        fi

        # Kopier fil med scp
        scp -pC "$FIL" "$DEST_USER@$DEST_HOST:$DEST_BANK/"
        if [ $? -eq 0 ]; then
            log_message "Overført $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_BANK."
        else
            log_message "Feilet overføring av $FILNAVN til $DEST_USER@$DEST_HOST:$DEST_BANK."
            continue
        fi

        # Arkiver lokal fil
        mv "$FIL" "$ARKIV_BANK/"
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til $ARKIV_BANK."
        else
            log_message "Feilet arkivering av $FILNAVN."
        fi
    done
}

# Prosesserer tsp-filer
process_tsp_files() {
    find "$SRC_BANK" -type f -name "tsp*.txt_D*" | while read FIL; do
        FILNAVN=$(basename "$FIL")
        log_message "Prosesserer tsp-fil $FILNAVN. Flyttes direkte til arkivet."

        mv "$FIL" "$ARKIV_BANK/"
        if [ $? -eq 0 ]; then
            log_message "Arkiverte $FILNAVN til $ARKIV_BANK."
        else
            log_message "Feilet arkivering av fil $FILNAVN."
        fi
    done
}

check_directory_exists "$ARKIV_BANK"

process_tsp_files
process_tdnb_bank_files

log_message "Bankfil overføring og arkivering gjennomført."
