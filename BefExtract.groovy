import groovy.xml.XmlSlurper
import groovy.transform.Field

import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.text.ParseException

// Turn off SQL Processing by Agent
agentAPI.skipAction('true')

// Get bind variables - Rule variable determines which block of code runs.
def bindVariables = agentAPI.getBindVariables()
def rule = bindVariables["Rule"]
String outFileName = agentContext["DATAFILENAME"] as String
File outputFile = new File(outFileName)
String encoding = "ISO-8859-1"

// Logging helpers
@Field def logInfo  = { msg -> println "[EXTRACT][INFO]  ${msg}" }
@Field def logWarn  = { msg -> println "[EXTRACT][WARN]  ${msg}" }
@Field def logError = { msg -> println "[EXTRACT][ERROR] ${msg}" }

// Temporary verification toggle for runtime path detection.
boolean DEBUG_PATH_VERIFICATION = false

def failMalformed(String message) {
    logError(message)
    throw new IllegalStateException(message)
}

def writeOutput = { String data, String enc = encoding ->
    outputFile.write(data ?: "", enc)
}

def withRuleDiagnostics(String ruleName, Closure handler) {
    long ruleStart = System.nanoTime()
    logDiagInfo("rule.start", [rule: ruleName])
    handler.call()
    long durationMs = Math.floorDiv(System.nanoTime() - ruleStart, 1_000_000L)
    logDiagInfo("rule.completed", [rule: ruleName, durationMs: durationMs])
}

def formatAvailableFiles(List<String> fileNames) {
    return fileNames.isEmpty() ? "(ingen filer)" : fileNames.join(", ")
}

def formatLogDetails(Map details) {
    if (!details || details.isEmpty()) {
        return ""
    }
    return details.collect { k, v -> "${k}=${v}" }.join(" | ")
}

def logDiagInfo(String event, Map details = [:]) {
    def suffix = formatLogDetails(details)
    logInfo("event=${event}" + (suffix ? " | ${suffix}" : ""))
}

def logDiagWarn(String event, Map details = [:]) {
    def suffix = formatLogDetails(details)
    logWarn("event=${event}" + (suffix ? " | ${suffix}" : ""))
}

String runDir = System.getProperty("user.dir") ?: ""
String baseRoot = (runDir.contains("/data/ar/prod/") || runDir.endsWith("/data/ar/prod")) ? "/data/ar/prod" :
        ((runDir.contains("/data/ar/dev/") || runDir.endsWith("/data/ar/dev")) ? "/data/ar/dev" : "")
if (!baseRoot) {
    failMalformed("Cannot determine base root from user.dir='${runDir}'")
}
logInfo("Resolved base root: ${baseRoot}")

if (DEBUG_PATH_VERIFICATION) {
    logInfo("DEBUG user.dir='${runDir}'")
    logInfo("DEBUG baseRoot='${baseRoot}'")
    def inboxDir = new File("${baseRoot}/inbox")
    if (!inboxDir.exists()) {
        failMalformed("DEBUG check failed: '${inboxDir.getAbsolutePath()}' does not exist. user.dir='${runDir}'")
    }
    logInfo("DEBUG check passed: '${inboxDir.getAbsolutePath()}' exists")
}

def findFiles(String locationPath, Closure nameMatcher) {
    def dir = new File(locationPath)
    if (!dir.exists() || !dir.isDirectory()) {
        logError("Directory not found: ${locationPath}")
        return ([] as List<File>)
    }

    int discoveredFiles = 0
    List<String> availableFiles = []
    List<File> result = []
    dir.eachFile { File file ->
        if (!file.isFile()) {
            return
        }

        discoveredFiles++
        availableFiles << file.getName()
        if (nameMatcher(file.getName())) {
            logInfo("Selected file: ${file.getAbsolutePath()}")
            result << file
        }
    }

    if (result.isEmpty()) {
        logDiagWarn("findFiles.summary", [
            path          : locationPath,
            discoveredFiles: discoveredFiles,
            matchedFiles   : 0,
            availableFiles : formatAvailableFiles(availableFiles.sort())
        ])
    } else {
        logDiagInfo("findFiles.summary", [
            path          : locationPath,
            discoveredFiles: discoveredFiles,
            matchedFiles   : result.size()
        ])
    }
    return result.sort { a, b -> a.getName().toLowerCase(Locale.ROOT) <=> b.getName().toLowerCase(Locale.ROOT) }
}

// Copy file with encoding, then delete source
def copyFileWithEncoding(File src, File dst, String enc) {
    File parent = dst.getParentFile()
    if (parent && !parent.exists() && !parent.mkdirs()) {
        failMalformed("Could not create destination directory: ${parent.getAbsolutePath()}")
    }

    src.withReader(enc) { reader ->
        dst.withWriter(enc) { writer ->
            writer << reader
        }
    }
    if (!src.delete()) {
        logWarn("Could not delete source file after archive copy: ${src.getAbsolutePath()}")
    }
}

// Filter out blank lines or lines with only whitespace
def filterNonEmptyLines(List<String> lines) {
    return lines.findAll { line -> !line.trim().isEmpty() }
}

