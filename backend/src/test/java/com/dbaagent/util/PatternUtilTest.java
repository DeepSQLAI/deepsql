package com.dbaagent.util;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternUtilTest {

    @Test
    void matchesTheSameInputsAsTheAnchoredFormItReplaced() {
        String[] regexes = {
            "\\b(slow query|slowest)\\b",
            "\\b(how many|count|number of)\\b.*\\bcolumns?\\b",
            "(?:sql|query example|write sql)"
        };
        String[] inputs = {
            "", "show me the slowest query", "how many columns are in orders",
            "unrelated text", "write sql for me"
        };

        for (String regex : regexes) {
            Pattern anchored = Pattern.compile(".*" + regex + ".*");
            for (String input : inputs) {
                assertEquals(anchored.matcher(input).matches(),
                    PatternUtil.containsPattern(input, regex),
                    "regex=" + regex + " input=" + input);
            }
        }
    }

    @Test
    void nullInputIsFalseRatherThanThrowing() {
        assertFalse(PatternUtil.containsPattern(null, "\\bx\\b"));
    }

    @Test
    void findsAKeywordAfterANewline() {
        // The anchored ".*X.*" form missed this: `.` does not cross a newline,
        // so a multi-line chat message never matched. find() is the fix.
        assertTrue(PatternUtil.containsPattern("first line\nslow query here",
            "\\b(slow query|slowest)\\b"));
    }

    @Test
    void reusesTheCompiledPatternForTheSameRegex() {
        String regex = "\\bcache-me\\b";
        assertTrue(PatternUtil.containsPattern("cache-me please", regex));
        assertTrue(PatternUtil.containsPattern("cache-me again", regex));
        assertFalse(PatternUtil.containsPattern("nothing here", regex));
    }
}
