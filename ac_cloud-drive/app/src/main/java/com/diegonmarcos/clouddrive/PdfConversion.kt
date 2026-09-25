package com.diegonmarcos.clouddrive

import android.content.Context
import android.net.Uri
import com.diegonmarcos.clouddrive.files.FileOps
import java.io.File
import java.io.IOException

/**
 * PDF → txt / md / html / csv, written NEXT TO the source (#458, on the #577 pdfium engine).
 * The ONE conversion path: the reader's Convert menu and the Files tab's row action both call
 * this, so there is no second, looser writer. The text comes from the same engine the reader
 * draws with ([PdfEngine.pageText]); the file goes out through [FileOps.writeText], the atomic
 * save path. A scan (no text layer) is refused loudly instead of producing an empty file that
 * looks like success. docx / xlsx / odt are not offered — layout reconstruction needs the fleet
 * converter service. Blocking; callers run it off the UI thread.
 */
object PdfConversion {

    const val PDF_MIME = "application/pdf"

    /** The written file, or the reason (an IOException whose message is what the user reads). */
    fun convertPdf(ctx: Context, source: File, target: String): Result<File> {
        if (target !in PdfConvert.TARGETS) return Result.failure(IOException("unknown conversion target: $target"))
        if (!source.isFile) return Result.failure(IOException("not a file: ${source.name}"))
        val engine = try {
            PdfEngine.open(ctx, Uri.fromFile(source), null)
        } catch (locked: PdfEngine.PasswordRequired) {
            return Result.failure(IOException(source.name + " is password protected — open it in the reader and enter the password first"))
        } catch (error: Throwable) {
            return Result.failure(IOException("cannot convert this PDF: " + (error.message ?: error.javaClass.simpleName)))
        }
        return try {
            val pages = (0 until engine.pageCount).map { PdfConvert.cleanPage(engine.pageText(it)) }
            if (PdfConvert.hasNoText(pages)) return Result.failure(IOException(PdfConvert.NO_TEXT_LAYER_MESSAGE))
            val directory = source.parentFile ?: return Result.failure(IOException("no folder to save next to"))
            val name = PdfConvert.freeName(directory.list()?.toSet() ?: emptySet(), PdfConvert.preferredName(source.name, target))
            val out = File(directory, name)
            FileOps.writeText(File(directory, name), PdfConvert.build(pages, target, source.name))
            Result.success(out)
        } catch (error: Exception) {
            Result.failure(IOException("cannot convert this PDF: " + (error.message ?: error.javaClass.simpleName)))
        } finally {
            engine.close()
        }
    }
}
