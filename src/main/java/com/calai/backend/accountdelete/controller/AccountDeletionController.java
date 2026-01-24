package com.calai.backend.accountdelete.controller;

import com.calai.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.calai.backend.accountdelete.service.AccountDeletionService;
import com.calai.backend.auth.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/account")
public class AccountDeletionController {

    private final AuthContext auth;
    private final AccountDeletionService service;
    private final AccountDeletionRequestRepository reqRepo;

    @PostMapping("/deletion-request")
    public Map<String, Object> request() {
        Long uid = auth.requireUserId();
        service.requestDeletion(uid);
        return Map.of("ok", true);
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
