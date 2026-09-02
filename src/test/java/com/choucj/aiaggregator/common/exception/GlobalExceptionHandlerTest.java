package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 1.4 GlobalExceptionHandler MockMvc 集成测试.
 *
 * <p>使用 {@link MockMvcBuilders#standaloneSetup} + {@code setControllerAdvice}
 * 而非 {@code @WebMvcTest} / {@code @SpringBootTest}:
 * <ul>
 *   <li>不启动 Spring 上下文 → 不依赖 Redis Cluster, 测试 0 IO</li>
 *   <li>显式注册 {@link GlobalExceptionHandler} 作为 ControllerAdvice, 隔离范围</li>
 *   <li>测试速度 &lt; 0.1s / 用例</li>
 * </ul>
 *
 * <p>验证 6 条映射路径:
 * <ol>
 *   <li>{@code RetryableException} → 503 + ErrorResponse(code=RETRYABLE_ERROR)</li>
 *   <li>{@code NonRetryableException} → 400 + ErrorResponse(code=NON_RETRYABLE_ERROR)</li>
 *   <li>{@code DegradationException} → 503 + ErrorResponse(code=DEGRADATION_NEEDED, message="服务降级,请稍后重试")</li>
 *   <li>{@code NotFoundException} → 404 + ErrorResponse(code=NON_RETRYABLE_ERROR)</li>
 *   <li>{@code NoResourceFoundException} → 404 + ErrorResponse(路由不存在, 不落入 500 兜底)</li>
 *   <li>{@code Exception} 兜底 → 500 + ErrorResponse(code=INTERNAL_ERROR, message="系统错误")</li>
 * </ol>
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldMapRetryableExceptionTo503() throws Exception {
        mockMvc.perform(get("/test/retryable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.RETRYABLE_ERROR.name()))
                .andExpect(jsonPath("$.message").value("RSSHUB 暂时不可用"));
    }

    @Test
    void shouldMapNonRetryableExceptionTo400() throws Exception {
        mockMvc.perform(get("/test/non-retryable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.NON_RETRYABLE_ERROR.name()))
                .andExpect(jsonPath("$.message").value("URL 非法"));
    }

    @Test
    void shouldMapNonRetryableWithCustomCodeStillReturn400() throws Exception {
        mockMvc.perform(get("/test/non-retryable-custom-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.name()))
                .andExpect(jsonPath("$.message").value("自定义错误码场景"));
    }

    @Test
    void shouldMapDegradationExceptionTo503WithGenericMessage() throws Exception {
        // DegradationException 不应重抛 — Spring MVC 不会路由重抛异常到同 advice 其他 handler
        // 直接返回 503 + 通用消息, 防止原始异常信息泄漏 (AR8 合规)
        mockMvc.perform(get("/test/degradation"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.DEGRADATION_NEEDED.name()))
                .andExpect(jsonPath("$.message").value("服务降级,请稍后重试"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not("DeepSeek 超时, 切换 GLM")));
    }

    @Test
    void shouldMapNotFoundExceptionTo404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NON_RETRYABLE_ERROR.name()))
                .andExpect(jsonPath("$.message").value("Article 状态未找到"));
    }

    @Test
    void shouldMapNoResourceFoundExceptionTo404Not500() throws Exception {
        // 路径打错是客户端错误 → 404, 不应被 Exception 兜底吞成 500 "系统错误" 误导排查
        // (standalone MockMvc 无静态资源 handler, 由测试端点直接抛 NoResourceFoundException 模拟
        //  Spring Boot 3 生产环境未匹配路由时 ResourceHttpRequestHandler 的抛出行为)
        mockMvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NON_RETRYABLE_ERROR.name()))
                .andExpect(jsonPath("$.message").value("请求路径不存在"));
    }

    @Test
    void shouldMapUnexpectedExceptionTo500WithoutStackTraceLeak() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.name()))
                .andExpect(jsonPath("$.message").value("系统错误"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not("NullPointer inside")));
    }

    /**
     * 测试专用 Controller — 仅注册在测试环境, 不进生产代码.
     *
     * <p>每个端点抛出对应类型的异常, 模拟业务侧 throw 行为.
     * Spring MVC 会将异常流到 {@link GlobalExceptionHandler} 拦截.
     */
    @RestController
    static class ExceptionThrowingController {

        @GetMapping("/test/retryable")
        public void throwRetryable() {
            throw new RetryableException("RSSHUB 暂时不可用");
        }

        @GetMapping("/test/non-retryable")
        public void throwNonRetryable() {
            throw new NonRetryableException("URL 非法");
        }

        @GetMapping("/test/non-retryable-custom-code")
        public void throwNonRetryableCustomCode() {
            throw new NonRetryableException(ErrorCode.INTERNAL_ERROR, "自定义错误码场景");
        }

        @GetMapping("/test/degradation")
        public void throwDegradation() {
            throw new DegradationException("DeepSeek 超时, 切换 GLM");
        }

        @GetMapping("/test/not-found")
        public void throwNotFound() {
            throw new NotFoundException(ErrorCode.NON_RETRYABLE_ERROR, "Article 状态未找到");
        }

        @GetMapping("/test/no-resource")
        public void throwNoResourceFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "test/no-such-route");
        }

        @GetMapping("/test/unexpected")
        public void throwUnexpected() {
            throw new NullPointerException("NullPointer inside");
        }
    }
}
