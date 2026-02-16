package com.calai.backend.foodlog.web;

public class SubscriptionRequiredException extends RuntimeException {
    private final String clientAction;

    public SubscriptionRequiredException(String message, String clientAction) {
        super(message);
        this.clientAction = clientAction;
    }

    // ✅ 新增：單參數建構子（預設 clientAction）
    public SubscriptionRequiredException(String message) {
        this(message, "SHOW_PAYWALL");//付費牆
    }

    public String clientAction() { return clientAction; }
}
