package com.calai.backend.users.user.controller;

import com.calai.backend.auth.repo.UserRepo;
import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.user.dto.MeDto;
import com.calai.backend.users.user.dto.UpdateNameRequest;
import com.calai.backend.users.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users/me")
public class UserMeController {

    private static final int NAME_MAX_LEN = 40;
    private final UserRepo users;
    private final AuthContext auth; // ★ 統一用 AuthContext 取得 uid

    @GetMapping
    public MeDto me() {
        Long uid = auth.requireUserId();
        User u = users.findById(uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeDto(u.getId(), u.getEmail(), u.getName(), u.getPicture());
    }

    // ✅ 新增：更新 name
    @PutMapping
    public MeDto updateMe(@RequestBody UpdateNameRequest req) {
        Long uid = auth.requireUserId();
        User u = users.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        String normalized = normalizeName(req.name());
        u.setName(normalized);
        users.save(u); // ✅ 保險：確保寫入

        return new MeDto(u.getId(), u.getEmail(), u.getName(), u.getPicture());
    }

    private String normalizeName(String raw) {
        if (raw == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        String s = raw.trim();
        if (s.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name cannot be blank");
        if (s.length() > NAME_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name too long (max " + NAME_MAX_LEN + ")");
        }
        return s;
    }
}
