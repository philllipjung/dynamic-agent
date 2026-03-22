package com.javaagent.bytebuddy;

import com.javaagent.commons.AgentConstants;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * HelperClassInjector - Adds advice JAR to System ClassLoader
 *
 * Finds bytebuddy-advice JAR and adds it to System ClassLoader search path.
 * This ensures helper classes are visible to all child ClassLoaders.
 */
public class HelperClassInjector {

    /**
     * Prepare helper classes by adding advice JAR to System ClassLoader
     */
    public static void prepareHelpers() {
        try {
            // Find the advice JAR in the same directory as agent JAR
            URL adviceJarUrl = findAdviceJar();
            if (adviceJarUrl == null) {
                System.err.println("[HelperClassInjector] Advice JAR not found");
                return;
            }

            // Add advice JAR to System ClassLoader
            addURLToSystemClassLoader(adviceJarUrl);
            System.out.println("[HelperClassInjector] Added advice JAR to System ClassLoader: " + adviceJarUrl);
            System.out.println("[HelperClassInjector] Helper classes now visible to all child ClassLoaders");

        } catch (Exception e) {
            System.err.println("[HelperClassInjector] Failed to prepare helper classes: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find the advice JAR file (assumes it's in the same directory as the agent JAR)
     */
    private static URL findAdviceJar() {
        try {
            // Get the current class's location
            String classResource = HelperClassInjector.class.getName().replace('.', '/') + ".class";
            URL classUrl = HelperClassInjector.class.getClassLoader().getResource(classResource);

            if (classUrl == null) {
                System.err.println("[HelperClassInjector] Cannot find advice class location");
                return null;
            }

            String path = classUrl.getPath();

            // Extract JAR path from class URL
            // Format: file:/path/to/bytebuddy-agent.jar!/com/javaagent/bytebuddy/HelperClassInjector.class
            if (path.contains("!")) {
                String jarPath = path.substring(0, path.indexOf("!"));
                if (jarPath.startsWith("file:")) {
                    jarPath = jarPath.substring(5); // Remove "file:" prefix
                }
                File agentJar = new File(jarPath);
                File parentDir = agentJar.getParentFile();

                if (parentDir != null) {
                    // Look for advice JAR in the same directory
                    File adviceJar = new File(parentDir, AgentConstants.ADVICE_JAR_NAME);
                    if (adviceJar.exists()) {
                        return adviceJar.toURI().toURL();
                    }
                }
            }

            return null;

        } catch (Exception e) {
            System.err.println("[HelperClassInjector] Error finding advice JAR: " + e.getMessage());
            return null;
        }
    }

    /**
     * Add URL to System ClassLoader using reflection
     */
    private static void addURLToSystemClassLoader(URL url) throws Exception {
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

        if (systemClassLoader instanceof URLClassLoader) {
            // Use reflection to call protected addURL() method
            Method addURLMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURLMethod.setAccessible(true);
            addURLMethod.invoke(systemClassLoader, url);
        } else {
            System.err.println("[HelperClassInjector] System ClassLoader is not URLClassLoader (Java 9+ module system)");
        }
    }
}
