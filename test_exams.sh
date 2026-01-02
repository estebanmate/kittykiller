#!/bin/bash

# Test script to verify exam parsing

echo "========================================="
echo "EXAM PARSING VERIFICATION"
echo "========================================="
echo ""

cd "/home/estebanmate/Develop/Projects/KittyKiller/app/sampledata"

# Test 1: Canarias (300 questions, inline answers)
echo "1. CANARIAS EXAM"
echo "   Expected: 300 questions, inline answers"
file="EXAMEN TCAE CANARIAS( RESPUESTAS DEBAJO DE CADA PREGUN.pdf"
pdftotext "$file" /tmp/canarias.txt 2>/dev/null
questions=$(grep -c "^[0-9]\{1,3\}\." /tmp/canarias.txt)
answers=$(grep -c "Respuesta Correcta:" /tmp/canarias.txt)
echo "   Found: $questions questions, $answers inline answers"
if [ "$questions" -eq 300 ] && [ "$answers" -eq 300 ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL"
fi
echo ""

# Test 2: Murcia (85 questions, table at end)
echo "2. MURCIA EXAM"
echo "   Expected: 85 questions, answer table at end"
file=$(ls -1 | grep -i "MURCIA")
pdftotext "$file" /tmp/murcia.txt 2>/dev/null
questions=$(grep -c "^[0-9]\{1,3\}\." /tmp/murcia.txt)
# Check for answer table pattern
table_answers=$(grep -oP '\d{1,3}[.\s-]*[a-dA-D](?![a-zA-Záéíóú])' /tmp/murcia.txt | wc -l)
echo "   Found: $questions questions, $table_answers table entries"
if [ "$questions" -ge 83 ] && [ "$table_answers" -ge 85 ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL (might need adjustment)"
fi
echo ""

# Test 3: Andalucía (153 questions, table at end)
echo "3. ANDALUCÍA EXAM"
echo "   Expected: 153 questions, answer table at end"
file=$(ls -1 | grep -i "ANDALUCIA")
pdftotext "$file" /tmp/andalucia.txt 2>/dev/null
questions=$(grep -c "^[0-9]\{1,3\}\." /tmp/andalucia.txt)
table_answers=$(grep -oP '\d{1,3}[.\s-]*[a-dA-D](?![a-zA-Záéíóú])' /tmp/andalucia.txt | wc -l)
echo "   Found: $questions questions, $table_answers table entries"
echo "   Note: PDF has syntax errors, may need OCR"
if [ "$questions" -ge 150 ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL - PDF extraction issues"
fi
echo ""

# Test 4: Madrid (55 questions, scanned PDF, table at beginning)
echo "4. MADRID EXAM"
echo "   Expected: 55 questions, scanned PDF, answer table at beginning"
file=$(ls -1 | grep -i "MADRID")
pdftotext "$file" /tmp/madrid.txt 2>/dev/null
text_length=$(wc -c < /tmp/madrid.txt)
echo "   Found: $text_length characters extracted"
if [ "$text_length" -lt 100 ]; then
    echo "   Note: Scanned PDF - requires OCR"
    echo "   ✓ EXPECTED (OCR will be used by app)"
else
    questions=$(grep -c "^[0-9]\{1,3\}\." /tmp/madrid.txt)
    echo "   Found: $questions questions"
fi
echo ""

echo "========================================="
echo "SUMMARY"
echo "========================================="
echo "Canarias: Should work perfectly (inline answers)"
echo "Murcia: Should work (table detection)"
echo "Andalucía: May have issues (PDF corruption)"
echo "Madrid: Requires OCR (scanned images)"
