import groovy.json.* 
import groovy.xml.*
import java.util.zip.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.text.ParseException
import java.io.BufferedReader
import java.lang.StringBuilder

// Turn off SQL Processing by Agent 
agentAPI.skipAction('true')

// Get bind variables - Rule variable determines which block of code runs. Variables are defined in the 'query' menu in the application and registered application parameters
bindVariables = agentAPI.getBindVariables()
rule = bindVariables["Rule"]
def outFileName = agentContext["DATAFILENAME"]
outputFile = new File(outFileName)
def String encoding = "ISO-8859-1"

// 1. this block of code is for the bank file. The if logic around each code block drives which files is transformed and loaded.
if(rule == "BANK_LOAD") {
    long fullRunTimeStart = System.nanoTime()
    locationPath = "/data/ar/dev/inbox/bank/"
    destLocation = "/data/ar/dev/inbox/bank/arkiv/"
    filePath = ""
    fileName = ""
    def folder = new File(locationPath)
    if (!folder.exists()) {
        println "Directory not found: " + locationPath
        return
    } else {
        println "Directory found: " + locationPath
    }

    folder.eachFile() {
         file -> if(file.getName().contains("tdnb")){
             filePath = file.getAbsolutePath()
             fileName = file.getName()
             fileNameFromPath = filePath.substring(filePath.lastIndexOf("/") + 1)
             println "Selected file name: " + fileName
             println "Selected file: " + filePath
             println "Selected file from substring: " + fileNameFromPath
             println "File Path from substring: " + filePath.substring(filePath.lastIndexOf("/") + 1)
         }
      }

    // Check if a valid file was found
    if (filePath == "") {
        println "No matching file found with 'tdnb' in its name."
        return 
    }

    def file = new File(filePath)
    def reader = file.newReader(encoding) // Create the BufferedReader

    // Initialize variables
    StringBuilder edit = new StringBuilder()
    String currentLine = reader.readLine()
    int numLines = 0
    long startTime = System.nanoTime()

   // Loop through each line of the bank file and check if the line starts with 01, 04, 05 (two lines) or 02 (three lines) transaction codes
    while (currentLine != null) {
        if(!currentLine.trim().isEmpty()) {
            numLines++
            String code = currentLine.substring(0, 8)
            
            if (code == "940SWI01" || code == "940SWI04" || code == "940SWI05") {
                String nextLine = reader.readLine()
                if (nextLine != null) {
                    edit.append("\n").append(currentLine).append(nextLine)
                    numLines++
                } else {
                    println("Error: Forventet en linje etter kode 940SWI01/04/05 på linje: " + numLines + " ,men linjen mangler.")
                }
            } else if (code == "940SWI02") {
                String nextLine1 = reader.readLine()
                String nextLine2 = reader.readLine()
                if (nextLine1 != null && nextLine2 != null) {
                    edit.append("\n").append(currentLine).append(nextLine1).append(nextLine2)
                    numLines += 2
                } else {
                    println("Error: Forventet to linjer etter kode 940SWI02 på linje:" + numLines " ,men en eller begge linjene mangler.")
                }
            }
        }
        currentLine = reader.readLine()
    }

    reader.close()

    long endTime = System.nanoTime()
    long duration = endTime - startTime
    println "For loop executed in: " + (duration / 1_000_000) + " ms"
    println "For loop executed in: " + (duration / 1_000_000_000.0) + " seconds"

    // Write the result to the output file
    def myFile = new File(locationPath +'mySuperFile2.txt')
    // Remove the first empty line if any and join the rest
    myFile.write(edit.toString().split('\n').drop(1).join('\n'), encoding)

    def editContents = []
    myFile.withReader(encoding) { fileReader ->
        editContents = fileReader.readLines()
    }

    numLinesEdit = editContents.size()

    StringBuilder finalDataBuilder = new StringBuilder()
    long startTime2 = System.nanoTime()

    // Loop through each line of the file content
    for (int i = 0; i < numLinesEdit; i++) {
        String line = editContents.get(i)
        String code = line.substring(0, 8)

        // Handling 940SWI01 code
        if (code == "940SWI01") {
            VALKODE = line.substring(116, 119)
            ACCOUNTNUMBER = line.substring(41, 52)
        } else if (code == "940SWI02") {
            String KUNDENUMMER = line.substring(10, 20)
            def bankDate = new Date().parse("yyyyMMdd", line.substring(190, 198))
            String BDATE = bankDate.format("dd-MMM-yy")
            String BELOP = line.substring(103, 118)
            String AMOUNT = BELOP[0..-3] + '.' + BELOP[-2..-1]
            def contents = []

            for (int j = 1; j <= 5 && i + j < numLinesEdit; j++) {
                String nextLine = editContents.get(i + j)
                if (nextLine.substring(0, 8) == "940SWI02") {
                    break // Stop if another 940SWI02 is encountered
                }
                if (nextLine.substring(0, 8) == "940SWI04") {
                    contents << nextLine.substring(90, 139) // Add 940SWI04 content
                }
            }

            // Ensure the content array has exactly 5 elements by padding with empty strings
            while (contents.size() < 5) {
                contents << ''
            }

            // Append to finalDataBuilder
            finalDataBuilder.append("\n")
                .append(KUNDENUMMER).append(',')
                .append(ACCOUNTNUMBER).append(',')
                .append(VALKODE).append(',')
                .append(BDATE).append(',')
                .append(BELOP).append(',')
                .append(AMOUNT).append(',')
                .append(contents.join(','))
        }
    }

    long endTime2 = System.nanoTime()
    long duration2 = endTime2 - startTime2
    println "2nd For loop executed in: " + (duration2 / 1_000_000) + " ms"
    println "2nd For loop executed in: " + (duration2 / 1_000_000_000.0) + " seconds"

    def myFile1 = new File(locationPath + 'ARCSInput.txt')
    myFile1.withWriter(encoding) { writer ->
        writer.write(finalDataBuilder.toString().trim())
    }

    StringBuilder outputDataBuilder = new StringBuilder('KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5')
    long startTime3 = System.nanoTime()

    // Process each line from the file
    myFile1.withReader(encoding) { trimReader ->
        trimReader.eachLine { row ->
            String cleanedRow = row.split(',').collect { it.trim() }.join(',')
            outputDataBuilder.append('\n').append(cleanedRow)
        }
    }

    String outputData = outputDataBuilder.toString().trim()

    long endTime3 = System.nanoTime()
    long duration3 = endTime3 - startTime3
    println "3rd For loop executed in: " + (duration3 / 1_000_000) + " ms"
    println "3rd For loop executed in: " + (duration3 / 1_000_000_000.0) + " seconds"

    def myFile2 = new File(locationPath + 'ARCSInput2.txt')
    myFile2.withWriter(encoding) { outputWriter ->
        outputWriter.write(outputData) // Write once
    }
  
    myFile.delete()
    myFile1.delete()
    myFile2.delete()

    // Write the final output to the file
    outputFile.write(outputData)

    long fullRunTimeEnd = System.nanoTime()
    long fullDuration = fullRunTimeEnd - fullRunTimeStart
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)
    println "Full runtime in: " + (fullDuration / 1_000_000) + " ms"
    println "Full runtime in: " + (fullDuration / 1_000_000_000.0) + " seconds"
}

