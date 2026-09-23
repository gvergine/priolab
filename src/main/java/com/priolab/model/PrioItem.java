package com.priolab.model;

/**
 * One item to prioritize, exactly as a connector delivered it.
 *
 * <p>A connector's load prints a JSON <em>array</em> of these objects, and a
 * save hands the same shape back on its stdin:
 *
 * <pre>{@code
 * {"id": "Apple", "description": "Test task 1", "url": "http://…/Apple",
 *  "UBV": "1", "TC": "2", "RROE": "3", "JS": "5"}
 * }</pre>
 *
 * The four score keys are <b>mandatory</b> and carry either {@code null} or a
 * string holding the number; PrioLab parses them into the nullable fields
 * below and writes them back out as strings.
 *
 * @param id          short identifier shown as a clickable link that opens {@link #url}
 * @param description human-readable summary of the item
 * @param url         the link opened when the id is clicked
 * @param ubv         user/business value, {@code null} when unscored
 * @param tc          time criticality, {@code null} when unscored
 * @param rroe        risk reduction / opportunity enablement, {@code null} when unscored
 * @param js          job size, {@code null} when unscored
 */
public record PrioItem(String id, String description, String url,
                       Integer ubv, Integer tc, Integer rroe, Integer js) {

    /** The JSON key of each score, in the order the tables show them. */
    public static final String KEY_UBV = "UBV";
    public static final String KEY_TC = "TC";
    public static final String KEY_RROE = "RROE";
    public static final String KEY_JS = "JS";
}
