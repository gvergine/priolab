package com.priolab.model;

/**
 * The four WSJF scoring inputs persisted for one item, keyed by the item's
 * {@code id} in the project's {@code item_scores} table.
 *
 * <p>Each value is {@code null} when the user has not picked one (the blank
 * choice in the dropdowns). A score with all four values {@code null} carries no
 * information and is not stored — see {@link #isEmpty()}.
 */
public record ItemScore(Integer businessValue, Integer timeCriticality,
                        Integer riskReduction, Integer jobSize) {

    /** True when nothing has been scored, i.e. there is nothing worth storing. */
    public boolean isEmpty() {
        return businessValue == null && timeCriticality == null
                && riskReduction == null && jobSize == null;
    }
}
