package org.dpdns.zerodep.query.dialect;

import java.util.List;

/**
 * A function's identity. Rendering is looked up in order: the database instance's overrides, the
 * dialect's registrations, then this definition's standard rendering. A definition owned by one
 * dialect refuses to render on another instead of guessing an equivalent.
 */
public final class FunctionDef {
    private final String name;
    private final FunctionRenderer standard;
    private final Class<? extends Dialect> owner;

    private FunctionDef(String name, FunctionRenderer standard, Class<? extends Dialect> owner) {
        this.name = name;
        this.standard = standard;
        this.owner = owner;
    }

    /** A function written NAME(arg, ...) on every database. */
    public static FunctionDef of(String name) { return new FunctionDef(name, (w, args) -> call(w, name, args), null); }

    public static FunctionDef of(String name, FunctionRenderer standard) { return new FunctionDef(name, standard, null); }

    /** A function that exists only on one dialect (or its subclasses). */
    public static FunctionDef only(Class<? extends Dialect> owner, String name) {
        return new FunctionDef(name, (w, args) -> call(w, name, args), owner);
    }

    public static FunctionDef only(Class<? extends Dialect> owner, String name, FunctionRenderer standard) {
        return new FunctionDef(name, standard, owner);
    }

    public String name() { return name; }

    public void render(SqlWriter w, List<? extends Renderable> args) {
        FunctionRenderer r = w.functionOverride(this);
        if (r == null) r = w.dialect().renderer(this);
        if (r == null) {
            if (owner != null && !owner.isInstance(w.dialect())) {
                throw new UnsupportedOperationException(name + " is a " + owner.getSimpleName() + " function; this database uses "
                    + w.dialect().name() + ". Use that dialect's equivalent, or register a renderer for it.");
            }
            r = standard;
        }
        r.render(w, args);
    }

    /** Writes NAME(arg1, arg2, ...). */
    public static void call(SqlWriter w, String name, List<? extends Renderable> args) {
        w.sql(name).sql("(").join(args, ", ").sql(")");
    }

    @Override public String toString() { return name; }
}