// Read all lines with explicit encoding and guaranteed reader close.
def readAllLines(File file, String enc) {
    List<String> lines = []
    file.withReader(enc) { reader ->
        lines = reader.readLines()
    }
    return lines
}

def readNonEmptyLinesWithStats(File file, String enc, String ruleName) {
    List<String> rawLines = readAllLines(file, enc)
    List<String> nonEmptyLines = filterNonEmptyLines(rawLines)
    logDiagInfo("file.lines", [
        rule         : ruleName,
        fileName     : file.getName(),
        rawLines     : rawLines.size(),
        nonEmptyLines: nonEmptyLines.size()
    ])
    return nonEmptyLines
}

// Read only a small preview to identify XML type without loading the full file.
def readFilePreview(File file, String enc, int maxLines = 40) {
    file.withReader(enc) { reader ->
        def lines = []
        for (int i = 0; i < maxLines; i++) {
            def line = reader.readLine()
            if (line == null) break
            lines << line
        }
        return lines.join("\n")
    }
}

// CAMT053 files in this flow can have generic names (for example GUID-content.xml),
// so we detect based on namespace/content instead of filename pattern.
def isCamt053File(File file) {
    if (!file?.isFile()) return false
    if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) return false

    try {
        def preview = readFilePreview(file, "UTF-8", 60).toLowerCase(Locale.ROOT)
        return preview.contains("urn:iso:std:iso:20022:tech:xsd:camt.053") ||
            preview.contains("camt.053")
    } catch (Exception ignored) {
        return false
    }
}

// Parse a date string from one format and return it formatted in another.
// Returns null and logs an error if parsing fails.
def reformatDate(String datestr, SimpleDateFormat parseFormat, SimpleDateFormat outputFormat) {
    try {
        return outputFormat.format(parseFormat.parse(datestr))
    } catch (ParseException e) {
        logError("Error parsing date '${datestr}': ${e.message}")
        return null
    }
}

// Returns the value of the Nth semicolon-delimited field (0-indexed).
// Returns null if the line has fewer fields than requested.
def getSemicolonField(String line, int fieldIdx) {
    int pos = 0
    for (int i = 0; i < fieldIdx; i++) {
        int sep = line.indexOf(";", pos)
        if (sep == -1) return null
        pos = sep + 1
    }
    int end = line.indexOf(";", pos)
    return end == -1 ? line.substring(pos) : line.substring(pos, end)
}

// Reformat the date stored in field index 2 (between 2nd and 3rd semicolon) of a
// semicolon-delimited line, and strip double-quote characters.
// Returns the modified line, or null if date parsing fails.
def reformatLineDate(String line, SimpleDateFormat parseFormat, SimpleDateFormat outputFormat) {
    def datestr = getSemicolonField(line, 2)
    if (datestr == null) return null
    def newdate = reformatDate(datestr.trim(), parseFormat, outputFormat)
    if (newdate == null) return null
    return line.replace(datestr, newdate).replace('"', "")
}

def isZeroOrBlankAmount(String amountText) {
    def normalized = amountText?.trim()?.replace(',', '.')
    if (!normalized) return true

    try {
        return new BigDecimal(normalized).compareTo(BigDecimal.ZERO) == 0
    } catch (Exception ignored) {
        return false
    }
}

def buildCamt053Output(File file, Closure logInfo, Closure logWarn) {
    def xml = new XmlSlurper(false, false).parse(file)
    def header = 'KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5'
    def rows = []

    def kundeNummer = firstNonBlank([
        xml.BkToCstmrStmt.GrpHdr.MsgRcpt.Id.OrgId.Othr.find { it.SchmeNm.Cd.text() == 'CUST' }?.Id?.text(),
        xml.BkToCstmrStmt.Stmt?.find { true }?.Acct?.Ownr?.Id?.OrgId?.Othr?.find { true }?.Id?.text()
    ])

    int statementCount = 0
    xml.BkToCstmrStmt.Stmt.each { stmt ->
        statementCount++

        def accountNumber = normalizeAccountNumber(firstNonBlank([
            stmt.Acct.Id.IBAN.text(),
            stmt.Acct.Id.Othr.Id.text()
        ]))
        def statementCurrency = firstNonBlank([stmt.Acct.Ccy.text(), 'NOK'])

        stmt.Ntry.each { ntry ->
            def direction = firstNonBlank([ntry.CdtDbtInd.text(), 'CRDT'])
            def bookingDate = formatCamtDate(ntry.BookgDt.Dt.text(), logWarn)
            def txDetails = ntry.NtryDtls.TxDtls

            if (!txDetails || txDetails.size() == 0) {
                def signedAmount = formatSignedAmount(ntry.Amt.text(), direction, logWarn)
                rows << toCsvLine([
                    kundeNummer,
                    accountNumber,
                    firstNonBlank([ntry.Amt.@Ccy.text(), statementCurrency]),
                    bookingDate,
                    toOreAmount(signedAmount, logWarn),
                    signedAmount,
                    firstNonBlank([ntry.NtryRef.text(), ntry.AcctSvcrRef.text()]),
                    '',
                    '',
                    buildBankContent5(ntry, direction),
                    ''
                ])
                return
            }

            txDetails.each { txDtls ->
                def signedAmount = formatSignedAmount(
                    firstNonBlank([txDtls.AmtDtls.TxAmt.Amt.text(), ntry.Amt.text()]),
                    direction,
                    logWarn
                )

                def remittanceLines = txDtls.RmtInf.Ustrd.collect { it.text() }.findAll { it }
                def remittanceDeduped = remittanceLines.inject([]) { acc, v ->
                    (acc.isEmpty() || acc.last() != v) ? acc + v : acc
                }
                def remittanceText = collapseWhitespace(remittanceDeduped.join(''))
                if (!remittanceText) {
                    remittanceText = collapseWhitespace(txDtls.AddtlTxInf.text())
                }

                def content1 = (remittanceText == 'OVERF. NORGES BANK')
                    ? extractRelatedAccount(txDtls.RltdPties.DbtrAcct)
                    : firstNonBlank([
                        txDtls.Refs.ClrSysRef.text(),
                        txDtls.Refs.EndToEndId.text(),
                        txDtls.Refs.TxId.text(),
                        txDtls.Refs.AcctSvcrRef.text(),
                        ntry.AcctSvcrRef.text(),
                        ntry.NtryRef.text()
                    ])

                rows << toCsvLine([
                    kundeNummer,
                    accountNumber,
                    firstNonBlank([txDtls.AmtDtls.TxAmt.Amt.@Ccy.text(), ntry.Amt.@Ccy.text(), statementCurrency]),
                    bookingDate,
                    toOreAmount(signedAmount, logWarn),
                    signedAmount,
                    content1,
                    remittanceText,
                    findCounterpartyName(txDtls, direction),
                    buildBankContent5(ntry, direction),
                    ''
                ])
            }
        }
    }

    logInfo("Processed CAMT.053 file '${file.getName()}' with ${statementCount} statements and ${rows.size()} output rows.")
    return ([header] + rows).join('\n')
}

