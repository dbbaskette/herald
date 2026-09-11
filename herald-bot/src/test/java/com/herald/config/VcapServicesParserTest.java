package com.herald.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class VcapServicesParserTest {
    static String fixture(String name) throws Exception {
        try (var input = VcapServicesParserTest.class.getResourceAsStream("/genai/" + name + ".json")) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "{}", "{\"postgres\":[{\"credentials\":42}]}"})
    void noBindingUsesLocal(String json) {
        assertThat(VcapServicesParser.parse(json, null, null)).isEmpty();
    }
    @Test void supportedFormats() throws Exception {
        var single = VcapServicesParser.parse(fixture("single-model"), null, null).orElseThrow();
        assertThat(single.baseUrl()).isEqualTo("https://models.example.test/team/openai/v1");
        assertThat(single.model()).isEqualTo("test/chat-model");
        assertThat(single.apiKey()).isEqualTo("synthetic-secret");
        assertThat(single.toString()).doesNotContain("synthetic-secret", "models.example", "test/chat-model");
        var endpoint = VcapServicesParser.parse(fixture("endpoint-only"), null, "selected-model").orElseThrow();
        assertThat(endpoint.baseUrl()).isEqualTo(single.baseUrl());
        assertThat(endpoint.model()).isEqualTo("selected-model");
    }
    @Test void selectionBeforeCredentialValidation() throws Exception {
        String json = fixture("multiple");
        assertThatThrownBy(() -> VcapServicesParser.parse(json, null, null)).hasMessageContaining("MULTIPLE_BINDINGS");
        assertThat(VcapServicesParser.parse(json, "chosen", null)).isPresent();
        assertThatThrownBy(() -> VcapServicesParser.parse(json, "absent", null)).hasMessageContaining("SERVICE_SELECTION");
        assertThatThrownBy(() -> VcapServicesParser.parse(json.replace("unselected", "chosen"), "chosen", null))
                .hasMessageContaining("SERVICE_SELECTION");
        assertThat(VcapServicesParser.parse(fixture("single-model").replace("instance_name", "name"), "chosen", null)).isPresent();
        assertThatThrownBy(() -> VcapServicesParser.parse(fixture("single-model").replace("\"instance_name\": \"chosen\"", "\"instance_name\": \"different\", \"name\": \"chosen\""), "chosen", null))
                .hasMessageContaining("SERVICE_SELECTION");
    }
    @ParameterizedTest @ValueSource(strings = {"[]", "null", "{\"x\":{}}", "{\"x\":[null]}", "{\"x\":[{\"tags\":42}]}", "{\"x\":[{\"tags\":[42]}]}", "{broken synthetic-secret", "{} {}"})
    void invalidStructureIsSanitized(String json) { assertSanitized(json); }
    @ParameterizedTest @ValueSource(strings = {"http://user:synthetic-secret@host/path", "relative", "https://host/path?key=synthetic-secret", "https://host/#synthetic-secret", "ftp://host", "https:///path", " "})
    void invalidEndpoints(String url) throws Exception {
        assertSanitized(fixture("single-model").replace("https://models.example.test/team/openai", url));
    }
    @ParameterizedTest @ValueSource(strings = {"api_base", "api_key", "model_name", "wire_format"})
    void singleTupleMustBeComplete(String field) throws Exception {
        String json = fixture("single-model");
        assertSanitized(json.replaceAll("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": null"));
        assertSanitized(json.replaceAll("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": 42"));
        assertSanitized(json.replaceAll("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": \" \""));
    }
    @Test void malformedCredentials() throws Exception {
        assertSanitized(fixture("malformed"));
        assertSanitized(fixture("single-model").replace("\"openai\"", "\"anthropic\""));
        assertSanitized(fixture("single-model").replace("[\n          \"chat\",\n          \"tools\"\n        ]", "[\"embeddings\"]"));
        assertSanitized(fixture("single-model").replace("[\n          \"chat\",\n          \"tools\"\n        ]", "42"));
        assertSanitized(fixture("endpoint-only"));
        assertThat(VcapServicesParser.parse(fixture("unrelated"), null, null)).isEmpty();
        assertThatThrownBy(() -> VcapServicesParser.parse("{}", "chosen", null)).hasMessageContaining("SERVICE_SELECTION");
    }
    @ParameterizedTest @ValueSource(strings = {"api_base", "api_key"})
    void endpointFieldsMustBeNonblankStrings(String field) throws Exception {
        String json = fixture("endpoint-only");
        for (String invalid : java.util.List.of("null", "42", "[]", "\" \"")) {
            String malformed = json.replaceAll("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": " + invalid);
            assertThatThrownBy(() -> VcapServicesParser.parse(malformed, null, "selected"))
                    .hasMessageContaining("GENAI_INVALID_CREDENTIALS").hasMessageNotContaining("synthetic-secret").hasNoCause();
        }
    }

    private void assertSanitized(String json) {
        var error = catchThrowable(() -> VcapServicesParser.parse(json, null, null));
        assertThat(error).isInstanceOf(IllegalStateException.class).hasNoCause();
        var output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        assertThat(output.toString()).doesNotContain("synthetic-secret", "models.example.test").contains("GENAI_");
    }
}
