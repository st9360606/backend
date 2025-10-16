package com.calai.backend.users.controller;

import com.calai.backend.auth.repo.UserRepo;
import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.dto.MeDto;
import com.calai.backend.users.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserMeController {

    private final UserRepo users;
    private final AuthContext auth;

    public UserMeController(UserRepo users, AuthContext auth) {
        this.users = users;
        this.auth = auth;
    }

    @GetMapping
    public MeDto me() {
        Long uid = auth.requireUserId(); // 需要登入；未登入時你既有的 AuthContext 會處理
        User u = users.findById(uid).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        return new MeDto(u.getId(), u.getEmail(), u.getName(), u.getPicture());
    }
}