def findCounterpartyName(txDtls, String direction) {
    if (direction == 'DBIT') {
        return firstNonBlank([
            txDtls.RltdPties.Cdtr.Nm.text(),
            txDtls.RltdPties.UltmtCdtr.Nm.text(),
            txDtls.RltdPties.Dbtr.Nm.text()
        ])
    }

    return firstNonBlank([
        txDtls.RltdPties.Dbtr.Nm.text(),
        txDtls.RltdPties.UltmtDbtr.Nm.text(),
        txDtls.RltdPties.Cdtr.Nm.text()
    ])
}

def extractRelatedAccount(accountNode) {
    return normalizeAccountNumber(firstNonBlank([
        accountNode?.Id?.IBAN?.text(),
        accountNode?.Id?.Othr?.Id?.text()
    ]))
}

def buildBankContent5(ntry, String direction) {
    return [
        ntry.BkTxCd.Prtry.Cd.text(),
        ntry.BkTxCd.Domn.Cd.text(),
        ntry.BkTxCd.Domn.Fmly.Cd.text(),
        ntry.BkTxCd.Domn.Fmly.SubFmlyCd.text(),
        direction
    ].findAll { it }.join('/')
}

def formatCamtDate(String dateText, Closure logWarn) {
    def trimmedDate = firstNonBlank([dateText])
    if (!trimmedDate) {
        return ''
    }

    try {
        def parsed = new SimpleDateFormat('yyyy-MM-dd', Locale.ENGLISH).parse(trimmedDate)
        return new SimpleDateFormat('dd-MMM-yy', Locale.ENGLISH).format(parsed)
    } catch (Exception e) {
        logWarn("Could not parse CAMT date '${dateText}': ${e.message}")
        return trimmedDate
    }
}

