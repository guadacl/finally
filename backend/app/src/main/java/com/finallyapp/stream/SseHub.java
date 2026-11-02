package com.finallyapp.stream;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Thread-safe registry of SSE emitters for one stream, with broadcast and heartbeat. */
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final String name;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseHub(String name) {
        this.name = name;
    }

    /** Registers a new emitter with no timeout; it is removed on completion, timeout or error. */
    public SseEmitter register(Consumer<SseEmitter> onConnect) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().reconnectTime(1000).comment("connected"));
            if (onConnect != null) {
                onConnect.accept(emitter);
            }
        } catch (IOException | RuntimeException e) {
            drop(emitter, e);
        }
        log.debug("{} SSE client connected ({} total)", name, emitters.size());
        return emitter;
    }

    public void broadcast(String event, Object data) {
        for (SseEmitter emitter : emitters) {
            send(emitter, event, data);
        }
    }

    /** Sends to one emitter; drops it on failure. Returns false if dropped. */
    public boolean send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | RuntimeException e) {
            drop(emitter, e);
            return false;
        }
    }

    /** Sends the {@code : ping} comment heartbeat to every client. */
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | RuntimeException e) {
                drop(emitter, e);
            }
        }
    }

    public int size() {
        return emitters.size();
    }

    private void drop(SseEmitter emitter, Exception e) {
        emitters.remove(emitter);
        log.debug("{} SSE client dropped: {}", name, e.toString());
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // already completed
        }
    }
}
