package org.tools4j.stacked.index

import mu.KLogging
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SeZipFileParser(private val seFileInZipParserProvider: SeFileInZipParserProvider) {
    companion object: KLogging()

    fun parse(
        archiveFile: String,
        jobStatus: JobStatus = JobStatusImpl()
    ) {
        logger.debug{ "Parsing $archiveFile" }
        RandomAccessFile(archiveFile, "r").use { randomAccessFile ->
            try {
                SevenZip.openInArchive(
                    null, // autodetect archive type
                    RandomAccessFileInStream(randomAccessFile)
                ).use { archive ->
                    val archiveIndicesToParse = ArrayList<Int>()
                    for (i in 0..(archive.numberOfItems - 1)) {
                        val isFolder = archive.getProperty(i, PropID.IS_FOLDER) as Boolean
                        if (isFolder){
                            continue
                        }
                        val pathInArchive = archive.getProperty(i, PropID.PATH).toString()
                        if(!seFileInZipParserProvider.hasZipParserFor(pathInArchive)){
                            continue
                        }
                        archiveIndicesToParse.add(i)
                    }
                    val extractCallback =
                        ExtractCallback(
                            archiveFile,
                            archive,
                            seFileInZipParserProvider,
                            jobStatus)

                    var outerException: Exception? = null
                    try {
                        archive.extract(
                            archiveIndicesToParse.toIntArray(),
                            false, // Non-test mode
                            extractCallback
                        )
                    } catch (e: Exception) {
                        outerException = e
                    }
                    if (extractCallback.exceptionDuringParsing != null) {
                        throw extractCallback.exceptionDuringParsing!!
                    } else if (outerException != null) {
                        throw UnknownExtractorException(archiveFile, outerException)
                    }
                }
            } catch (e: ExtractorException){
                throw e
            } catch (e: Exception){
                throw UnknownExtractorException(archiveFile, e)
            }
        }
    }
}

class ExtractCallback(
    private val archiveFile: String,
    private val archive: IInArchive,
    private val seFileInZipParserProvider: SeFileInZipParserProvider,
    private val jobStatus: JobStatus = JobStatusImpl()

) : IArchiveExtractCallback {
    private var index = -1
    private var fileInZipParser: FileInZipParser? = null
    private var totalFileInZipSize: Long = 0;
    @Volatile var parsingFuture: Future<*>? = null
    @Volatile lateinit var pathInArchive: String
    @Volatile private var extractedFileInZipSize: Long = 0
    @Volatile var exceptionDuringParsing: Exception? = null
    companion object: KLogging()

    override fun getStream(
        index: Int,
        extractAskMode: ExtractAskMode
    ): ISequentialOutStream? {
        if(extractAskMode == ExtractAskMode.SKIP){
            return null
        }
        if(exceptionDuringParsing != null) throw exceptionDuringParsing!!
        this.index = index
        pathInArchive = archive.getProperty(index, PropID.PATH).toString()
        logger.debug{ "Extractor calling getStream() for: $pathInArchive" }
        totalFileInZipSize = archive.getProperty(index, PropID.SIZE).toString().toLong()
        fileInZipParser = seFileInZipParserProvider.getFileInZipParser(pathInArchive)
        jobStatus.addOperation("Parsing $pathInArchive from $archiveFile...")
        parsingFuture = Executors.newSingleThreadExecutor().submit {
            try {
                if(exceptionDuringParsing != null) throw exceptionDuringParsing!!
                fileInZipParser!!.start()

            } catch (e: FileInZipParserException) {
                e.printStackTrace()
                exceptionDuringParsing = ExtractorException(archiveFile, e)
                throw exceptionDuringParsing!!
            } catch (e: Exception) {
                e.printStackTrace()
                exceptionDuringParsing = UnknownExtractorException(archiveFile, e)
                throw exceptionDuringParsing!!
            }
        }

        extractedFileInZipSize = 0

        return ISequentialOutStream { data ->
            fileInZipParser!!.outputStreamToWriteTo.write(data)
            extractedFileInZipSize += data.size
            jobStatus.currentOperationProgress = toProgress(totalFileInZipSize, extractedFileInZipSize.toLong())
            data.size // Return amount of processed data
        }
    }

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        if (extractOperationResult != ExtractOperationResult.OK) {
            throw IllegalStateException("Extraction error")
        }
        if(index < 0 || fileInZipParser == null) return
        jobStatus.currentOperationProgress = "Completed extracting [${fileInZipParser!!.fileName}], scanning archive for further data files..."
        /*
        At this point we know that writing to the output stream has finished.
        Therefore we need to close the output stream.  This will cause a -1
        to be written to the output stream.  The reader (in the future) might
        not have finished reading from the inputStream.  So we need to wait
        for it to finish.  Hence the 'get'
         */
        fileInZipParser!!.flushAndClose()
        parsingFuture?.get()
    }

    override fun setCompleted(completeValue: Long) {}
    override fun prepareOperation(extractAskMode: ExtractAskMode?) {}
    override fun setTotal(total: Long) {}
}

class UnknownExtractorException(val archiveFile: String, cause: Throwable): Exception(cause){
    override val message: String?
        get() = "Error occurred whilst parsing file [$archiveFile] ${cause?.message}"
}

class ExtractorException(val archiveFile: String, val fileInZipParserException: FileInZipParserException): Exception(){
    override val message: String?
        get() = fileInZipParserException.message + " whilst parsing archive [$archiveFile]"
}

interface SeFileInZipParserProvider {
    fun getFileInZipParser(pathInArchive: String): FileInZipParser
    fun hasZipParserFor(pathInArchive: String): Boolean
}

class SeFileInZipParserProviderImpl (
    val rowHandlers: Map<String, XmlRowHandler<*>>) : SeFileInZipParserProvider {

    override fun hasZipParserFor(pathInArchive: String): Boolean {
         return rowHandlers.containsKey(pathInArchive)
    }

    override fun getFileInZipParser(pathInArchive: String): FileInZipParser {
        val xmlRowHandlerProvider = rowHandlers.getValue(pathInArchive)
        val pipedInputStream = PipedInputStream()
        val pipedOutputStream = PipedOutputStream(pipedInputStream)
        val xmlFileParser = XmlFileParser(pipedInputStream, xmlRowHandlerProvider )
        return FileInZipParser(pathInArchive, xmlFileParser, pipedOutputStream)
    }
}

class FileInZipParser(
    val fileName: String,
    val xmlFileParser: XmlFileParser,
    val outputStreamToWriteTo: OutputStream) {

    fun start() {
        try {
            xmlFileParser.parse()
        } catch(e: XmlFileParserException){
            throw FileInZipParserException(fileName, e)
        }
    }

    fun flushAndClose() {
        outputStreamToWriteTo.flush()
        outputStreamToWriteTo.close()
    }
}

class FileInZipParserException(
    val fileName: String,
    val xmlFileParserException: XmlFileParserException): Exception(xmlFileParserException){

    override val message: String?
        get() = xmlFileParserException.message + " in file [$fileName]"
}
