import groovy.json.* 
import groovy.xml.*
import java.util.zip.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.text.ParseException

// Turn off SQL Processing by Agent 
agentAPI.skipAction('true')

// Get bind variables - Rule variable determines which block of code runs. Variables are defined in the 'query' menu in the application and registered application parameters
bindVariables = agentAPI.getBindVariables()
rule = bindVariables["Rule"]
def outFileName = agentContext["DATAFILENAME"]
outputFile = new File(outFileName)

// 1. this block of code is for the bank file. The if logic around each code block drives which files is transformed and loaded.
if(rule == "BANK_LOAD") {
    locationPath = "/data/ar/dev/inbox/bank/"
    filePath = ""
    fileName = ""
    def folder = new File(locationPath)
    if (!folder.exists()) {
        println "Directory not found: " + locationPath
        return // Exit script if directory does not exist
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
        return // Exit the script if no file is found
    }

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/bank/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

    // get number of lines present in the file contents
    numLines = fileContents.size()
    print numLines
    
    // Loop through each line of the bank file and check if the line starts with 01, 04, 05 (two lines) or 02 (three lines) transaction codes
    String edit
    for(int i = 0;i<numLines;i++){
        String code = fileContents.get(i).substring(0,8)
        if(code == "940SWI01" || code == "940SWI04" || code == "940SWI05"){
            edit = edit + "\n" + fileContents.get(i) + fileContents.get(i+1)
        }
        else if(code == "940SWI02"){
            edit = edit + "\n" + fileContents.get(i) + fileContents.get(i+1) + fileContents.get(i+2)
        }
    }
    
    edit1 = edit.split('\n').drop(1).join('\n')
    def myFile = new File('/data/ar/dev/data/mySuperFile.txt')
    myFile.write(edit1)
    editContents = myFile.readLines()
    numLinesEdit = editContents.size()
    
    // Need to loop through rows grabbing relevant information for each transaction 02 line and write to output file
    String finalData
    def code = ''
    def line = ''
    def VALKODE = ''
    def ACCOUNTNUMBER = ''
    for(int i = 0;i<numLinesEdit;i++){
        line = editContents.get(i)
        code = line.substring(0,8)
        
        if(code == "940SWI01"){  
            VALKODE = line.substring(116,119)
            ACCOUNTNUMBER = line.substring(41,52)}
        if(code == "940SWI02"){
            def KUNDENUMMER = ''
            def BDATE = ''
            def BELOP = ''
            def AMOUNT = ''
            def content1 = ''
            def content2 = ''
            def content3 = ''
            def content4 = ''
            def content5 = ''
            KUNDENUMMER = line.substring(10,20)
            def bankDate = new Date().parse("yyyyMMdd",line.substring(190,198))
            BDATE = bankDate.format("dd-MMM-yy")
            BELOP = line.substring(103,118)
            AMOUNT = BELOP[0..-3] + '.' + BELOP[-2..-1] 
            
            // this piece of code ensures that only the relevant 04 lines for the specific 02 line are includes in the output file contents to prevent duplication
            if(editContents.get(i+1).substring(0,8) == "940SWI04"){
                content1 = editContents.get(i+1).substring(90,139)
            }
            if(i<numLinesEdit-2 && editContents.get(i+2).substring(0,8) == "940SWI04" && editContents.get(i+1).substring(0,8) != "940SWI02" ){
                content2 = editContents.get(i+2).substring(90,139)
            }
            if(i<numLinesEdit-3 && editContents.get(i+3).substring(0,8) == "940SWI04" && editContents.get(i+2).substring(0,8) != "940SWI02"){
                content3 = editContents.get(i+3).substring(90,139)
            }
            if(i<numLinesEdit-4 && editContents.get(i+4).substring(0,8) == "940SWI04" && editContents.get(i+3).substring(0,8) != "940SWI02" && editContents.get(i+2).substring(0,8) != "940SWI02"){
                content4 = editContents.get(i+4).substring(90,139)
            }
            if(i<numLinesEdit-5 && editContents.get(i+5).substring(0,8) == "940SWI04" && editContents.get(i+4).substring(0,8) != "940SWI02" && editContents.get(i+3).substring(0,8) != "940SWI02" && editContents.get(i+2).substring(0,8) != "940SWI02"){
                content5 = editContents.get(i+5).substring(90,139)
            }
            finalData = finalData + "\n" + KUNDENUMMER + ',' + ACCOUNTNUMBER + ',' + VALKODE + ',' + BDATE + ',' + BELOP + ',' + AMOUNT + ',' + content1 + ',' + content2 + ',' + content3 + ',' + content4 + ',' + content5
            
        }
    }

    def myFile1 = new File('/data/ar/dev/data/ARCSInput.txt')
    myFile1.write(finalData.split('\n').drop(1).join('\n'))

    String arcsData
    String outputData = 'KUNDENUMMER,ACCOUNTNUMBER,VALKODE,BDATE,BELOP_ORE,BELOP,content1,content2,content3,content4,content5'
    arcsData = myFile1.text.split('\n').each{row ->
    // def newRow
    a = ''
        row.split(',').each{e ->
            if(a==''){
                a = e.trim()
            }
            else {
                a = a + ',' + e.trim()
            }
        }
            outputData = outputData + '\n' + a
        
    }
    def myFile2 = new File('/data/ar/dev/data/ARCSInput2.txt')
    myFile2.write(outputData)
    
    myFile.delete()
    myFile1.delete()
    myFile2.delete()
    outputFile.write(outputData)
    
}

