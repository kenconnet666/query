package org.dpdns.zerodep.query.meta;

import java.io.Serializable;

/**
 * A getter or record accessor reference such as {@code Lips::getMaterial} or {@code Material::partNumber}.
 * Serializable so the referenced method's name can be recovered; lambdas other than method references are rejected.
 */
@FunctionalInterface
public interface Getter<E, T> extends Serializable {
    T get(E row);
}
