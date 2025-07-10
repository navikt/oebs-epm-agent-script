// AftLoad.groovy – Archive original source file only after successful load

def rule

try {
    rule = fdmContext["RULENAME"]
} catch (MissingPropertyException e) {
    def bindVariables = agentAPI.getBindVariables()
    rule = bindVariables["Rule"]
}

def String encoding = "ISO-8859-1"

def logInfo(msg)  {
    println "[ARCHIVE][INFO]  ${msg}"
    fdmAPI.logInfo("[ARCHIVE][INFO]  ${msg}")
}
def logWarn(msg)  {
    println "[ARCHIVE][WARN]  ${msg}"
    fdmAPI.logWarn("[ARCHIVE][WARN]  ${msg}")
}
def logError(msg) {
    println "[ARCHIVE][ERROR] ${msg}"
    fdmAPI.logError("[ARCHIVE][ERROR] ${msg}")
}

// Define paths and filename patterns for each rule
def config = [
    "BANK_LOAD"                         : [src: "/data/ar/dev/inbox/bank/",                           dst: "/data/ar/dev/inbox/bank/arkiv/",                           pattern: "tdnb"],
    "OEBS_BANK_LOAD"                    : [src: "/data/ar/dev/inbox/OracleGL/",                       dst: "/data/ar/dev/inbox/OracleGL/arkiv/",                       pattern: "GL2BETTY"],
    "Tiltak_Daglig_Bestilling"         : [src: "/data/ar/dev/inbox/tiltak/daglig/",                  dst: "/data/ar/dev/inbox/tiltak/daglig/arkiv/",                  pattern: "Tiltak_Daglig"],
    "Tiltak_Daglig_Faktura"            : [src: "/data/ar/dev/inbox/tiltak/daglig/",                  dst: "/data/ar/dev/inbox/tiltak/daglig/arkiv/",                  pattern: "Tiltaksfaktura_Daglig"],
    "Tiltak_Tilsagn_Arena_LT"          : [src: "/data/ar/dev/inbox/tiltak/daglig/arena/",            dst: "/data/ar/dev/inbox/tiltak/daglig/arena/arkiv/",            pattern: "LT_tiltak_avstemming_dag"],
    "Tiltak_Tilsagn_Arena_LT-refusjon" : [src: "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/",   dst: "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/",   pattern: "LT_tiltak_avstemming_dag"],
    "Tiltak_Tilsagn_Arena_Annul"       : [src: "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/annullert/", dst: "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/annullert/arkiv/", pattern: "tiltak_avstemming_dag"],
    "Tiltak_RefKrav_Arena"             : [src: "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/",   dst: "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/",   pattern: "tiltak_avstemming_dag"],
    "Tiltak_RefKrav_Arena_Tilsagn_Godkjent": [src: "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/", dst: "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/", pattern: "tiltak_avstemming_dag"],
    "Tiltak_Manedlig_OEBS"             : [src: "/data/ar/dev/inbox/tiltak/maanedlig/",               dst: "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/",               pattern: "Tiltak_Maanedlig"],
    "Tiltak_Manedlig"                  : [src: "/data/ar/dev/inbox/tiltak/maanedlig/",               dst: "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/",               pattern: "tiltak_avstemming_mnd"],
    "Grensesnitt"                      : [src: "/data/ar/dev/inbox/grensesnitt/",                    dst: "/data/ar/dev/inbox/grensesnitt/arkiv/",                    pattern: "OB04"],
    "Grensesnitt_fag"                  : [src: "/data/ar/dev/inbox/grensesnitt/",                    dst: "/data/ar/dev/inbox/grensesnitt/arkiv/",                    pattern: "tilbake-p"]
]

def ruleConfig = config[rule]
if (!ruleConfig) {
    logError("No archive logic defined for rule: '${rule}'. No action taken.")
    return 1
}

def sourceDir = new File(ruleConfig.src)
def archiveDir = new File(ruleConfig.dst)
def pattern = ruleConfig.pattern

def matchingFiles = sourceDir.listFiles().findAll { it.name.contains(pattern) }
if (matchingFiles.isEmpty()) {
    logWarn("No source file matching pattern '${pattern}' found in '${sourceDir}'")
    return 1
}

matchingFiles.each { file ->
    def archivedFile = new File(archiveDir, file.name)
    copyFileWithEncoding(file, archivedFile, encoding)
    logInfo("File successfully copied to archive: ${archivedFile.absolutePath}")
}

return 0

// Helper method
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
