package com.calai.backend.auth.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/me")
public class UserController {
    @GetMapping
    public Object me(@RequestAttribute("userId") Long uid) {
        return new Me(uid);
    }
    record Me(Long userId) {}
}
