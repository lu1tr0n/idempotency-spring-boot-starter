package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code max-response-size}: an over-cap response is streamed to the client in
 * full but not cached (the lock is released, so a retry re-executes); an
 * under-cap response is cached and replayed.
 */
@SpringBootTest(
    classes = ResponseSizeCapServletTest.TestApp.class,
    properties = {
        "spring.idempotency.max-response-size=64B",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@AutoConfigureMockMvc
class ResponseSizeCapServletTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PadController controller;

    @Autowired
    InMemoryIdempotencyStore store;

    @AfterEach
    void reset() {
        controller.reset();
        store.clear();
    }

    @Test
    void overCapResponse_streamedInFull_butNotCached() throws Exception {
        String bigPad = "x".repeat(200);
        // First call returns a >64B body: the client gets it in full...
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pad\":200}"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString(bigPad)))
            .andExpect(header().doesNotExist("Idempotency-Replayed"));

        // ...but it was not cached, so the same key re-executes the handler.
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pad\":3}"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(controller.calls()).isEqualTo(2);
    }

    @Test
    void underCapResponse_isCachedAndReplayed() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pad\":3}"))
            .andExpect(status().isOk());

        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pad\":3}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"));

        assertThat(controller.calls()).isEqualTo(1);
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        InMemoryIdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        PadController padController() {
            return new PadController();
        }
    }

    @RestController
    static class PadController {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/op")
        public Map<String, Object> op(@RequestBody Map<String, Object> body) {
            calls.incrementAndGet();
            int pad = body.get("pad") instanceof Number num ? num.intValue() : 0;
            return Map.of("pad", "x".repeat(pad));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
