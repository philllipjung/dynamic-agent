package com.test.service.test1.controller;

import com.test.service.shared.UserIdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test1")
public class Test1Controller {

    private final UserIdService userIdService;

    @Autowired
    public Test1Controller(UserIdService userIdService) {
        this.userIdService = userIdService;
    }

    @GetMapping
    public String test1(@RequestHeader(value = "userId", required = false) String userId) {
        if (userId == null || userId.isEmpty()) {
            userId = userIdService.generateUserId();
        }
        String result = userIdService.setUserId(userId);
        return "Test1 - userId: " + userId + ", setUserId result: " + result;
    }

    @GetMapping("/setUserId")
    public String setUserIdEndpoint(@RequestParam String userId) {
        String result = userIdService.setUserId(userId);
        return result;
    }

    @GetMapping("/generateUserId")
    public String generateUserId() {
        String userId = userIdService.generateUserId();
        return userId;
    }

    @GetMapping("/pojo")
    public String testPojo(@RequestParam(defaultValue = "test") String input) {
        com.test.service.shared.TestPojo pojo = new com.test.service.shared.TestPojo();
        return pojo.doSomething(input);
    }

    // 새로운 메서드 - advice 테스트용
    @GetMapping("/newMethod")
    public String newMethod(@RequestParam(defaultValue = "test") String input) {
        return "NEW_METHOD: " + input + " at " + System.currentTimeMillis();
    }
}