def formatSignedAmount(String amountText, String direction, Closure logWarn) {
    def normalizedAmount = firstNonBlank([amountText]).replace(',', '.')
    if (!normalizedAmount) {
        return ''
    }

    try {
        def amount = new BigDecimal(normalizedAmount)
        if (direction == 'DBIT' && amount.signum() > 0) {
            amount = amount.negate()
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    } catch (Exception e) {
        logWarn("Could not parse CAMT amount '${amountText}': ${e.message}")
        return normalizedAmount
    }
}

def toOreAmount(String amountText, Closure logWarn) {
    def normalizedAmount = firstNonBlank([amountText]).replace(',', '.')
    if (!normalizedAmount) {
        return ''
    }

    try {
        return new BigDecimal(normalizedAmount)
            .multiply(new BigDecimal('100'))
            .setScale(0, RoundingMode.HALF_UP)
            .toPlainString()
    } catch (Exception e) {
        logWarn("Could not convert CAMT amount '${amountText}' to ore: ${e.message}")
        return normalizedAmount
    }
}

def normalizeAccountNumber(String accountNumber) {
    def normalized = firstNonBlank([accountNumber]).replace(' ', '')
    if (normalized.startsWith('NO') && normalized.length() > 4) {
        return normalized.substring(4)
    }
    return normalized
}

def collapseWhitespace(String value) {
    return value ? value.replaceAll(/\s+/, ' ').trim() : ''
}

def firstNonBlank(List values) {
    def firstValue = values.find { value ->
        value != null && value.toString().trim()
    }
    return firstValue == null ? '' : firstValue.toString().trim()
}

def toCsvLine(List values) {
    return values.collect { value -> escapeCsvValue(value) }.join(',')
}

def escapeCsvValue(value) {
    def normalizedValue = value == null ? '' : value.toString()
    if (normalizedValue.contains('"')) {
        normalizedValue = normalizedValue.replace('"', '""')
    }
    if (normalizedValue.contains(',') || normalizedValue.contains('\n') || normalizedValue.contains('\r') || normalizedValue.contains('"')) {
        return '"' + normalizedValue + '"'
    }
    return normalizedValue
}

// Shared date format instances (defined once to avoid repeated instantiation)
def FMT_ISO_DATE     = new SimpleDateFormat("yyyy-MM-dd")              // Innlesing: Arena/tiltak-filer (regel 3, 4, 5, 6)
def FMT_DD_MM_DATE   = new SimpleDateFormat("dd-MM-yyyy")              // Innlesing: norsk datoformat i avstemmingsfiler (regel 7, 8, 9, 12)
def FMT_COMPACT_DATE = new SimpleDateFormat("yyyyMMdd")                // Innlesing: Oracle-filer uten skilletegn – OB04, GL2BETTY, fagsystem (regel 1, 13, 14)
def FMT_NO_SHORT     = new SimpleDateFormat("dd-MMM-yy", Locale.forLanguageTag("no")) // Innlesing: norsk kort månedsnavn – GL2BETTY og Tiltak_Maanedlig (regel 2, 11)
def FMT_OUT_DATE     = new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH) // Utdata: standard EPM/ARCS-format med 4-sifret år (brukes i alle regler)
def FMT_OUT_SHORT    = new SimpleDateFormat("dd-MMM-yy",   Locale.ENGLISH) // Utdata: EPM/ARCS-format med 2-sifret år – kun BDATE i BANK_LOAD (regel 1)

Map<String, Closure> ruleHandlers = [:]

// 1. CAMT053_LOAD
ruleHandlers["CAMT053_LOAD"] = {
    long fullRunTimeStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/bank/camt053/"
    def destLocation = "${baseRoot}/inbox/bank/camt053/arkiv/"
    String filePath = ""
    String fileName = ""

    def header = 'KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5'

    def folder = new File(locationPath)
    if (!folder.exists()) {
        def msg = "CAMT053_LOAD: Directory not found: ${locationPath}"
        logError(msg)
        outputFile.write(header, "UTF-8")
        throw new RuntimeException(msg)
    }

    List<File> availableFiles = (folder.listFiles() ?: ([] as File[])).findAll { it.isFile() } as List<File>
    int discoveredFiles = availableFiles.size()
    logDiagInfo("camt053.discovery", [path: locationPath, discoveredFiles: discoveredFiles])

    List<File> matchingFiles = availableFiles
        .findAll { File file -> isCamt053File(file) }
        .sort { [it.lastModified(), it.getName()] }

    logDiagInfo("camt053.match", [path: locationPath, matchedFiles: matchingFiles.size()])

    if (matchingFiles.isEmpty()) {
        List<String> availableNames = availableFiles.collect { it.getName() }
        logDiagWarn("camt053.no_match", [
            path          : locationPath,
            discoveredFiles: discoveredFiles,
            availableFiles : formatAvailableFiles(availableNames.sort())
        ])
        outputFile.write(header, "UTF-8")
        return
    }

    StringBuilder outputBuilder = new StringBuilder()
    boolean firstFile = true
    int totalRowsBuilt = 0

    matchingFiles.each { File file ->
        filePath = file.getAbsolutePath()
        fileName = file.getName()
        int rawLinesInFile = readAllLines(file, "UTF-8").size()
        logDiagInfo("camt053.file.selected", [fileName: fileName, filePath: filePath, rawLines: rawLinesInFile])

        String fileOutput = buildCamt053Output(file, logInfo, logWarn)
        List<String> fileLines = fileOutput.readLines()
        int rowsInFile = Math.max((fileLines.size() - 1), 0)
        totalRowsBuilt += rowsInFile
        logDiagInfo("camt053.file.output", [fileName: fileName, outputRows: rowsInFile])

        if (firstFile) {
            outputBuilder.append(fileOutput)
            firstFile = false
        } else {
            // Hopp over header-linje for påfølgende filer
            outputBuilder.append('\n').append(fileLines.drop(1).join('\n'))
        }

        def dst = new File(destLocation + fileName)
        copyFileWithEncoding(file, dst, "UTF-8")
        logDiagInfo("camt053.file.archived", [fileName: fileName, archivePath: dst.getAbsolutePath()])
    }

    outputFile.write(outputBuilder.toString(), "UTF-8")
    logDiagInfo("camt053.output.written", [outputFile: outFileName, totalOutputRows: totalRowsBuilt])

    long fullRunTimeEnd = System.nanoTime()
    long fullDuration = fullRunTimeEnd - fullRunTimeStart
    logInfo("CAMT053 processing completed in: " + (fullDuration / 1_000_000) + " ms")
}

