#!/bin/bash
# Count questions that have proper structure
text_file="/tmp/canarias_full.txt"

# Extract all question numbers that start with pattern "N. "
echo "Searching for question patterns..."
grep -oP '^\d{1,3}\.' "$text_file" | sed 's/\.//' | sort -n | tail -20
