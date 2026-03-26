package com.javaagent.bytebuddy.advices;

import java.io.FileWriter;
import java.io.IOException;

public class SimpleInterceptor {

    public static void intercept() {
        try {
            FileWriter fw = new FileWriter("/tmp/interceptor-executed.txt", true);
            fw.write("INTERCEPTOR_CALLED at " + System.currentTimeMillis() + "\n");
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
