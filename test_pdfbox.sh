#!/bin/bash

# Test PDFBox extraction using a simple Java program

echo "Creating PDFBox test program..."

cat > /tmp/TestPDFBox.java << 'EOF'
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.File;

public class TestPDFBox {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java TestPDFBox <pdf-file>");
            return;
        }
        
        File pdfFile = new File(args[0]);
        if (!pdfFile.exists()) {
            System.out.println("File not found: " + args[0]);
            return;
        }
        
        System.out.println("Testing PDFBox extraction on: " + pdfFile.getName());
        System.out.println("=".repeat(60));
        
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            
            System.out.println("Pages: " + document.getNumberOfPages());
            System.out.println("Text length: " + text.length() + " characters");
            System.out.println();
            
            // Count questions
            int questions = text.split("(?:^|\\n|\\s)\\d{1,3}\\s?[\\.\\)\\-]").length - 1;
            System.out.println("Approximate questions: " + questions);
            
            // Count answer markers
            int inlineAnswers = text.split("(?i)Respuesta\\s+Correcta:").length - 1;
            System.out.println("Inline answers: " + inlineAnswers);
            
            System.out.println();
            System.out.println("First 500 characters:");
            System.out.println("-".repeat(60));
            System.out.println(text.substring(0, Math.min(500, text.length())));
            
            System.out.println();
            System.out.println("Last 500 characters:");
            System.out.println("-".repeat(60));
            System.out.println(text.substring(Math.max(0, text.length() - 500)));
            
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
EOF

echo "Compiling with PDFBox from Gradle cache..."
PDFBOX_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.apache.pdfbox -name "pdfbox-*.jar" | grep -v "android" | head -1)

if [ -z "$PDFBOX_JAR" ]; then
    echo "PDFBox not found in Gradle cache. The app uses pdfbox-android which requires Android runtime."
    echo "Let me check what the app actually extracts..."
    exit 1
fi

echo "Using: $PDFBOX_JAR"
javac -cp "$PDFBOX_JAR" /tmp/TestPDFBox.java 2>&1

if [ $? -eq 0 ]; then
    echo ""
    echo "Testing Andalucía PDF..."
    echo ""
    cd "/home/estebanmate/Develop/Projects/KittyKiller/app/sampledata"
    java -cp "/tmp:$PDFBOX_JAR" TestPDFBox "EXAMEN"*"ANDALUCIA"*.pdf
else
    echo "Compilation failed. PDFBox version mismatch."
fi
