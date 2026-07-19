package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the draft cover image {@code thumb_media_id} from WeChat permanent image materials.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatThumbMediaIdResolver {

    private static final int PAGE_SIZE = 20;
    private static final String DEFAULT_COVER_IMAGE_BASENAME = "ai_content_cover";

    private final WeChatMaterialTool materialTool;
    private volatile String cachedThumbMediaId;

    public WeChatThumbMediaIdResolver(WeChatMaterialTool materialTool) {
        this.materialTool = materialTool;
    }

    public String resolve() {
        String cached = cachedThumbMediaId;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (cachedThumbMediaId == null) {
                cachedThumbMediaId = queryThumbMediaId();
            }
            return cachedThumbMediaId;
        }
    }

    private String queryThumbMediaId() {
        List<WeChatMaterialTool.PermanentImage> images = new ArrayList<>();
        int offset = 0;
        while (true) {
            WeChatMaterialTool.PermanentImagePage page = materialTool.listPermanentImages(offset, PAGE_SIZE);
            for (WeChatMaterialTool.PermanentImage item : page.items()) {
                if (!StringUtils.hasText(item.mediaId())) {
                    continue;
                }
                if (isDefaultCoverImage(item.name())) {
                    log.info("微信草稿封面素材解析成功: strategy=defaultBasename, name={}, mediaIdLength={}",
                            item.name(), item.mediaId().length());
                    return item.mediaId();
                }
                images.add(item);
            }

            int nextOffset = offset + PAGE_SIZE;
            if (page.items().isEmpty() || nextOffset >= page.totalCount()) {
                break;
            }
            offset = nextOffset;
        }

        if (images.size() == 1) {
            WeChatMaterialTool.PermanentImage image = images.get(0);
            log.info("微信草稿封面素材解析成功: strategy=singleImage, name={}, mediaIdLength={}",
                    image.name(), image.mediaId().length());
            return image.mediaId();
        }

        throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                "无法唯一确定微信草稿封面永久图片素材: 请上传文件名为 " + DEFAULT_COVER_IMAGE_BASENAME
                        + " 的图片, 或确保永久图片素材库只有一张可用图片");
    }

    private static boolean isDefaultCoverImage(String materialName) {
        if (!StringUtils.hasText(materialName)) {
            return false;
        }
        String trimmedName = materialName.trim();
        int extensionIndex = trimmedName.lastIndexOf('.');
        String basename = extensionIndex <= 0 ? trimmedName : trimmedName.substring(0, extensionIndex);
        return DEFAULT_COVER_IMAGE_BASENAME.equalsIgnoreCase(basename);
    }
}
