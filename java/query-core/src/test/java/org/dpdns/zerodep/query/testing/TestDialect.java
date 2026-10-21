package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.dialect.Dialect;

import java.util.regex.Pattern;

/** A neutral dialect for rendering tests: upper-case bare identifiers and a configurable IN limit. */
public final class TestDialect extends Dialect {
    private static final Pattern BARE = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private final int maxInList;

    public TestDialect() { this(1000); }

    public TestDialect(int maxInList) { this.maxInList = maxInList; }

    @Override public String name() { return "test"; }

    @Override protected boolean isBare(String identifier) { return BARE.matcher(identifier).matches(); }

    @Override public String foldCase(String identifier) { return identifier.toUpperCase(); }

    @Override public int maxInListSize() { return maxInList; }
}
