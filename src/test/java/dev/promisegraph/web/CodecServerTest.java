package dev.promisegraph.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The codec server, the way the Temporal UI calls it: from another origin, in the browser. */
@SpringBootTest(properties = "promisegraph.temporal.worker-enabled=false")
@AutoConfigureMockMvc
class CodecServerTest {

    @Autowired
    MockMvc mvc;

    final ObjectMapper json = new ObjectMapper();
    final Base64.Encoder b64 = Base64.getEncoder();

    String plainPayloads() {
        return "{\"payloads\":[{\"metadata\":{\"encoding\":\"" + b64.encodeToString("json/plain".getBytes()) + "\"},"
                + "\"data\":\"" + b64.encodeToString("\"Please be prepared for the presentation.\"".getBytes()) + "\"}]}";
    }

    JsonNode call(String path, String body) throws Exception {
        String out = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Origin", "http://localhost:8080").content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    @Test
    void encodeThenDecodeGivesTheOriginalBack() throws Exception {
        JsonNode sealed = call("/codec/encode", plainPayloads());
        String encoding = new String(Base64.getDecoder().decode(sealed.at("/payloads/0/metadata/encoding").asText()));
        assertThat(encoding).isEqualTo("binary/encrypted");
        assertThat(new String(Base64.getDecoder().decode(sealed.at("/payloads/0/data").asText()))).doesNotContain("presentation");

        JsonNode opened = call("/codec/decode", sealed.toString());
        assertThat(new String(Base64.getDecoder().decode(opened.at("/payloads/0/data").asText()))).contains("presentation");
        assertThat(new String(Base64.getDecoder().decode(opened.at("/payloads/0/metadata/encoding").asText()))).isEqualTo("json/plain");
    }

    @Test
    void onlyTheTemporalUiPageMayCallItFromABrowser() throws Exception {
        mvc.perform(options("/codec/decode").header("Origin", "http://localhost:8080").header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-namespace"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/codec/decode").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherComputerIsRefused() throws Exception {
        mvc.perform(post("/codec/decode").contentType(MediaType.APPLICATION_JSON).content(plainPayloads())
                        .with(r -> { r.setRemoteAddr("192.168.1.20"); return r; }))
                .andExpect(status().isForbidden());
    }

    @Test
    void badInputIsABadRequest() throws Exception {
        mvc.perform(post("/codec/decode").contentType(MediaType.APPLICATION_JSON).content("{\"nope\":1}")).andExpect(status().isBadRequest());
    }
}