// 2. BANK_LOAD
ruleHandlers["BANK_LOAD"] = {
    logInfo("Starting rule: BANK_LOAD")
    long fullRunTimeStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/bank/"
    def destLocation = "${baseRoot}/inbox/bank/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("tdnb") }
    if (matchingFiles.isEmpty()) {
        logError("No matching file found with 'tdnb' in its name.")
        return
    }

    File file = matchingFiles[0]
    def fileName = file.getName()
    int bankRawLines = readAllLines(file, encoding).size()
    logDiagInfo("file.lines", [rule: "BANK_LOAD", fileName: fileName, rawLines: bankRawLines])
    StringBuilder edit = new StringBuilder()

    // Loop through each line – close reader automatically via withReader
    file.withReader(encoding) { reader ->
        String currentLine = reader.readLine()
        while (currentLine != null) {
            if (!currentLine.trim().isEmpty()) {
                if (currentLine.length() < 8) {
                    failMalformed("BANK_LOAD: malformed input row (short header)")
                }
                String code = currentLine.substring(0, 8)
                if (code == "940SWI01" || code == "940SWI04" || code == "940SWI05") {
                    String nextLine = reader.readLine()
                    if (nextLine != null) {
                        edit.append("\n").append(currentLine).append(nextLine)
                    }
                } else if (code == "940SWI02") {
                    String nextLine1 = reader.readLine()
                    String nextLine2 = reader.readLine()
                    if (nextLine1 != null && nextLine2 != null) {
                        edit.append("\n").append(currentLine).append(nextLine1).append(nextLine2)
                    }
                }
            }
            currentLine = reader.readLine()
        }
    }

    def myFile = new File(locationPath + 'mySuperFile2.txt')
    myFile.write(edit.toString().split('\n').drop(1).join('\n'), encoding)
    edit = null // release StringBuilder memory

    def editContents = []
    myFile.withReader(encoding) { fileReader ->
        editContents = fileReader.readLines()
    }

    def numLinesEdit = editContents.size()
    logInfo("BANK_LOAD: parsed ${numLinesEdit} lines from intermediate file")
    StringBuilder finalDataBuilder = new StringBuilder()
    def VALKODE = ''
    def ACCOUNTNUMBER = ''
    int rowsWritten = 0

    for (int i = 0; i < numLinesEdit; i++) {
        String line = editContents.get(i)
        if (line.length() < 8) {
            failMalformed("BANK_LOAD: malformed parsed row at index ${i} (short header)")
        }
        String code = line.substring(0, 8)

        if (code == "940SWI01") {
            if (line.length() < 119) {
                failMalformed("BANK_LOAD: malformed 940SWI01 row at index ${i}")
            }
            VALKODE = line.substring(116, 119)
            ACCOUNTNUMBER = line.substring(41, 52)
        } else if (code == "940SWI02") {
            if (line.length() < 198) {
                failMalformed("BANK_LOAD: malformed 940SWI02 row at index ${i}")
            }
            String KUNDENUMMER = line.substring(10, 20)
            def bankDate = FMT_COMPACT_DATE.parse(line.substring(190, 198))
            String BDATE = FMT_OUT_SHORT.format(bankDate)
            String BELOP = line.substring(103, 118)
            String AMOUNT = BELOP[0..-3] + '.' + BELOP[-2..-1]
            def contents = []

            for (int j = 1; j <= 5 && i + j < numLinesEdit; j++) {
                String nextLine = editContents.get(i + j)
                if (nextLine.length() < 8) {
                    failMalformed("BANK_LOAD: malformed continuation row at index ${i + j}")
                }
                String nextCode = nextLine.substring(0, 8)
                if (nextCode == "940SWI02") break
                if (nextCode == "940SWI04") {
                    if (nextLine.length() < 139) {
                        failMalformed("BANK_LOAD: malformed 940SWI04 continuation row at index ${i + j}")
                    }
                    contents << nextLine.substring(90, 139)
                }
            }
            while (contents.size() < 5) { contents << '' }

            finalDataBuilder.append("\n")
                .append(KUNDENUMMER).append(',')
                .append(ACCOUNTNUMBER).append(',')
                .append(VALKODE).append(',')
                .append(BDATE).append(',')
                .append(BELOP).append(',')
                .append(AMOUNT).append(',')
                .append(contents.join(','))
            rowsWritten++
        }
    }

    // Write intermediate result to temp file and stream-trim per field
    def myFile1 = new File(locationPath + 'ARCSInput.txt')
    myFile1.withWriter(encoding) { writer ->
        writer.write(finalDataBuilder.toString().trim())
    }
    finalDataBuilder = null // release memory

    StringBuilder outputDataBuilder = new StringBuilder('KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5')

    myFile1.withReader(encoding) { trimReader ->
        trimReader.eachLine { row ->
            String cleanedRow = row.split(',').collect { it.trim() }.join(',')
            outputDataBuilder.append('\n').append(cleanedRow)
        }
    }

    String outputData = outputDataBuilder.toString().trim()
    myFile.delete()
    myFile1.delete()
    writeOutput(outputData)

    long fullRunTimeEnd = System.nanoTime()
    long fullDuration = fullRunTimeEnd - fullRunTimeStart
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(file, dst, encoding)
    logInfo("BANK_LOAD complete: ${rowsWritten} rows written. Full runtime: ${fullDuration / 1_000_000} ms")
}

