package com.priolab.model;

/**
 * One item to prioritize, as emitted by a connector after the {@code GET}
 * command: a JSON object with {@code id}, {@code description} and {@code url}.
 *
 * @param id          short identifier shown as a clickable link that opens {@link #url}
 * @param description human-readable summary of the item
 * @param url         the link opened when the id is clicked
 */
public record PrioItem(String id, String description, String url) {
}
