package com.choucj.aiaggregator.source.twitter.api;

import com.choucj.aiaggregator.common.exception.GlobalExceptionHandler;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.source.twitter.SpecifiedXContentService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpecifiedXContentControllerTest {

    @Test
    void shouldRejectMissingTokenWithoutCallingService() throws Exception {
        SpecifiedXContentService service = mock(SpecifiedXContentService.class);
        MockMvc mvc = mvc(new SpecifiedXContentController(service, "secret"));

        mvc.perform(post("/api/twitter/specified")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"123\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).process("123");
    }

    @Test
    void shouldRejectWhenConfiguredTokenIsEmptyWithoutCallingService() throws Exception {
        SpecifiedXContentService service = mock(SpecifiedXContentService.class);
        MockMvc mvc = mvc(new SpecifiedXContentController(service, ""));

        mvc.perform(post("/api/twitter/specified")
                        .header(SpecifiedXContentController.TOKEN_HEADER, "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"123\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).process("123");
    }

    @Test
    void shouldRejectWrongTokenWithoutCallingService() throws Exception {
        SpecifiedXContentService service = mock(SpecifiedXContentService.class);
        MockMvc mvc = mvc(new SpecifiedXContentController(service, "secret"));

        mvc.perform(post("/api/twitter/specified")
                        .header(SpecifiedXContentController.TOKEN_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"123\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).process("123");
    }

    @Test
    void shouldReturnSafeSummaryForAuthorizedRequest() throws Exception {
        SpecifiedXContentService service = mock(SpecifiedXContentService.class);
        when(service.process("123")).thenReturn(
                new SpecifiedXContentService.Result("123", 7, 5, 3, 2, false, 42));
        MockMvc mvc = mvc(new SpecifiedXContentController(service, "secret"));

        mvc.perform(post("/api/twitter/specified")
                        .header(SpecifiedXContentController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweetId").value("123"))
                .andExpect(jsonPath("$.discovered").value(7))
                .andExpect(jsonPath("$.filtered").value(5))
                .andExpect(jsonPath("$.archived").value(3))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.elapsedMs").value(42))
                .andExpect(jsonPath("$.target").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());

        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    void shouldClearCorrelationContextWhenServiceFails() throws Exception {
        SpecifiedXContentService service = mock(SpecifiedXContentService.class);
        when(service.process("123")).thenThrow(new IllegalStateException("boom"));
        MockMvc mvc = mvc(new SpecifiedXContentController(service, "secret"));

        try {
            mvc.perform(post("/api/twitter/specified")
                    .header(SpecifiedXContentController.TOKEN_HEADER, "secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"target\":\"123\"}"));
        } catch (Exception ignored) {
            // standalone MockMvc 会把未映射的运行时异常交还测试；这里只验证 finally 清理。
        }

        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    void shouldRegisterOnlyWhenExplicitlyEnabled() {
        SpecifiedXContentService service = mock(SpecifiedXContentService.class);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(SpecifiedXContentService.class, () -> service)
                .withUserConfiguration(SpecifiedXContentController.class)
                .withPropertyValues("twitter.specified.token=secret");

        runner.run(context -> assertThat(context).doesNotHaveBean(SpecifiedXContentController.class));
        runner.withPropertyValues("twitter.specified.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SpecifiedXContentController.class));
    }

    private MockMvc mvc(SpecifiedXContentController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
