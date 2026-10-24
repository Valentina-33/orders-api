package edu.escuelaing.orders.application;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Generates ids like ORD-004, ORD-005, ... Unique only inside one execution environment. */
public class SequentialOrderIdGenerator implements Supplier<String> {

    private final AtomicInteger next;

    public SequentialOrderIdGenerator(int firstNumber) {
        this.next = new AtomicInteger(firstNumber);
    }

    @Override
    public String get() {
        return String.format("ORD-%03d", next.getAndIncrement());
    }
}
