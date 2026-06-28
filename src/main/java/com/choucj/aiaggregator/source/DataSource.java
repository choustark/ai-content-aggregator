package com.choucj.aiaggregator.source;

import java.util.List;

/**
 * 数据源抽象接口 — 实现"插件化数据源"扩展点(PRD NFR14).
 *
 * <p>泛型化设计:{@code T} 为数据源返回的领域模型类型, 当前已知实现:
 * <ul>
 *   <li>{@code DataSource<Tweet>} — 由 {@code TwitterSource} 实现(Epic 2, Story 2.1-2.2)</li>
 *   <li>{@code DataSource<GitHubRepo>} — 由 {@code GitHubSource} 实现(Epic 4, Story 4.1-4.2)</li>
 * </ul>
 *
 * <p><b>泛型化决策(对架构文档 580 行的合理改进):</b>
 * architecture.md 原始签名为非泛型 {@code interface DataSource { List<Tweet> fetch(); }},
 * 但考虑 GitHub 数据源返回 {@code List<GitHubRepo>}, 本接口升级为泛型 {@code DataSource<T>}.
 *
 * <p>实现类通过显式声明类型参数(如 {@code TwitterSource implements DataSource<Tweet>}),
 * Spring 在 {@code ListableBeanFactory} 中可正确解析具体泛型类型。
 *
 * @param <T> 数据源返回的领域模型类型
 */
public interface DataSource<T> {

    /**
     * 从数据源抓取最新内容.
     *
     * <p>调用方通常是 Pipeline / Scheduler. 实现类应处理:
     * <ul>
     *   <li>分页拉取(如 RSSHub 的 /twitter/user/:id 返回 JSON 数组)</li>
     *   <li>限流 / 重试(由 {@code ResilienceProperties} 控制)</li>
     *   <li>失败时抛出 {@code RetryableException}(Story 1.4 引入)</li>
     * </ul>
     *
     * @return 抓取到的领域模型列表; 无数据时返回空列表(非 {@code null})
     */
    List<T> fetch();
}
