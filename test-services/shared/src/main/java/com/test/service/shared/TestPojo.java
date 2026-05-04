package com.test.service.shared;

/**
 * Simple POJO for testing ByteBuddy instrumentation
 * Not managed by Spring, so new instances are created each time
 */
public class TestPojo {

    public String doSomething(String input) {
        return "POJO processed: " + input;
    }
}