//2. this block of code is for the OEBS bank file
else if(rule == "OEBS_BANK_LOAD"){
    locationPath = "/data/ar/dev/inbox/OracleGL"
    destLocation = "/data/ar/dev/inbox/OracleGL/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("GL2BETTY")){
             filePath = file.getAbsolutePath()
             fileName = file.getName()
             fileNameFromPath = filePath.substring(filePath.lastIndexOf("/") + 1)
             println "Selected file name: " + fileName
             println "Selected file: " + filePath
             println "Selected file from substring: " + fileNameFromPath
             println "File Path from substring: " + filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    output = "Konto-Number;Reference1;Reference2;Debit;Credit;Date;Content1;Content2;Amount"
    for(line in fileContents) {
        line = line.replaceAll(",",".")
        // this section of code parses the string looking at the location of ; delimiter to grab specific debit and credit balances for each line and date
        def deb_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
        def deb_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        def cred_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        def cred_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1) + 1)
        def date_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1) + 1)
        def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1) + 1) + 1)
        def debit 
        if(line.substring(deb_pos_1+1,deb_pos_2) == ""){
            debit = 0
        }else{debit = Float.valueOf(line.substring(deb_pos_1+1,deb_pos_2))}
        def credit
        if(line.substring(cred_pos_1+1,cred_pos_2) == ""){
            credit = 0
        }else{credit = Float.valueOf(line.substring(cred_pos_1+1,cred_pos_2))}

        def datestr = line.substring(date_pos_1 + 1, date_pos_2).trim()
        def norwegianDateFormat = new SimpleDateFormat("dd-MMM-yy", Locale.forLanguageTag("no")) // Parsing with Norwegian locale
        def date
        try {
            date = norwegianDateFormat.parse(datestr)
        } catch (ParseException e) {
            println "Error parsing date: ${e.message}"
        }

        def sdf = new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH) // Formatting with English locale
        def newdate = sdf.format(date)

        // Replace the original date string with the newly formatted date in the line
        line = line.replace(datestr, newdate)
        output = output + '\n' + line + ';' + (debit + credit).toString()
    }
     outputFile.write(output)
}

