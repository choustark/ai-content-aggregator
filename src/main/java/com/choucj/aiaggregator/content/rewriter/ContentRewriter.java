package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.twitter.model.Tweet;

/**
 * 内容改写器抽象接口 — AI 改写扩展点(PRD FR4, NFR15).
 *
 * <p>实现类:
 * <ul>
 *   <li>{@code SingleModelRewriter} — Story 2.4 单模型改写, Story 5.3 在该实现内可选注入 RAG 参考上下文;
 *       {@code feature-flags.multi-model.enabled=false/missing} 时注册</li>
 *   <li>{@code MultiModelRewriter} — Story 5.4 多模型投票改写;
 *       {@code feature-flags.multi-model.enabled=true} 时注册, 与 Single 互斥</li>
 * </ul>
 *
 * <p>Processor 侧始终只注入一个 {@code ContentRewriter}; 多模型能力是实现层替换, 不要求
 * Twitter/GitHub Processor 增加条件分支.
 *
 * <p><b>签名设计 (Story 4.4 扩展):</b>
 * 原始 PRD 只有 Twitter → Article 一条改写路径. Story 4.4 GitHub Pipeline Integration
 * 需要直接改写 {@link GitHubRepo} → {@link Article}, 在此接口添加
 * {@code default Article rewrite(GitHubRepo repo)} 方法并默认抛
 * {@link NonRetryableException}, 由 {@code SingleModelRewriter} 覆盖. 默认抛
 * {@code NonRetryableException} (非 {@code UnsupportedOperationException}) 保持项目异常
 * 分类法一致 — 调用方 (GitHubProcessor) per-article catch(Exception) 仍可隔离单条失败 (L2 防御).
 */
public interface ContentRewriter {

    /**
     * 将推文改写为可发布的文章.
     *
     * <p>改写流程(由实现类完成):
     * <ol>
     *   <li>从 {@link Tweet#getContent()} / {@link Tweet#getSummary()} 提取源文本</li>
     *   <li>调用 LLM 生成 Markdown 正文 + 标题 + 摘要; 单模型实现走 DeepSeek→GLM fallback,
     *       多模型实现并行调用 DeepSeek/GLM 后本地评分选优</li>
     *   <li>填充 {@link Article} 的 {@code aiGenerated=true} 标识(合规要求 AR8)</li>
     *   <li>记录 {@code innovationScore}(若由 InnovationFilter 传递, 否则默认 0)</li>
     * </ol>
     *
     * @param tweet 源推文(不应为 {@code null})
     * @return 改写后的 Article(包含 UUID / 标题 / 正文 / digest / source / 时间戳)
     */
    Article rewrite(Tweet tweet);

    /**
     * 将 GitHub 仓库改写为可发布的文章 (Story 4.4 GitHub Pipeline 集成).
     *
     * <p>默认实现抛 {@link NonRetryableException}, 提示实现类未覆盖此路径.
     * {@code SingleModelRewriter} 覆盖此方法消费 {@link GitHubRepo}:
     * 取 {@code readmeContent}(Story 4.2) + {@code valueSummary}(Story 4.3) 作为源文本,
     * 经 LLM 改写为中文微信公众号风格文章, {@code Article.id} 形如 {@code gh-{owner}-{repo}}
     * (B8 确定性 ID).
     *
     * <p><b>D3 警示:</b> 调用方 (GitHubProcessor) 需 null-check {@code repo.valueScore}
     * (Double nullable) 后赋值给 {@code Article.innovationScore} (int primitive).
     * 本方法内部已通过 {@code SingleModelRewriter#convertInnovationScore} 空安全转换.
     *
     * @param repo 源 GitHub 仓库(不应为 {@code null}, 需含 {@code fullName})
     * @return 改写后的 Article({@code id=gh-{owner}-{repo}}, {@code source="GitHub Repo:owner/repo"})
     * @throws NonRetryableException 实现类未覆盖 (默认路径) 或 repo 非法
     */
    default Article rewrite(GitHubRepo repo) {
        throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                "ContentRewriter.rewrite(GitHubRepo) 未实现: "
                        + (repo == null ? "(repo=null)" : repo.getFullName()));
    }
}
