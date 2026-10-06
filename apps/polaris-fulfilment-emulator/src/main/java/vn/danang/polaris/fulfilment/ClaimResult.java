package vn.danang.polaris.fulfilment;

/** Outcome of one claim call. {@code FAILED} covers every outcome other than 200 and 409 (no retry, D4). */
public enum ClaimResult {
    WON, LOST, FAILED
}