// 3. this block of code loads the tiltak daglig file with only lines of type BESTTILLING
else if(rule == "Tiltak_Daglig_Bestilling") {
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("Tiltak_Daglig")){
            filePath = file.getAbsolutePath()
            fileName = file.getName()
            println "Selected file name: " + fileName
            println "Selected file: " + filePath
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)   
    
    output="Type;Tilsagnsnummer;Dato;Belob;Valuta;Organisasjon;Bedrift;Lopenummer;Referanse"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        if(type_str == 'BESTILLING') {
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new Date().parse("yyyy-MM-dd", datestr)
            newdate = date.format("dd-MMM-yyyy")
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line + ";;"
        }
    }
    outputFile.write(output)
}


//4. This block of code loads the Tiltak Daglig Faktura file loading only lines with type FAKTURA
else if(rule == "Tiltak_Daglig_Faktura") {
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("Tiltaksfaktura_Daglig")){
            filePath = file.getAbsolutePath()
            fileName = file.getName()
            println "Selected file name: " + fileName
            println "Selected file: " + filePath
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    output="Type;Tilsagnsnummer;Dato;Belob;Valuta;Organisasjon;Bedrift;Lopenummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        if(type_str == 'FAKTURA') {
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new Date().parse("yyyy-MM-dd", datestr)
            newdate = date.format("dd-MMM-yyyy")
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line
        }
    }
    outputFile.write(output)
}

//5. This block of code loads the LT tiltak avstemming daglig file loading only rows with type LT-tilsagn and status of Godkjent
else if(rule == "Tiltak_Tilsagn_Arena"){
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("LT_tiltak_avstemming_dag")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    output="Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        def status_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
        def status_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        status_str = line.substring(status_pos_1+1,status_pos_2)
        if(type_str == '"LT-tilsagn' && status_str == "Godkjent"){
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new Date().parse("yyyy-MM-dd", datestr)
            newdate = date.format("dd-MMM-yyyy")
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line + ";"
        }
    }
    outputFile.write(output)
}

//6. This code block loads the LT tiltak avstemming daglig file loading only rows with type LT-refusjon
else if(rule == "Tiltak_Tilsagn_Arena_LT-refusjon"){
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("LT_tiltak_avstemming_dag")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)   
    
    output="Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        if(type_str == '"LT-refusjon'){
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new Date().parse("yyyy-MM-dd", datestr)
            newdate = date.format("dd-MMM-yyyy")
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line + ";"
        }
    }
    outputFile.write(output)
}

//7. This code block loads the Tilsagn avsetemming daglig tiltak file, loading only rows with type Tilsagn and status Annullert 
else if(rule == "Tiltak_Tilsagn_Arena_Annul"){
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/" 
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("tiltak_avstemming_dag")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    output="Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        def status_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
        def status_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        status_str = line.substring(status_pos_1+1,status_pos_2)
        if(type_str == 'Tilsagn' && status_str == "Annullert"){
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new SimpleDateFormat("dd-MM-yyyy").parse(datestr)
            newdate = new SimpleDateFormat("dd-MMM-yyyy").format(date)
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line + ";"
        }
    }
    outputFile.write(output)
}

//8. This code block loads the Tilsagn avsetemming daglig tiltak file, loading only rows with type Refusjonskrav 
else if(rule == "Tiltak_RefKrav_Arena"){
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("tiltak_avstemming_dag")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
         }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    output="Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        def status_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
        def status_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        status_str = line.substring(status_pos_1+1,status_pos_2)
        if(type_str == "Refusjonskrav"){
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new SimpleDateFormat("dd-MM-yyyy").parse(datestr)
            newdate = new SimpleDateFormat("dd-MMM-yyyy").format(date)
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line
        }
    }
    outputFile.write(output)
}