// 3. OEBS_BANK_LOAD
ruleHandlers["OEBS_BANK_LOAD"] = {
    logInfo("Starting rule: OEBS_BANK_LOAD")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/OracleGL"
    def destLocation = "${baseRoot}/inbox/OracleGL/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("GL2BETTY") }

    StringBuilder output = new StringBuilder("Konto-Number;Reference1;Reference2;Debit;Credit;Date;Content1;Content2;Amount")
    int rowsWritten = 0

    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "OEBS_BANK_LOAD")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def debitStr  = getSemicolonField(line, 3) ?: ""
            def creditStr = getSemicolonField(line, 4) ?: ""
            def datestr   = getSemicolonField(line, 5)?.trim()
            if (!datestr) {
                failMalformed("OEBS_BANK_LOAD: missing or blank date field in line: '${line}' in file: ${file.getName()}")
            }

            def debit  = debitStr  == "" ? BigDecimal.ZERO : new BigDecimal(debitStr)
            def credit = creditStr == "" ? BigDecimal.ZERO : new BigDecimal(creditStr)

            def newdate = reformatDate(datestr, FMT_NO_SHORT, FMT_OUT_DATE)
            if (newdate == null) failMalformed("OEBS_BANK_LOAD: failed to reformat date '${datestr}' in line: '${line}' in file: ${file.getName()}")
            line = line.replace(datestr, newdate)
            output.append('\n').append(line).append(';').append((debit + credit).toPlainString())
            rowsWritten++
        }
    }
    writeOutput(output.toString())
    logInfo("OEBS_BANK_LOAD complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 4. Tiltak_Daglig_Bestilling
ruleHandlers["Tiltak_Daglig_Bestilling"] = {
    logInfo("Starting rule: Tiltak_Daglig_Bestilling")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("Tiltak_Daglig") }

    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Belob;Valuta;Organisasjon;Bedrift;Lopenummer;Referanse")
    int rowsWritten = 0
    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Daglig_Bestilling")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def type_str = getSemicolonField(line, 0)
            if (type_str == 'BESTILLING' || line.startsWith("LT-BESTILLING")) {
                def processed = reformatLineDate(line, FMT_ISO_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed).append(";;")
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Daglig_Bestilling complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 5. Tiltak_Daglig_Faktura
ruleHandlers["Tiltak_Daglig_Faktura"] = {
    logInfo("Starting rule: Tiltak_Daglig_Faktura")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("Tiltaksfaktura_Daglig") }
    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Belob;Valuta;Organisasjon;Bedrift;Lopenummer")
    int rowsWritten = 0
    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Daglig_Faktura")

        def combinedLines = []
        def currentLine = ""
        for (line in fileContents) {
            if (line.startsWith("FAKTURA") || line.startsWith("LT-FAKTURA") || line.trim().isEmpty()) {
                if (currentLine) combinedLines << currentLine
                currentLine = line
            } else {
                currentLine += line.trim()
            }
        }
        if (currentLine) combinedLines << currentLine

        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in combinedLines) {
            line = line.replace(",", ".")
            def type_str = getSemicolonField(line, 0)
            if (type_str == "FAKTURA" || type_str == "LT-FAKTURA") {
                def processed = reformatLineDate(line, FMT_ISO_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed)
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Daglig_Faktura complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 6. Tiltak_Tilsagn_Arena_LT – only rows with type LT-tilsagn and status Godkjent (filter added)
ruleHandlers["Tiltak_Tilsagn_Arena_LT"] = {
    logInfo("Starting rule: Tiltak_Tilsagn_Arena_LT")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/LT/tilsagn"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/LT/tilsagn/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("LT_tiltak_avstemming_dag") || it.contains("TILSAGN") }

    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Bedrift;Organisasjon;Lopenummer")
    int rowsWritten = 0
    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Tilsagn_Arena_LT")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def type_str   = getSemicolonField(line, 0)
            def status_str = getSemicolonField(line, 3)

            if (type_str == "LT-tilsagn" && status_str == "Godkjent") {
                def processed = reformatLineDate(line, FMT_ISO_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed).append(";;")
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Tilsagn_Arena_LT complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 7. Tiltak_Tilsagn_Arena_LT-refusjon
ruleHandlers["Tiltak_Tilsagn_Arena_LT-refusjon"] = {
    logInfo("Starting rule: Tiltak_Tilsagn_Arena_LT-refusjon")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/LT/refusjon"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/LT/refusjon/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("LT_tiltak_avstemming_dag") || it.contains("REFUSJON") }
    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Bedrift;Organisasjon;Lopennummer")
    int rowsWritten = 0

    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Tilsagn_Arena_LT-refusjon")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def type_str = getSemicolonField(line, 0)
            if (type_str == "LT-refusjon") {
                def processed = reformatLineDate(line, FMT_ISO_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed).append(";")
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Tilsagn_Arena_LT-refusjon complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 8. Tiltak_Tilsagn_Arena_Annul
ruleHandlers["Tiltak_Tilsagn_Arena_Annul"] = {
    logInfo("Starting rule: Tiltak_Tilsagn_Arena_Annul")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/arena/tilsagn/annullert"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/arena/tilsagn/annullert/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("tiltak_avstemming_dag") }

    if (matchingFiles.isEmpty()) {
        logError("No files matching the pattern found in the directory.")
        return
    }

    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer")
    int rowsWritten = 0
    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Tilsagn_Arena_Annul")

        for (line in fileContents) {
            line = line.replace(",", ".")
            def type_str   = getSemicolonField(line, 0)
            def status_str = getSemicolonField(line, 3)
            if (type_str == 'Tilsagn' && status_str == "Annullert") {
                def processed = reformatLineDate(line, FMT_DD_MM_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed).append(";")
                rowsWritten++
            }
        }
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Tilsagn_Arena_Annul complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 9. Tiltak_RefKrav_Arena
ruleHandlers["Tiltak_RefKrav_Arena"] = {
    logInfo("Starting rule: Tiltak_RefKrav_Arena")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/arena/refusjon/"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/arena/refusjon/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("tiltak_avstemming_dag") }

    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer")
    int rowsWritten = 0
    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_RefKrav_Arena")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def type_str = getSemicolonField(line, 0)
            if (type_str == "Refusjonskrav") {
                def processed = reformatLineDate(line, FMT_DD_MM_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed)
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_RefKrav_Arena complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 10. Tiltak_RefKrav_Arena_Tilsagn_Godkjent
ruleHandlers["Tiltak_RefKrav_Arena_Tilsagn_Godkjent"] = {
    logInfo("Starting rule: Tiltak_RefKrav_Arena_Tilsagn_Godkjent")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/daglig/arena/tilsagn/"
    def destLocation = "${baseRoot}/inbox/tiltak/daglig/arena/tilsagn/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("tiltak_avstemming_dag") }

    StringBuilder output = new StringBuilder("Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer")
    int rowsWritten = 0
    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_RefKrav_Arena_Tilsagn_Godkjent")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def type_str   = getSemicolonField(line, 0)
            def status_str = getSemicolonField(line, 3)
            if (type_str == "Tilsagn" && status_str == "Godkjent") {
                def processed = reformatLineDate(line, FMT_DD_MM_DATE, FMT_OUT_DATE)
                if (processed == null) continue
                output.append('\n').append(processed)
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_RefKrav_Arena_Tilsagn_Godkjent complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 11. Tiltak_Manedlig_OEBS
ruleHandlers["Tiltak_Manedlig_OEBS"] = {
    logInfo("Starting rule: Tiltak_Manedlig_OEBS")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/maanedlig/"
    def destLocation = "${baseRoot}/inbox/tiltak/maanedlig/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("Tiltak_Maanedlig") }
    StringBuilder output = new StringBuilder("Referanse;Dato;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer")
    int rowsWritten = 0
    int rowsSkipped = 0

    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Manedlig_OEBS")

        for (line in fileContents) {
            line = line.replace(",", ".")
            def belop1 = getSemicolonField(line, 3)?.trim()

            if (isZeroOrBlankAmount(belop1)) {
                rowsSkipped++
                continue
            }

            if (line.length() < 28) {
                failMalformed("Tiltak_Manedlig_OEBS: malformed fixed-width row (expected at least 28 chars)")
            }

            def datestr = line.substring(19, 28).trim()
            def newdate = reformatDate(datestr, FMT_NO_SHORT, FMT_OUT_DATE)
            if (newdate == null) continue
            line = line.replace(datestr, newdate)
            output.append('\n').append(line)
            rowsWritten++
        }
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Manedlig_OEBS complete: ${rowsWritten} rows written, ${rowsSkipped} rows skipped (zero/empty amount). Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 12. Tiltak_Manedlig
ruleHandlers["Tiltak_Manedlig"] = {
    logInfo("Starting rule: Tiltak_Manedlig")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/tiltak/maanedlig"
    def destLocation = "${baseRoot}/inbox/tiltak/maanedlig/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("tiltak_avstemming_mnd") }
    StringBuilder output = new StringBuilder("Dato;Referanse;Year;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer")
    int rowsWritten = 0
    int rowsSkipped = 0

    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Tiltak_Manedlig")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            def belop1 = getSemicolonField(line, 4)?.trim()

            if (isZeroOrBlankAmount(belop1)) {
                rowsSkipped++
                continue
            }

            if (line.length() < 10) {
                failMalformed("Tiltak_Manedlig: malformed fixed-width row (expected at least 10 chars)")
            }

            def datestr = line.substring(0, 10)
            def newdate = reformatDate(datestr, FMT_DD_MM_DATE, FMT_OUT_DATE)
            if (newdate == null) continue
            line = line.replace(datestr, newdate)
            output.append('\n').append(line)
            rowsWritten++
        }
    }
    writeOutput(output.toString())
    logInfo("Tiltak_Manedlig complete: ${rowsWritten} rows written, ${rowsSkipped} rows skipped (zero/empty amount). Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 13. Grensesnitt
ruleHandlers["Grensesnitt"] = {
    logInfo("Starting rule: Grensesnitt")
    long ruleStart = System.nanoTime()
    def locationPath = "${baseRoot}/inbox/grensesnitt"
    def destLocation = "${baseRoot}/inbox/grensesnitt/arkiv/"
    def matchingFiles = findFiles(locationPath) { it.contains("OB04") }
    StringBuilder output = new StringBuilder("ref1,vedtaksID,belop_file,dato_overfort,person_nr,FOM,TOM,ytelseskonto,ref2,dato,kostnadssted,ansvarssted,ref3,rente1,rente2,ref4,fagsystem_ID")
    int rowsWritten = 0

    matchingFiles.each { file ->
        def fileContents = readNonEmptyLinesWithStats(file, encoding, "Grensesnitt")
        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encoding)

        for (line in fileContents) {
            line = line.replace(",", ".")
            if (line.length() < 23) {
                failMalformed("Grensesnitt: malformed fixed-width row (expected at least 23 chars)")
            }
            if (line.substring(22, 23) != " ") {
                if (line.length() < 179) {
                    failMalformed("Grensesnitt: malformed fixed-width row (expected at least 179 chars)")
                }
                def ref1         = line.substring(0, 15)
                def vedtaksID    = line.substring(19, 25)
                def belop_file   = line.substring(29, 40)
                def aDate        = FMT_COMPACT_DATE.parse(line.substring(40, 48))
                def dato_overfort = FMT_OUT_DATE.format(aDate)
                def personNr     = line.substring(48, 59)
                def FOM          = line.substring(59, 67)
                def TOM          = line.substring(67, 75)
                def ytelseskonto = line.substring(75, 80)
                def ref2         = line.substring(83, 97)
                def bDate        = FMT_COMPACT_DATE.parse(line.substring(101, 109))
                def dato         = FMT_OUT_DATE.format(bDate)
                def kostnadssted = line.substring(109, 113)
                def ansvarssted  = line.substring(113, 117)
                def ref3         = line.substring(117, 124)
                def rente1       = line.substring(140, 155)
                def rente2       = line.substring(155, 162)
                def ref4         = line.substring(162, 170)
                def fagsystem_ID = line.substring(170, 179)
                def newline      = [ref1, vedtaksID, belop_file, dato_overfort, personNr,
                                    FOM, TOM, ytelseskonto, ref2, dato, kostnadssted,
                                    ansvarssted, ref3, rente1, rente2, ref4, fagsystem_ID].join(",")
                output.append('\n').append(newline)
                rowsWritten++
            }
        }
    }
    writeOutput(output.toString())
    logInfo("Grensesnitt complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

// 14. Grensesnitt_fag
ruleHandlers["Grensesnitt_fag"] = {
    logInfo("Starting rule: Grensesnitt_fag")
    long ruleStart = System.nanoTime()
    def locationPath  = "${baseRoot}/inbox/grensesnitt"
    def destLocation  = "${baseRoot}/inbox/grensesnitt/arkiv/"
    def encodingFag   = "UTF-8"
    def matchingFiles = findFiles(locationPath) { it.contains("tilbake-p") || it.contains("navi") }

    if (matchingFiles.isEmpty()) {
        logError("No files matching the pattern found in the directory.")
        return
    }

    StringBuilder output = new StringBuilder("avsender;vedtakId;fnr;vedtaksdato;fagsakYtelseType;tilbakekrevesBruttoUtenRenter;skatt;tilbakekrevesNettoUtenRenter;renter;erOmgjøringTilIngenTilbakekreving")
    int rowsWritten = 0

    matchingFiles.each { file ->
        def rawLines = readAllLines(file, encodingFag)
        logDiagInfo("file.lines", [rule: "Grensesnitt_fag", fileName: file.getName(), rawLines: rawLines.size()])
        def fileContents = rawLines
        logInfo("Grensesnitt_fag: skipping header row in file ${file.getName()}")
        fileContents = filterNonEmptyLines(fileContents.drop(1))
        logDiagInfo("file.lines.after_header", [rule: "Grensesnitt_fag", fileName: file.getName(), nonEmptyLines: fileContents.size()])

        for (line in fileContents) {
            line = line.replace(",", ".")
            // Dato ligger i felt 3 (mellom 3. og 4. semikolon) i fagsystem-filen – ikke felt 2 som i øvrige regler
            def datestr = getSemicolonField(line, 3)?.trim()
            if (!datestr) {
                failMalformed("Grensesnitt_fag: missing or blank date field in line: '${line}' in file: ${file.getName()}")
            }
            def newdate = reformatDate(datestr, FMT_COMPACT_DATE, FMT_OUT_DATE)
            if (newdate == null) continue
            line = line.replace(datestr, newdate)
            output.append('\n').append(line)
            rowsWritten++
        }

        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encodingFag)
    }
    writeOutput(output.toString())
    logInfo("Grensesnitt_fag complete: ${rowsWritten} rows written. Runtime: ${(System.nanoTime() - ruleStart) / 1_000_000} ms")
}

Closure selectedRuleHandler = ruleHandlers[rule]
if (!selectedRuleHandler) {
    failMalformed("Unknown rule '${rule}'. Supported rules: ${ruleHandlers.keySet().sort().join(', ')}")
}
withRuleDiagnostics(rule?.toString(), selectedRuleHandler)
