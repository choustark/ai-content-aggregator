package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 2.3a {@link LangChain4jLlmClient} 单测.
 *
 * <p>覆盖:
 * <ul>
 *   <li>happy 路径(DeepSeek 主)</li>
 *   <li>DeepSeek 失败降级 GLM</li>
 *   <li>双失败抛 RetryableException(EXTERNAL_API_ERROR)</li>
 *   <li>chatWithModel 显式切换 (含不支持的 model)</li>
 *   <li>空 prompt / systemPrompt 校验</li>
 *   <li>chat(system, user) 走 ChatResponse.aiMessage().text()</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LangChain4jLlmClientTest {

    @Mock
    private ChatModel deepSeekChatModel;

    @Mock
    private ChatModel glmChatModel;

    private LangChain4jLlmClient client;

    @BeforeEach
    void setUp() {
        client = new LangChain4jLlmClient(deepSeekChatModel, glmChatModel);
    }

    @Test
    void shouldReturnDeepSeekResponseOnHappyPath() {
        when(deepSeekChatModel.chat("hello")).thenReturn("hi from deepseek");

        String result = client.chat("hello");

        assertThat(result).isEqualTo("hi from deepseek");
        verify(glmChatModel, never()).chat(anyString());
    }

    @Test
    void shouldFallbackToGlmWhenDeepSeekFails() {
        when(deepSeekChatModel.chat("hello")).thenThrow(new RuntimeException("deepseek timeout"));
        when(glmChatModel.chat("hello")).thenReturn("hi from glm");

        String result = client.chat("hello");

        assertThat(result).isEqualTo("hi from glm");
        verify(deepSeekChatModel, times(1)).chat("hello");
        verify(glmChatModel, times(1)).chat("hello");
    }

    @Test
    void shouldThrowRetryableWhenBothModelsFail() {
        when(deepSeekChatModel.chat("hello")).thenThrow(new RuntimeException("ds down"));
        when(glmChatModel.chat("hello")).thenThrow(new RuntimeException("glm down"));

        assertThatThrownBy(() -> client.chat("hello"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldUseExplicitModelWithoutFallback() {
        when(glmChatModel.chat("hello")).thenReturn("glm only");

        String result = client.chatWithModel("glm", "hello");

        assertThat(result).isEqualTo("glm only");
        verify(deepSeekChatModel, never()).chat(anyString());
    }

    @Test
    void shouldThrowRetryableWhenExplicitModelFailsWithoutFallback() {
        when(deepSeekChatModel.chat("hello")).thenThrow(new RuntimeException("ds down"));

        assertThatThrownBy(() -> client.chatWithModel("deepseek", "hello"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        verify(glmChatModel, never()).chat(anyString());
    }

    @Test
    void shouldRejectUnknownModel() {
        assertThatThrownBy(() -> client.chatWithModel("unknown-model", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的模型");
    }

    @Test
    void shouldRejectBlankPrompt() {
        assertThatThrownBy(() -> client.chat(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankSystemOrUserPrompt() {
        assertThatThrownBy(() -> client.chat("", "user"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("system", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleSystemUserChatWithChatResponse() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("score: 8"))
                .build();
        when(deepSeekChatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        String result = client.chat("you are scorer", "score this");

        assertThat(result).isEqualTo("score: 8");
    }

    @Test
    void shouldFallbackToGlmOnSystemUserChatWhenDeepSeekFails() {
        ChatResponse dsResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("glm score"))
                .build();
        when(deepSeekChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("ds down"));
        when(glmChatModel.chat(any(ChatMessage[].class))).thenReturn(dsResponse);

        String result = client.chat("system", "user");

        assertThat(result).isEqualTo("glm score");
    }

    @Test
    void shouldThrowRetryableWhenBothFailOnSystemUserChat() {
        when(deepSeekChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("ds down"));
        when(glmChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("glm down"));

        assertThatThrownBy(() -> client.chat("system", "user"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }
}
