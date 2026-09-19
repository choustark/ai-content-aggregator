package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.observability.TestSlowOperationRecorder;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.wechat.client.WxJavaWeChatClient;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Story 3.1 — WxJavaWeChatClient token 获取与异常映射测试.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WxJavaWeChatClientTest {

    @Mock
    private WxMpService wxMpService;

    private WeChatProperties weChatProperties;
    private WxJavaWeChatClient client;

    @BeforeEach
    void setUp() {
        weChatProperties = new WeChatProperties();
        weChatProperties.getClient().setStableAccessToken(true);
        client = new WxJavaWeChatClient(wxMpService, weChatProperties, TestSlowOperationRecorder.create());
    }

    @Test
    void getAccessTokenSuccess(CapturedOutput output) throws WxErrorException {
        String token = "x".repeat(120);
        when(wxMpService.getAccessToken()).thenReturn(token);

        String result = client.getAccessToken();

        assertThat(result).isEqualTo(token);
        assertThat(output)
                .contains("WxJava access token acquired")
                .contains("length=" + token.length())
                .contains("stable=true");
    }

    @Test
    void getAccessTokenThrowsNonRetryableOnNullToken(CapturedOutput output) throws WxErrorException {
        when(wxMpService.getAccessToken()).thenReturn(null);

        assertThatThrownBy(() -> client.getAccessToken())
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava getAccessToken 返回 null token")
                .hasMessageNotContaining("WxJava 框架异常");
        assertThat(output).doesNotContain("WxJava access token acquired");
    }

    @Test
    void getAccessTokenThrowsNonRetryableOnInvalidCredential() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(wxError(40001, "invalid credential"));

        assertWechatException(NonRetryableException.class, ErrorCode.WECHAT_INVALID_CREDENTIAL, 40001);
    }

    @Test
    void getAccessTokenThrowsRetryableOnTokenExpired() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(wxError(40014, "invalid access_token"));

        assertWechatException(RetryableException.class, ErrorCode.WECHAT_TOKEN_EXPIRED, 40014);
    }

    @Test
    void getAccessTokenThrowsNonRetryableOnIpWhitelist() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(wxError(40164, "invalid ip"));

        assertWechatException(NonRetryableException.class, ErrorCode.WECHAT_API_ERROR, 40164);
    }

    @Test
    void getAccessTokenThrowsNonRetryableOnRateLimit() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(wxError(45009, "reach max api daily quota limit"));

        assertWechatException(NonRetryableException.class, ErrorCode.WECHAT_API_ERROR, 45009);
    }

    @Test
    void getAccessTokenThrowsRetryableOnSystemBusy() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(wxError(-1, "system busy"));

        assertWechatException(RetryableException.class, ErrorCode.WECHAT_API_ERROR, -1);
    }

    @Test
    void getAccessTokenThrowsNonRetryableOnUnknownError() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(wxError(99999, "unknown error"));

        assertWechatException(NonRetryableException.class, ErrorCode.WECHAT_API_ERROR, 99999);
    }

    @Test
    void getAccessTokenThrowsNonRetryableOnRuntimeException() throws WxErrorException {
        when(wxMpService.getAccessToken()).thenThrow(new IllegalStateException("sdk state broken"));

        assertThatThrownBy(() -> client.getAccessToken())
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava 框架异常: sdk state broken");
    }

    @Test
    void addDraftThrowsNonRetryableUntilStory33ImplementsIt() {
        assertThatThrownBy(() -> client.addDraft(null))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("Story 3.3 WeChatPublisher 将实施 addDraft");
    }

    private void assertWechatException(
            Class<? extends AggregatorException> exceptionType,
            ErrorCode errorCode,
            int errcode) {
        assertThatThrownBy(() -> client.getAccessToken())
                .isInstanceOf(exceptionType)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode()).isEqualTo(errorCode))
                .hasMessageContaining("微信 getAccessToken 失败")
                .hasMessageContaining("errcode=" + errcode);
    }

    private static WxErrorException wxError(int code, String message) {
        return new WxErrorException(new WxError(code, message));
    }
}
