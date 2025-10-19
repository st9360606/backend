package com.calai.backend.users.controller;

import com.calai.backend.auth.repo.UserRepo;
import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.dto.MeDto;
import com.calai.backend.users.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users/me")
public class UserMeController {

    private final UserRepo users;
    private final AuthContext auth; // ★ 統一用 AuthContext 取得 uid

    @GetMapping
    public MeDto me() {
        Long uid = auth.requireUserId();
        User u = users.findById(uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeDto(u.getId(), u.getEmail(), u.getName(), u.getPicture());
    }
}
