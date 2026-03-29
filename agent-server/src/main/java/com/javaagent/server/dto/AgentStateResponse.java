package com.javaagent.server.dto;

import java.util.List;

/**
 * Response DTOs for Agent Management API
 */
public class AgentStateResponse {

    /**
     * JVM State Response
     * Represents a single attached JVM
     */
    public static class JvmState {
        private String pid;
        private String className;
        private String agentType; // BYTEBUDDY, ARTHAS, BOTH
        private String attachTime;

        public JvmState() {}

        public JvmState(String pid, String className, String agentType, String attachTime) {
            this.pid = pid;
            this.className = className;
            this.agentType = agentType;
            this.attachTime = attachTime;
        }

        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getAgentType() { return agentType; }
        public void setAgentType(String agentType) { this.agentType = agentType; }

        public String getAttachTime() { return attachTime; }
        public void setAttachTime(String attachTime) { this.attachTime = attachTime; }
    }

    /**
     * Instrumentation State Response
     * Represents a single instrumentation (advice applied to a method)
     */
    public static class InstrumentationState {
        private String className;
        private String methodName;
        private String adviceType; // SPAN, SPAN_ATTRIBUTE, EVENT
        private String appliedAt;

        public InstrumentationState() {}

        public InstrumentationState(String className, String methodName, String adviceType, String appliedAt) {
            this.className = className;
            this.methodName = methodName;
            this.adviceType = adviceType;
            this.appliedAt = appliedAt;
        }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public String getAdviceType() { return adviceType; }
        public void setAdviceType(String adviceType) { this.adviceType = adviceType; }

        public String getAppliedAt() { return appliedAt; }
        public void setAppliedAt(String appliedAt) { this.appliedAt = appliedAt; }
    }

    /**
     * JVM List Response
     * Wrapper for list of JVMs
     */
    public static class JvmListResponse {
        private List<JvmState> jvms;
        private int count;

        public JvmListResponse() {}

        public JvmListResponse(List<JvmState> jvms) {
            this.jvms = jvms;
            this.count = jvms != null ? jvms.size() : 0;
        }

        public List<JvmState> getJvms() { return jvms; }
        public void setJvms(List<JvmState> jvms) { this.jvms = jvms; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }

    /**
     * JVM Detail Response
     * Detailed info for a single JVM including its instrumentations
     */
    public static class JvmDetailResponse {
        private JvmState jvm;
        private List<InstrumentationState> instrumentations;
        private int instrumentationCount;

        public JvmDetailResponse() {}

        public JvmDetailResponse(JvmState jvm, List<InstrumentationState> instrumentations) {
            this.jvm = jvm;
            this.instrumentations = instrumentations;
            this.instrumentationCount = instrumentations != null ? instrumentations.size() : 0;
        }

        public JvmState getJvm() { return jvm; }
        public void setJvm(JvmState jvm) { this.jvm = jvm; }

        public List<InstrumentationState> getInstrumentations() { return instrumentations; }
        public void setInstrumentations(List<InstrumentationState> instrumentations) { this.instrumentations = instrumentations; }

        public int getInstrumentationCount() { return instrumentationCount; }
        public void setInstrumentationCount(int instrumentationCount) { this.instrumentationCount = instrumentationCount; }
    }

    /**
     * Instrumentation List Response
     * Wrapper for list of instrumentations
     */
    public static class InstrumentationListResponse {
        private String pid;
        private List<InstrumentationState> instrumentations;
        private int count;

        public InstrumentationListResponse() {}

        public InstrumentationListResponse(String pid, List<InstrumentationState> instrumentations) {
            this.pid = pid;
            this.instrumentations = instrumentations;
            this.count = instrumentations != null ? instrumentations.size() : 0;
        }

        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }

        public List<InstrumentationState> getInstrumentations() { return instrumentations; }
        public void setInstrumentations(List<InstrumentationState> instrumentations) { this.instrumentations = instrumentations; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }
}
