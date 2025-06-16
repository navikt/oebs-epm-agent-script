def bindVariables = agentAPI.getBindVariables()
def rule = bindVariables["Rule"]
def outputFilePath = agentContext["DATAFILENAME"]
def file = new File(outputFilePath)
def String encoding = "ISO-8859-1"

def logInfo(msg)  { println "[ARCHIVE][INFO]  ${msg}" }
def logWarn(msg)  { println "[ARCHIVE][WARN]  ${msg}" }
def logError(msg) { println "[ARCHIVE][ERROR] ${msg}" }


if (!file.exists()) {
    logError("No output file found to archive: $outputFilePath")
    return 1
}

def archiveDirPath = ""

if (rule == "BANK_LOAD") {
    archiveDirPath = "/data/ar/dev/inbox/bank/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "OEBS_BANK_LOAD") {
    archiveDirPath = "/data/ar/dev/inbox/OracleGL/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Daglig_Bestilling") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Daglig_Faktura") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Tilsagn_Arena_LT") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arena/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Tilsagn_Arena_LT-refusjon") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Tilsagn_Arena_Annul") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/annullert/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_RefKrav_Arena") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_RefKrav_Arena_Tilsagn_Godkjent") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Manedlig_OEBS") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Tiltak_Manedlig") {
    archiveDirPath = "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Grensesnitt") {
    archiveDirPath = "/data/ar/dev/inbox/grensesnitt/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else if (rule == "Grensesnitt_fag") {
    archiveDirPath = "/data/ar/dev/inbox/grensesnitt/arkiv/"
    logInfo("Archiving file for rule: ${rule} to ${archiveDirPath}")
} else {
    logError("No archive logic defined for rule: '${rule}'. No action taken.")
    return 0
}

// Move the file
def archivedFile = new File(archiveDirPath, file.name)
copyFileWithEncoding(file, archivedFile, encoding)

if (!archivedFile.exists()) {
    logError("File copy failed: ${archivedFile.absolutePath}")
    return 1
} else {
    logInfo("File successfully copied to archive: ${archivedFile.absolutePath}")
}


def copyFileWithEncoding(File src, File dst, String encoding) {
    src.withReader(encoding) { reader ->
        dst.withWriter(encoding) { writer ->
            writer << reader
        }
    }
    if (src.delete()) {
        logInfo("Deleted source file after copying: ${src.absolutePath}")
    } else {
        logWarn("Failed to delete original file after copying: ${src.absolutePath}")
    }
}

