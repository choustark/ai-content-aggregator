package com.choucj.aiaggregator.source.twitter.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Apify Twitter/X Actor 配置.
 */
@ConfigurationProperties(prefix = "apify.twitter")
@Validated
@Data
public class ApifyTwitterProperties {

    /** Apify API base URL. */
    private String baseUrl = "https://api.apify.com";

    /** Actor API id, e.g. parseforge~x-com-scraper. */
    private String actorId = "parseforge~x-com-scraper";

    /** Apify API token, 建议由 APIFY_API_TOKEN 注入. */
    private String token;

    /** 单账号最多抓取条数. */
    @Min(value = 1, message = "apify.twitter.max-tweets-per-account must be positive")
    private int maxTweetsPerAccount = 50;

    /** HTTP 调用超时秒数. 同步 Actor 运行会等待结果, 因此默认较长. */
    @Min(value = 1, message = "apify.twitter.timeout-seconds must be positive")
    private int timeoutSeconds = 180;

    /** Actor input 中账号列表字段名. */
    private String handleField = "usernames";

    /** 是否用 X 搜索语法 from:{handle} 发现账号推文. */
    private boolean useSearchTerms = false;

    /** Actor input 中搜索词列表字段名. */
    private String searchTermField = "searchTerms";

    /** 搜索词模板, 用 String.formatted(handle) 生成; 默认 from:{handle}. */
    private String searchTermTemplate = "from:%s";

    /** Actor input 中数量限制字段名. */
    private String maxItemsField = "maxItems";

    /** 透传给 Actor 的默认 input 字段. */
    private Map<String, Object> inputDefaults = new LinkedHashMap<>();
}