// 9. This block of code loads the Tilsagn avsetemming daglig tiltak file, loading only rows with type Tilsagn and status Attestert
else if(rule == "Tiltak_RefKrav_Arena_Tilsagn_Godkjent"){
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("tiltak_avstemming_dag")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
         }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)
    
    output="Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        def status_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
        def status_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        status_str = line.substring(status_pos_1+1,status_pos_2)
        if(type_str == "Tilsagn" && status_str == "Godkjent"){
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new SimpleDateFormat("dd-MM-yyyy").parse(datestr)
            newdate = new SimpleDateFormat("dd-MMM-yyyy").format(date)
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            output = output + '\n' + line
        }
    }
    outputFile.write(output)
}

// 10. This block of code loads the Tilsagn avsetemming daglig tiltak file, loading only rows with type Tilsagn and status Annullert
else if(rule == "Tiltak_RefKrav_Arena_Tilsagn_Annullert"){
    locationPath = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/"
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/"

    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("tiltak_avstemming_dag")){
            path = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
         }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    output="Type;Tilsagnsnummer;Dato;Status;Belob;Valuta;Organisasjon;Bedrift;Lopennummer"
    for(line in fileContents) {
        def type_pos_1 = line.indexOf(";")
        type_str = line.substring(0,type_pos_1)
        def status_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
        def status_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
        status_str = line.substring(status_pos_1+1,status_pos_2)
        if(type_str == "Tilsagn" && status_str == "Annullert"){
            def date_pos_1 = line.indexOf(";", line.indexOf(";") + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            datestr = line.substring(date_pos_1+1,date_pos_2)
            date = new SimpleDateFormat("dd-MM-yyyy").parse(datestr)
            newdate = new SimpleDateFormat("dd-MMM-yyyy").format(date)
            line = line.replaceAll(datestr,newdate)
            line = line.replaceAll('"',"")
            print(line)
            output = output + '\n' + line
        }
    }
    outputFile.write(output)
}

// 11. This block of code loads data from the Tiltakl Maanedlig file
else if(rule == "Tiltak_Manedlig"){
    locationPath = "/data/ar/dev/inbox/tiltak/maanedlig/"
    destLocation = "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("Tiltak_Maanedlig")){
            path = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)
    // define header row for the file
    output="Referanse;Dato;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer"
    output = output +'\n' + fileContents
    outputFile.write(output)
}

// 12. This block of code loads data from the Manedlig_OEBS file
else if(rule == "Tiltak_Manedlig_OEBS"){
    locationPath = "/data/ar/dev/inbox/tiltak/maanedlig"
    destLocation = "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
         file -> if(file.getName().contains("tiltak_avstemming_mnd")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)
    
    // define header row for the file
    output="Dato;Referanse;Year;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer"
    for(line in fileContents) {
        datestr = line.substring(0,10)
        date = new Date().parse("dd.MM.yyyy",datestr)
        newdate = date.format("dd-MMM-yyyy")
        line = line.replaceAll(datestr,newdate.toString())
        output = output +'\n' + line
    }
    outputFile.write(output)
}

