package org.dpdns.zerodep.query;

import java.util.List;

/** One page of rows and the total row count. Pages are numbered from 1. */
public record Page<R>(List<R> rows, long total, int page, int size) {
    public Page {
        rows = List.copyOf(rows);
    }

    public long totalPages() { return size == 0 ? 0 : (total + size - 1) / size; }
}
