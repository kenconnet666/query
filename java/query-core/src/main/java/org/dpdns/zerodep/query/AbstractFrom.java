package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;

/** What every lambda scope (From1 ... From8) shares. Immutable: every call returns a new scope. */
public abstract class AbstractFrom<S extends AbstractFrom<S>> {
    final SelectSpec spec;

    AbstractFrom(SelectSpec spec) { this.spec = spec; }

    abstract S rebuild(SelectSpec spec);

    public final S distinct() { return rebuild(spec.distinct()); }

    public final S limit(int rows) { return rebuild(spec.limit(rows)); }

    public final S offset(int rows) { return rebuild(spec.offset(rows)); }

    /** Skips the database instance's global filters for this SELECT. */
    public final S unfiltered() { return rebuild(spec.unfiltered()); }

    /** Adds a dialect clause, e.g. {@code Hana.hint("NO_CS_JOIN")}. */
    public final S with(Clause clause) { return rebuild(spec.clause(clause)); }

    /** SELECT 1, for EXISTS subqueries. */
    public final Query<Integer> selectOne() { return Query.value(spec, Sql.lit(1)); }

    static <E> Ref<E> ref(Class<E> entity) { return new Ref<>(new TableSource<>(entity)); }

    static <E> Ref<E> ref(Source<E> source) { return new Ref<>(source); }
}
