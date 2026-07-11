package com.choucj.aiaggregator.source.github.client;

import com.choucj.aiaggregator.source.github.model.GitHubRepo;

import java.util.List;

/**
 * GitHub REST API 客户端 — 底层 HTTP helper (不实现 {@code DataSource}, 复用 Story 2.1 RSSHubClient 模式).
 *
 * <p>调用 GitHub REST API v3 (spike-4.1 §2.1 决策: Search API 替代官方无 /trending 端点).
 * 实现类 {@link GitHubClientImpl} 处理:
 * <ul>
 *   <li>查询模板渲染 (B2 模式: String.replace 占位符)</li>
 *   <li>Authorization Bearer token 注入 (Fine-grained PAT, spike-4.1 §2.2)</li>
 *   <li>异常映射 (HttpClientErrorException/HttpServerErrorException/ResourceAccessException
 *       → RetryableException/NonRetryableException, 复用 Story 2.1 RSSHubClient 决策表)</li>
 *   <li>JSON 响应解析为 {@link GitHubRepo} 列表 (D3 模式: primitive 字段 asInt(0) 默认值)</li>
 * </ul>
 *
 * <p><b>架构 delta (Story 4.1):</b>
 * 本接口是底层 HTTP helper, 不实现 {@code DataSource<GitHubRepo>}. 真正的 {@code DataSource}
 * 实现是 {@link com.choucj.aiaggregator.source.github.GitHubSource}, 编排 GitHubClient +
 * RedisRepository 缓存 (spike-4.1 §2.5 架构对齐).
 *
 * @see GitHubClientImpl
 */
public interface GitHubClient {

    /**
     * 抓取 Trending 仓库列表 (用 Search API + sort=stars 替代 /trending).
     *
     * <p>查询渲染: {@code queryTemplate.replace("{date}", today-minus-lookback)
     * .replace("{language}", language)} (B2 模式).
     * URL: {@code https://api.github.com/search/repositories?q={query}&sort=stars&order=desc&per_page={topN}}.
     *
     * @return 仓库列表 (可能为空, 但不为 {@code null}); 仅填充 8 字段
     *         (id/fullName/name/description/language/stars/forks/readmeUrl/url)
     * @throws com.choucj.aiaggregator.common.exception.RetryableException    GitHub 限流 (429/403+RateLimit-Remaining:0) / 5xx / 连接异常
     * @throws com.choucj.aiaggregator.common.exception.NonRetryableException 4xx (除 403-rate-limit / 429), 参数错
     */
    List<GitHubRepo> fetchTrending();

    /**
     * 抓取指定仓库的 README 文本 (base64 解码 + 100KB 截断).
     *
     * <p>调用 {@code GET /repos/{owner}/{repo}/readme}, 取响应 {@code content} 字段 (base64 编码的
     * README 全文), 用 {@link java.util.Base64#getMimeDecoder()} 解码 (容忍 GitHub 注入的
     * {@code \n} 换行), 转 UTF-8 字符串.
     *
     * <p><b>截断策略 (spike-4.1 §2.6 + C1 token 估算):</b>
     * <ul>
     *   <li>解码后字符数 ≤ {@code readme.max-size-kb * 1024} (默认 100KB ≈ 60K token / 128K 上下文 47%): 完整返回</li>
     *   <li>100KB-1MB: 截断为前 100KB (顶部含项目简介 + 安装 + 使用, 价值最高)</li>
     *   <li>> 1MB: 直接拒绝 (返 null + warn), 防 OOM (spike-4.1 §2.6 决策)</li>
     * </ul>
     *
     * <p><b>异常映射 (复用 Story 4.1 fetchTrending 决策表):</b>
     * <ul>
     *   <li>404 Not Found: 不抛, debug log + 返 null (业务级"无 README"语义, AC-2)</li>
     *   <li>403 + X-RateLimit-Remaining:0 / 429 / 5xx / 连接异常: {@code RetryableException} (上层 Processor 重试)</li>
     *   <li>4xx 其他 (除 404 / 429 / 403-rate-limit): {@code NonRetryableException} (上层跳过该仓库)</li>
     *   <li>200 + base64 解码失败 ({@link IllegalArgumentException}): warn + 返 null (不阻塞 Pipeline)</li>
     * </ul>
     *
     * <p><b>D3 警示 (调用方 null-check):</b> 返回值 nullable. 调用方 (Story 4.3 InnovationFilter /
     * Story 4.4 GitHubProcessor) 必须先 null-check 再用, 降级为仅用 description + 元数据分支.
     *
     * @param owner 仓库所有者 (e.g., {@code "langchain4j"}), 非空
     * @param repo  仓库名 (e.g., {@code "langchain4j"}), 非空
     * @return README 文本 (可能为 null: 仓库无 README / >1MB / 总开关关 / base64 解码失败); 非空时为截断后的全文
     * @throws com.choucj.aiaggregator.common.exception.RetryableException    GitHub 限流 (429/403+RateLimit-Remaining:0) / 5xx / 连接异常
     * @throws com.choucj.aiaggregator.common.exception.NonRetryableException 4xx (除 404 / 429 / 403-rate-limit), 参数错 / 权限错
     */
    String fetchReadme(String owner, String repo);
}
