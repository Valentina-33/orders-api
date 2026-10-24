package edu.escuelaing.orders.application;

/** Thrown when the input of an operation breaks a business rule (missing customer, quantity &lt; 1, ...). */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
