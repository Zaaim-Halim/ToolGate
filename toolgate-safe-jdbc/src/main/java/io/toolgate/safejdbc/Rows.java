package io.toolgate.safejdbc;

import io.toolgate.api.ArgValue;
import java.util.List;

/**
 * What a query returned, ready to hand back to the model: column labels and rows of values.
 * Numbers are numbers, booleans are booleans, SQL {@code NULL} is null, text is text, and dates
 * and times are ISO-8601 text.
 *
 * @param columns   the column labels, in order
 * @param rows      the rows, each with one value per column
 * @param truncated whether rows were left out or text was cut to fit the {@link Limits}
 */
public record Rows(List<String> columns, List<List<ArgValue>> rows, boolean truncated) {

    /**
     * Copies the lists and checks that every row has one value per column.
     *
     * @throws IllegalArgumentException if a row has the wrong number of values
     */
    public Rows {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::<ArgValue>copyOf).toList();
        for (List<ArgValue> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("a row has " + row.size() + " values for " + columns.size()
                        + " columns");
            }
        }
    }
}
