package com.calai.backend.accountdelete.controller;

import com.calai.backend.accountdelete.dto.AccountDeletionPreviewResponse;
import com.calai.backend.accountdelete.dto.AccountDeletionSubmitRequest;
import com.calai.backend.accountdelete.dto.AccountDeletionSubmitResponse;
import com.calai.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.calai.backend.accountdelete.service.AccountDeletionService;
import com.calai.backend.auth.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/account")
public class AccountDeletionController {

    private final AuthContext auth;
    private final AccountDeletionService service;
    private final AccountDeletionRequestRepository reqRepo;

    @GetMapping("/deletion-preview")
    public AccountDeletionPreviewResponse preview() {
        Long uid = auth.requireUserId();
        return service.getDeletionPreview(uid);
    }

    @PostMapping("/deletion-request")
    public AccountDeletionSubmitResponse request(
            @RequestBody(required = false) AccountDeletionSubmitRequest body
    ) {
        Long uid = auth.requireUserId();
        AccountDeletionSubmitRequest safeBody = body == null
                ? new AccountDeletionSubmitRequest(false, false)
                : body;

        var req = service.requestDeletion(
                uid,
                safeBody.isSubscriptionWarningAcknowledged(),
                safeBody.isUserRequestedGooglePlayCancel()
        );

        return new AccountDeletionSubmitResponse(true, req.getReqStatus(), req.getRequestedAtUtc());
    }

    @GetMapping("/deletion-request")
    public Map<String, Object> status() {
        Long uid = auth.requireUserId();
        var r = reqRepo.findByUserId(uid).orElse(null);
        return r == null
                ? Map.of("status", "NONE")
                : Map.of("status", r.getReqStatus(), "requestedAtUtc", String.valueOf(r.getRequestedAtUtc()));
    }
}
