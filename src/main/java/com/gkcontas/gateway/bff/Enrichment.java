package com.gkcontas.gateway.bff;

/** How order lines get their product names from the catalog. */
public enum Enrichment {
    /** Distinct ids, fetched in batches: one call per batch. */
    BATCH,
    /** One catalog call per order line - the N+1 of an aggregator. */
    PER_ITEM
}
