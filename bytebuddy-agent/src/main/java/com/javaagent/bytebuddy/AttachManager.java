package com.javaagent.bytebuddy;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Manager for attaching ByteBuddy agent to running JVM
 */
public class AttachManager {

    private static String agentJarPath;

    /**
     * Attach ByteBuddy agent to a JVM by PID
     */
    public static String attach(String pid) {
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            String agentPath = getAgentJarPath();

            System.out.println("[ByteBuddy] Attaching agent to PID " + pid + " from " + agentPath);
            vm.loadAgent(agentPath);
            vm.detach();

            return "SUCCESS: ByteBuddy agent attached to PID " + pid;
        } catch (AttachNotSupportedException e) {
            return "ERROR: JVM does not support attach - " + e.getMessage();
        } catch (IOException e) {
            return "ERROR: IO error - " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Find JVM PID by class name (partial match)
     */
    public static String findPidByClassName(String className) {
        try {
            String jpsOutput = runJps();
            String[] lines = jpsOutput.split("\n");

            for (String line : lines) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length >= 2) {
                    String processName = parts[1];
                    if (processName.contains(className) || processName.contains(className.replace(".", "/"))) {
                        return parts[0]; // Return PID
                    }
                }
            }
            return "ERROR: No JVM found with class name: " + className;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static String runJps() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("jps", "-l");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        java.util.Scanner scanner = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A");
        String output = scanner.hasNext() ? scanner.next() : "";
        scanner.close();
        return output;
    }

    private static String getAgentJarPath() throws IOException {
        if (agentJarPath != null && new File(agentJarPath).exists()) {
            return agentJarPath;
        }

        // Find the agent JAR (should be built at target/bytebuddy-agent-1.0.0.jar)
        String classPath = ByteBuddyAgent.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (classPath.endsWith(".jar") || classPath.endsWith(".jar/")) {
            agentJarPath = classPath.split("!")[0].replace("file:", "");
            return agentJarPath;
        }

        // For development, use absolute path to the agent jar
        String agentJar = "/root/java-agent-system/bytebuddy-agent/target/bytebuddy-agent-1.0.0.jar";
        if (new File(agentJar).exists()) {
            agentJarPath = agentJar;
            return agentJarPath;
        }

        // As a fallback, try the original (non-shaded) jar
        String originalJar = "/root/java-agent-system/bytebuddy-agent/target/original-bytebuddy-agent-1.0.0.jar";
        if (new File(originalJar).exists()) {
            agentJarPath = originalJar;
            return agentJarPath;
        }

        throw new IOException("Agent JAR not found at expected location");
    }
}
