package com.saathi.catalog.domain;

import java.util.Locale;

/** §7.1's stock units. A product's label and its count unit must agree at write time. */
public enum StockUnit {
    pc,
    kg,
    g,
    L,
    ml;

    /**
     * §7.1's "a mismatch between {@code unit_label} and {@code stock_unit} is a validation error at
     * product write, not a runtime surprise". The label is what the customer reads ("500 ml",
     * "1 kg", "6 pc"), so the count unit has to be a unit that label actually names.
     */
    public boolean describes(String unitLabel) {
        if (unitLabel == null) {
            return false;
        }
        String label = unitLabel.toLowerCase(Locale.ROOT).replace(" ", "");
        return switch (this) {
            case pc -> label.contains("pc") || label.contains("piece") || label.contains("pack")
                || label.contains("dozen") || label.contains("unit") || label.contains("nos");
            case kg -> label.contains("kg");
            case g -> !label.contains("kg") && label.matches(".*\\d+g.*");
            case L -> !label.contains("ml")
                && (label.matches(".*\\d+l.*") || label.contains("litre") || label.contains("liter"));
            case ml -> label.contains("ml");
        };
    }
}
