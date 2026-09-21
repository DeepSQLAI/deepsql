package com.dbaagent.util;

import java.util.regex.Pattern;

/**
 * Quoting and validation for table and column names interpolated into SQL.
 *
 * <p>Identifiers cannot be bind parameters, so every dialect's answer is to quote them — and
 * quoting is only protection if an embedded quote is <em>doubled</em>. Wrapping without
 * doubling is exactly as safe as {@code "'" + value + "'"} is for a string literal, which is
 * to say not at all.
 *
 * <p>Two services had written their own quoter and both omitted the doubling, while the three
 * provider classes next to them did it correctly. Verified against a real PostgreSQL rather
 * than inferred: a {@code tableName} path variable of
 * {@code victim" AS t; DROP TABLE zz_inj.probe; SELECT 1 FROM zz_inj."victim} reaching
 * {@code CardinalityEstimationService} produced
 *
 * <pre>SELECT COUNT(*) FROM zz_inj."victim" AS t; DROP TABLE zz_inj.probe; SELECT 1 FROM zz_inj."victim"</pre>
 *
 * which executed with no errors at all — the count returned, the table was dropped, and the
 * trailing select returned its rows. That payload contains no {@code /}, so Spring's
 * {@code StrictHttpFirewall} does not block it.
 *
 * <p>This lives in one place on purpose. A security primitive copied into each caller is a
 * primitive that gets fixed in one copy and missed in the others — the drift the SQL guard is
 * kept mirrored to avoid, and the reason the two renderers in Agent chat now share one escape.
 */
public final class SqlIdentifier {

    /**
     * What a real table or column name looks like: letters, digits, underscore, dollar, and a
     * dot for a schema-qualified name. Deliberately permissive enough for the schemas this
     * product actually meets — a validator that rejected {@code v_daily_revenue} or
     * {@code order_items_2026} would be deleted by the next person to hit it.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]+");

    private SqlIdentifier() {
    }

    /**
     * Quotes an identifier for the dialect, doubling any embedded quote character.
     *
     * <p>An unknown or null dialect gets ANSI double quotes. Defaulting to MySQL backticks
     * would be the riskier guess: a double quote arriving in a backtick-quoted identifier is
     * inert, while a backtick arriving in a double-quoted one is inert too — but ANSI is what
     * every non-MySQL dialect here uses, so it is the correct default rather than merely the
     * safe one.
     */
    public static String quote(String identifier, String dbType) {
        String quote = isMysql(dbType) ? "`" : "\"";
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /**
     * Returns the identifier if it could name a real table or column, and throws otherwise.
     *
     * <p>{@link #quote} already makes injection impossible; this is the second layer. Escaping
     * turns a hostile name into a harmless one, but it still lets a caller address an object
     * the feature never meant to touch, and it leaves a confusing error when the "table" was
     * never a table. Refusing early says so plainly.
     */
    public static String requireSafe(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Identifier is required");
        }
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                "Not a valid table or column name: " + identifier);
        }
        return identifier;
    }

    private static boolean isMysql(String dbType) {
        return dbType != null && dbType.toLowerCase().contains("mysql");
    }
}
