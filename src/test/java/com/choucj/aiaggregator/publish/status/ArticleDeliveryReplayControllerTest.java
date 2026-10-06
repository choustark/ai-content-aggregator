package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.task.queue.TaskQueue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleDeliveryReplayControllerTest {

    private final ArticleDeliveryReplayService service = mock(ArticleDeliveryReplayService.class);

    @Test
    void should_reject_when_token_is_missing_or_incorrect() {
        ArticleDeliveryReplayController controller = new ArticleDeliveryReplayController(service, "secret");

        assertThat(controller.replay("tw-1", null, "req-1").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.replay("tw-1", "wrong", "req-1").getStatusCode().value()).isEqualTo(403);
        verify(service, never()).replay("tw-1", "req-1");
    }

    @Test
    void should_return_new_linked_task_when_authorized() {
        ArticleDeliveryReplayController controller = new ArticleDeliveryReplayController(service, "secret");
        when(service.replay("tw-1", "req-1")).thenReturn(new TaskQueue.ReplayResult(
                TaskQueue.ReplayOutcome.REPLAYED, "delivery:tw-1:replay:req-1"));

        var response = controller.replay("tw-1", "secret", "req-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("newTaskId", "delivery:tw-1:replay:req-1");
    }

    @Test
    void should_preserve_not_dead_letter_as_404_and_conflicts_as_409() {
        ArticleDeliveryReplayController controller = new ArticleDeliveryReplayController(service, "secret");
        when(service.replay("tw-1", "missing")).thenReturn(new TaskQueue.ReplayResult(
                TaskQueue.ReplayOutcome.NOT_DEAD_LETTER, null));
        when(service.replay("tw-1", "conflict")).thenReturn(new TaskQueue.ReplayResult(
                TaskQueue.ReplayOutcome.TARGET_CONFLICT, null));

        assertThat(controller.replay("tw-1", "secret", "missing").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.replay("tw-1", "secret", "conflict").getStatusCode().value()).isEqualTo(409);
    }
}
