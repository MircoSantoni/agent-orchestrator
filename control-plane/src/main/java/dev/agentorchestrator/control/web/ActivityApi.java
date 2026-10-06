package dev.agentorchestrator.control.web;

import dev.agentorchestrator.control.events.ActivityStream;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ActivityApi {
    private final ActivityStream stream;
    public ActivityApi(ActivityStream stream) { this.stream = stream; }

    @GetMapping(value="/activity", produces=MediaType.APPLICATION_JSON_VALUE)
    public String activity(@PathVariable UUID projectId, @RequestParam(defaultValue="0") long afterId,
                           @RequestParam(defaultValue="100") int limit) {
        return stream.activity(projectId, afterId, limit);
    }

    @GetMapping(value="/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID projectId, @RequestHeader(value="Last-Event-ID", required=false) Long lastEventId) {
        return stream.subscribe(projectId, lastEventId);
    }
}
