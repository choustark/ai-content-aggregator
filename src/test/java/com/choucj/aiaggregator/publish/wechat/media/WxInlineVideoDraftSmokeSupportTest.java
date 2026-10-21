package com.choucj.aiaggregator.publish.wechat.media;

import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftInfo;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WxInlineVideoDraftSmokeSupportTest {

    @Test
    void selectsMatchingArticleFromMultiArticleDraft() {
        WxMpDraftArticles first = new WxMpDraftArticles().setTitle("其他文章").setContent("first");
        WxMpDraftArticles expected = new WxMpDraftArticles().setTitle("目标文章").setContent("target");

        WxMpDraftArticles selected = WxInlineVideoDraftSmokeTest.selectArticle(
                new WxMpDraftInfo(List.of(first, expected)), "目标文章");

        assertThat(selected.getContent()).isEqualTo("target");
    }

    @Test
    void explicitEnvironmentCodeAndDnsCauseAreBlocked() {
        assertThat(WxInlineVideoDraftSmokeTest.environmentBlocked(
                new WxErrorException(new WxError(40164, "invalid ip")))).isTrue();
        assertThat(WxInlineVideoDraftSmokeTest.environmentBlocked(
                new WxErrorException(new WxError(-1, "transport"), new UnknownHostException("host")))).isTrue();
    }

    @Test
    void apiUrlMentionWithoutRecognizedCauseIsNotBlocked() {
        WxErrorException rejection = new WxErrorException(
                new WxError(40003, "api.weixin.qq.com rejected request"));

        assertThat(WxInlineVideoDraftSmokeTest.environmentBlocked(rejection)).isFalse();
    }
}
