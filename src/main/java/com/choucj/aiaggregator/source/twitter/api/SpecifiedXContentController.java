package com.choucj.aiaggregator.source.twitter.api;

import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.model.ErrorResponse;
import com.choucj.aiaggregator.source.twitter.SpecifiedXContentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 默认关闭且由共享令牌保护的指定 X Post 同步入口。 */
@RestController
@RequestMapping("/api/twitter/specified")
@ConditionalOnProperty(prefix = "twitter.specified", name = "enabled", havingValue = "true")
public class SpecifiedXContentController {

    static final String TOKEN_HEADER = "X-Specified-X-Token";

    private final SpecifiedXContentService service;
    private final String configuredToken;

    public SpecifiedXContentController(SpecifiedXContentService service,
                                       @Value("${twitter.specified.token:}") String configuredToken) {
        this.service = service;
        this.configuredToken = configuredToken;
    }

    @PostMapping
    public ResponseEntity<?> process(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) SpecifiedXContentRequest request) {
        if (configuredToken == null || configuredToken.isBlank()
                || token == null || !constantTimeEquals(token, configuredToken)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse(ErrorCode.NON_RETRYABLE_ERROR, "指定内容访问令牌无效"));
        }
        CorrelationContext.begin(null);
        try {
            SpecifiedXContentService.Result result = service.process(request == null ? null : request.target());
            return ResponseEntity.ok(new SpecifiedXContentResponse(
                    result.tweetId(), result.discovered(), result.filtered(), result.archived(),
                    result.failed(), result.degraded(), result.elapsedMs()));
        } finally {
            CorrelationContext.end();
        }
    }

    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
