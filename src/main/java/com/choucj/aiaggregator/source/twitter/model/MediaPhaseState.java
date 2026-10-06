package com.choucj.aiaggregator.source.twitter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 描述单个媒体处理阶段，保留重放和故障定位所需的有限证据。 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MediaPhaseState {
    @Builder.Default
    private MediaPhaseStatus status = MediaPhaseStatus.NOT_STARTED;
    @Builder.Default
    private int attempt = 0;
    private LocalDateTime nextRetryAt;
    private String errorClass;
    private String errorCode;
    private String errorSummary;
    private LocalDateTime updatedAt;

    /** 创建尚未开始的阶段，避免不同调用方产生不同默认值。 */
    public static MediaPhaseState notStarted() {
        return MediaPhaseState.builder().status(MediaPhaseStatus.NOT_STARTED).attempt(0).build();
    }
}
