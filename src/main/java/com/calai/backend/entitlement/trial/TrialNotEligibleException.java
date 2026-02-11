package com.calai.backend.entitlement.trial;

public class TrialNotEligibleException extends RuntimeException {
    public TrialNotEligibleException(String message) { super(message); }
}
