package com.caloshape.backend.auth.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailLoginCodeMailListener {

    private final EmailLoginCodeMailSender mailSender;

    @Async("emailOtpExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLoginCodeRequested(EmailLoginCodeRequestedEvent event) {
        try {
            mailSender.sendLoginCode(event.toEmail(), event.code(), event.ttlMinutes());
        } catch (Exception ex) {
            log.warn(
                    "email_login_code_send_failed to={} error={}",
                    maskEmail(event.toEmail()),
                    ex.toString(),
                    ex
            );
        }
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
