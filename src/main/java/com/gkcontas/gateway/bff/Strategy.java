package com.gkcontas.gateway.bff;

/** How the independent calls of the dashboard are made. */
public enum Strategy {
    /** All at once: the response waits for the slowest call. */
    PARALLEL,
    /** One after the other: the response waits for the sum of them. */
    SEQUENTIAL
}
