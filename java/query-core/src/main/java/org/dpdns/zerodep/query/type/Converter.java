package org.dpdns.zerodep.query.type;

/**
 * Converts between the Java type J seen by application code and the database value D read and
 * written through JDBC. Implementations need a public no-argument constructor when referenced
 * from annotations; one instance per class is created and shared.
 */
public interface Converter<J, D> {
    D toDatabase(J value);

    J fromDatabase(D value);
}
