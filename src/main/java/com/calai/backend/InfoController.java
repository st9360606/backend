// app 模組（或你的 Web 模組）底下
package com.calai.backend;

import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api")
public class InfoController {
    public record Info(String message, String serverTime) {}

    @GetMapping("/info")
    public Info info() {
        return new Info("Cal AI backend is up!", OffsetDateTime.now().toString());
    }
}
