package com.priolab.model;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;

/**
 * A {@link PrioItem} decorated with the editable WSJF scoring inputs and the
 * derived WSJF value.
 *
 * <p>The four inputs — Business Value, Time Criticality, Risk Reduction and Job
 * Size — are {@code null} until the user picks a number ({@code null} = the
 * "Undefined" choice). {@link #wsjfProperty() WSJF} is
 * {@code (BusinessValue + TimeCriticality + RiskReduction) / JobSize} and is
 * {@code null} (shown blank) while any input is undefined. It recomputes
 * automatically as the inputs change, so tables bound to it re-render and the
 * result list can re-sort.
 */
public class ScoredItem {

    private final PrioItem item;

    private final ObjectProperty<Integer> businessValue = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Integer> timeCriticality = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Integer> riskReduction = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Integer> jobSize = new SimpleObjectProperty<>(null);

    private final ObservableValue<Double> wsjf;

    public ScoredItem(PrioItem item) {
        this.item = item;
        this.wsjf = Bindings.createObjectBinding(() -> {
            Integer bv = businessValue.get();
            Integer tc = timeCriticality.get();
            Integer rr = riskReduction.get();
            Integer js = jobSize.get();
            if (bv == null || tc == null || rr == null || js == null || js == 0) {
                return null;
            }
            return (bv + tc + rr) / (double) js;
        }, businessValue, timeCriticality, riskReduction, jobSize);
    }

    public PrioItem item() {
        return item;
    }

    public ObjectProperty<Integer> businessValueProperty() {
        return businessValue;
    }

    public ObjectProperty<Integer> timeCriticalityProperty() {
        return timeCriticality;
    }

    public ObjectProperty<Integer> riskReductionProperty() {
        return riskReduction;
    }

    public ObjectProperty<Integer> jobSizeProperty() {
        return jobSize;
    }

    public ObservableValue<Double> wsjfProperty() {
        return wsjf;
    }

    public Double getWsjf() {
        return wsjf.getValue();
    }
}