//2. this block of code is for the OEBS bank file
else if(rule == "OEBS_BANK_LOAD"){
    locationPath = "/data/ar/dev/inbox/OracleGL"
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/OracleGL/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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
            // Handle the error appropriately (e.g., set date to null or log it)
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()    
    
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()    
    
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/refusjon/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()
    
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/daglig/arena/tilsagn/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()    
    // define header row for the file
    output="Referanse;Dato;Belob1;Belob2;Valuta;Organisasjon;Bedriftsnummer"
    output = output +'\n' + fileContents
    outputFile.write(output)
}

// 12. This block of code loads data from the Manedlig_OEBS file
else if(rule == "Tiltak_Manedlig_OEBS"){
    locationPath = "/data/ar/dev/inbox/tiltak/maanedlig"
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/tiltak/maanedlig/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()
    
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

    // read in lines from file
    def fileContents = new File(filePath).newReader("windows-1252").readLines()

    // move orginal file to archive location
    destLocation = "/data/ar/dev/inbox/grensesnitt/arkiv/" // update with NAV folder location path for archived files

    def src = new File(filePath)
    def dst = new File(destLocation + fileName)

    // copy src to dst
    dst.text = '' // set file contents to empty incase file already exists from previous data load.
    dst << src.text
    // delete file in src
    src.delete()

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
    // write final output to file to be loaded
    outputFile.write(output)
}

//14. This block of code loads data from the Fagsystem Grensesnitt file.
else if(rule == "Grensesnitt_fag"){
    locationPath = "/data/ar/dev/inbox/grensesnitt"
    destLocation = "/data/ar/dev/inbox/grensesnitt/arkiv/"
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
    def output="avsender;vedtakId;fnr;vedtaksdato;fagsakYtelseType;tilbakekrevesBruttoUtenRenter;skatt;tilbakekrevesNettoUtenRenter;renter;erOmgjÃ¸ringTilIngenTilbakekreving"

    matchingFiles.each { file ->
        def fileContents = file.newReader("windows-1252").readLines()
        println "File header: " + fileContents.get(1)
        fileContents = fileContents.drop(1)

        for (line in fileContents) {
            def date_pos_1 = line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1)
            def date_pos_2 = line.indexOf(";", line.indexOf(";", line.indexOf(";", line.indexOf(";") + 1) + 1) + 1)
            def datestr = line.substring(date_pos_1 + 1, date_pos_2)
            def date = new Date().parse("yyyyMMdd", datestr)
            def newdate = date.format("dd-MMM-yyyy")
            line = line.replaceAll(datestr, newdate)
            output = output + '\n' + line
        }

        // Move the original file to the archive location
        def dst = new File(destLocation + file.getName())

        // Copy file content for archiving
        dst << file.text

        // Delete the original file after archiving
        file.delete()
    }
    outputFile.write(output)
}