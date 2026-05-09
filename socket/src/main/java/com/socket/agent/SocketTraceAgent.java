package com.socket.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * 🤖 100% 자동 계측을 위한 ByteBuddy Agent
 *
 * 이 Agent는:
 * 1. SocketHandler.handle() 메서드에 자동으로 추적 기능 추가 (서버)
 * 2. PrintWriter.println() 메서드에 traceparent 헤더 자동 추가 (클라이언트)
 *
 * 사용자 코드에는 추적 관련 코드가 전혀 필요 없습니다!
 */
public class SocketTraceAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("=================================================");
        System.out.println("🤖 100% 자동 계측 Agent 시작 (서버 + 클라이언트)");
        System.out.println("=================================================");

        configureServerAgent(inst);
        configureClientAgent(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("=================================================");
        System.out.println("🤖 100% 자동 계측 Agent 시작 (동적 부착)");
        System.out.println("=================================================");

        configureServerAgent(inst);
        configureClientAgent(inst);
    }

    private static void configureServerAgent(Instrumentation inst) {
        System.out.println("⚙️ 서버 측 자동 계측 설정 중...");

        new AgentBuilder.Default()
                .type(ElementMatchers.named("com.socket.server.SocketHandler"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    System.out.println("🔧 변환 중: " + typeDescription.getName());

                    return builder.visit(
                            net.bytebuddy.asm.Advice.to(com.socket.agent.SocketTraceAdvice.class)
                                    .on(ElementMatchers.named("handle")
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.takesArgument(0, java.net.Socket.class)))
                    );
                })
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .installOn(inst);

        System.out.println("✅ 서버 측 자동 계측 설정 완료!");
    }

    private static void configureClientAgent(Instrumentation inst) {
        System.out.println("⚙️ 클라이언트 측 자동 계측 설정 중...");

        new AgentBuilder.Default()
                .type(ElementMatchers.named("java.io.PrintWriter"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    return builder.visit(
                            net.bytebuddy.asm.Advice.to(com.socket.agent.SocketOutputAdvice.class)
                                    .on(ElementMatchers.named("println")
                                            .and(ElementMatchers.takesArgument(0, String.class)))
                    );
                })
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .installOn(inst);

        System.out.println("✅ 클라이언트 측 자동 계측 설정 완료!");
        System.out.println("=================================================");
        System.out.println("🎯 목표:");
        System.out.println("  - 서버: SocketHandler.handle()에 추적 코드 자동 추가");
        System.out.println("  - 클라이언트: PrintWriter.println()에 traceparent 자동 추가");
        System.out.println("🚀 사용자 코드는 순수 비즈니스 로직만!");
        System.out.println("=================================================");
    }
}