// 13. This block of code loads data from the OB04 Grensesnitt file
else if(rule == "Grensesnitt"){
    locationPath = "/data/ar/dev/inbox/grensesnitt"
    destLocation = "/data/ar/dev/inbox/grensesnitt/arkiv/"
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
        file -> if(file.getName().contains("OB04")){
            filePath = file.getAbsolutePath()
            fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
            println filePath
            println filePath.substring(filePath.lastIndexOf("/") + 1)
        }
    }

    def fileContents = new File(filePath).newReader(encoding).readLines()
    fileContents = filterNonEmptyLines(fileContents)
    def src = new File(filePath)
    def dst = new File(destLocation + fileName)
    copyFileWithEncoding(src, dst, encoding)

    // define header row for the file and set the comma-delimited output headers for the output file
    output = "ref1,vedtaksID,belop_file,dato_overfort,person_nr,FOM,TOM,ytelseskonto,ref2,dato,kostnadssted,ansvarssted,ref3,rente1,rente2,ref4,fagsystem_ID"
    def ref1 = ""
    def vedtaksID = ""
    def belop_file = ""
    def dato_overfort = ""
    def person_nr = ""
    def FOM = ""
    def TOM = ""	
    def ytelseskonto = ""
    def ref2 = ""
    def dato = ""
    def kostnadssted = ""
    def ansvarssted = ""
    def ref3 = ""
    def rente1 = ""
    def rente2 = ""
    def ref4 = ""
    def fagsystem_ID = ""

    // loop through each line in the file contents - each line is split using defined substring characters below
    for(line in fileContents) {
        // check if it is a line we want to load or a header line we want to ignore
        if(line.substring(22,23) != " "){
            ref1 = line.substring(0,15)
            vedtaksID = line.substring(19,25)
            belop_file = line.substring(29,40)
            def aDate = new Date().parse("yyyyMMdd",line.substring(40,48))
            dato_overfort = aDate.format("dd-MMM-yyyy")
            personNr = line.substring(48,59)
            FOM = line.substring(59,67)
            TOM = line.substring(67,75)
            ytelseskonto = line.substring(75,80)
            ref2 = line.substring(83,97)
            def bDate = new Date().parse("yyyyMMdd",line.substring(101,109))
            dato = bDate.format("dd-MMM-yyyy")
            kostnadssted = line.substring(109,113)
            ansvarssted = line.substring(113,117)
            ref3 = line.substring(117,124)
            rente1 = line.substring(140,155)
            rente2 = line.substring(155,162)
            ref4 = line.substring(162,170)
            fagsystem_ID = line.substring(170,179)
            // create comma delimited line to be loaded
            newline = ref1 + "," + vedtaksID + "," + belop_file + "," + dato_overfort + "," + personNr + "," + FOM + "," + TOM + "," + ytelseskonto + "," + ref2 + "," + dato + "," + kostnadssted + "," + ansvarssted + "," + ref3 + "," + rente1 + "," + rente2 + "," + ref4 + "," + fagsystem_ID
            // add new line to the cummulating output string
            output = output + "\n" + newline
        }
    }
    outputFile.write(output)
}

//14. This block of code loads data from the Fagsystem Grensesnitt files.
else if(rule == "Grensesnitt_fag"){
    locationPath = "/data/ar/dev/inbox/grensesnitt"
    destLocation = "/data/ar/dev/inbox/grensesnitt/arkiv/"
    def encodingFag = "UTF-8"
    def matchingFiles = []
    filePath = ""
    fileName = ""
    new File(locationPath).eachFile() { 
        file -> if(file.getName().contains("tilbake-p")){
            filePath = file.getAbsolutePath()
            println "file path: " + filePath
            matchingFiles << file
        }
    }

    if (matchingFiles.isEmpty()) {
        println "No files matching the pattern found in the directory."
        return
    }
    // define header row for the file
    def output="avsender;vedtakId;fnr;vedtaksdato;fagsakYtelseType;tilbakekrevesBruttoUtenRenter;skatt;tilbakekrevesNettoUtenRenter;renter;erOmgjøringTilIngenTilbakekreving"

    matchingFiles.each { file ->
        def fileContents = file.newReader(encodingFag).readLines()
        println "File header: " + fileContents.get(1)
        fileContents = fileContents.drop(1)
        fileContents = filterNonEmptyLines(fileContents)
        
        for (line in fileContents) {

            def date_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
            def datestr = line.substring(date_pos_1 + 1, date_pos_2)
            def date = new Date().parse("yyyyMMdd", datestr)
            def newdate = date.format("dd-MMM-yyyy")
            line = line.replaceAll(datestr, newdate)
            output = output + '\n' + line
        }

        def dst = new File(destLocation + file.getName())
        copyFileWithEncoding(file, dst, encodingFag)
    }
    outputFile.write(output)
}

// Define a reusable method for copying files with a specified encoding
def copyFileWithEncoding(File src, File dst, String encoding) {
    src.withReader(encoding) { reader ->
        dst.withWriter(encoding) { writer ->
            writer << reader // Copy content with the specified encoding
        }
    }
    src.delete() // Optionally delete the source file after copying
}

// Method to filter out blank lines or lines with only whitespace
def filterNonEmptyLines(List<String> lines) {
    return lines.findAll { line -> !line.trim().isEmpty() }
}
