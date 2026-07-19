package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatThumbMediaIdResolverTest {

    @Mock
    private WeChatMaterialTool materialTool;

    private WeChatThumbMediaIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WeChatThumbMediaIdResolver(materialTool);
    }

    @Test
    void shouldResolveThumbMediaIdByDefaultCoverMaterialName() {
        when(materialTool.listPermanentImages(0, 20)).thenReturn(page(1,
                List.of(image("media-id-1", "ai_content_cover.png"))));

        String mediaId = resolver.resolve();

        assertThat(mediaId).isEqualTo("media-id-1");
    }

    @Test
    void shouldPageUntilDefaultCoverMaterialNameIsFound() {
        when(materialTool.listPermanentImages(0, 20)).thenReturn(page(21,
                List.of(image("media-id-old", "other.jpg"))));
        when(materialTool.listPermanentImages(20, 20)).thenReturn(page(21,
                List.of(image("media-id-cover", "ai_content_cover.jpg"))));

        String mediaId = resolver.resolve();

        assertThat(mediaId).isEqualTo("media-id-cover");
    }

    @Test
    void shouldCacheResolvedThumbMediaId() {
        when(materialTool.listPermanentImages(0, 20)).thenReturn(page(1,
                List.of(image("media-id-1", "AI_CONTENT_COVER.jpeg"))));

        assertThat(resolver.resolve()).isEqualTo("media-id-1");
        assertThat(resolver.resolve()).isEqualTo("media-id-1");

        verify(materialTool).listPermanentImages(0, 20);
    }

    @Test
    void shouldUseSinglePermanentImageWhenDefaultCoverNameIsAbsent() {
        when(materialTool.listPermanentImages(0, 20)).thenReturn(page(1,
                List.of(image("media-id-only", "only-image.jpg"))));

        String mediaId = resolver.resolve();

        assertThat(mediaId).isEqualTo("media-id-only");
    }

    @Test
    void shouldThrowWhenMultipleImagesExistWithoutDefaultCoverName() {
        when(materialTool.listPermanentImages(0, 20)).thenReturn(page(2,
                List.of(image("media-id-one", "one.jpg"), image("media-id-two", "two.jpg"))));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("无法唯一确定微信草稿封面永久图片素材")
                .hasMessageContaining("ai_content_cover");
    }

    private static WeChatMaterialTool.PermanentImagePage page(
            int totalCount, List<WeChatMaterialTool.PermanentImage> items) {
        return new WeChatMaterialTool.PermanentImagePage(totalCount, items.size(), items);
    }

    private static WeChatMaterialTool.PermanentImage image(String mediaId, String name) {
        return new WeChatMaterialTool.PermanentImage(mediaId, name, null, null);
    }
}
