#!/usr/bin/env kotlin

@file:DependsOn("com.tom-roush:pdfbox-android:2.0.27.0")

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: test_pdfbox.kts <pdf-file>")
        return
    }
    
    val pdfFile = File(args[0])
    if (!pdfFile.exists()) {
        println("File not found: ${args[0]}")
        return
    }
    
    println("Testing PDFBox extraction on: ${pdfFile.name}")
    println("=" .repeat(60))
    
    try {
        val document = PDDocument.load(pdfFile)
        val stripper = PDFTextStripper()
        val text = stripper.getText(document)
        
        println("Pages: ${document.numberOfPages}")
        println("Text length: ${text.length} characters")
        println()
        
        // Count questions
        val questionPattern = Regex("(?:^|\\n|\\s)(\\d{1,3})\\s?[\\.\\)\\-]")
        val questions = questionPattern.findAll(text).count()
        println("Questions detected: $questions")
        
        // Count answer markers
        val answerPattern = Regex("Respuesta\\s+Correcta:", RegexOption.IGNORE_CASE)
        val inlineAnswers = answerPattern.findAll(text).count()
        println("Inline answers: $inlineAnswers")
        
        // Check for answer table
        val tablePattern = Regex("(\\d{1,3})[\\s\\.\\-]*([a-dA-D])(?![a-zA-Záéíóú])")
        val tableMatches = tablePattern.findAll(text).count()
        println("Potential table entries: $tableMatches")
        
        println()
        println("First 500 characters:")
        println("-" .repeat(60))
        println(text.take(500))
        
        document.close()
        
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    }
}

main(args)
