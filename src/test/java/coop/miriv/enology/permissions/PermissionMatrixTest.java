package coop.miriv.enology.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import coop.miriv.enology.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F1C-10: drives every write endpoint with a REAL login (so the actual permission grants from
 * the seed / migration apply, not a hand-built principal) for every dev-seed user, and asserts
 * the matrix in the plan (§2A.4): 403 exactly where the user has none of the listed permissions,
 * anything else (400/404/422/2xx — the endpoint exists and does its own validation) otherwise.
 *
 * If this test ever needs to change, the permission matrix changed and the review that reads it
 * must be re-approved: do not "fix" a red row by loosening the assertion.
 */
@AutoConfigureMockMvc
class PermissionMatrixTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private static final String PASSWORD = "ChangeMe123!";

    /** method, path, allowed usernames (everyone else in ALL_USERS must get 403). */
    private record Row(HttpMethod method, String path, List<String> allowed) {}

    private static final List<String> ALL_USERS =
        List.of("admin", "enologo", "laboratorio", "operario", "produccion", "consulta");

    private static Row row(HttpMethod method, String path, String... allowed) {
        return new Row(method, path, List.of(allowed));
    }

    private static Stream<Row> matrix() {
        return Stream.of(
            row(HttpMethod.POST, "/api/deposits", "admin", "enologo", "produccion"),
            row(HttpMethod.POST, "/api/lots", "enologo", "produccion"),
            row(HttpMethod.POST, "/api/movements", "enologo", "operario", "produccion"),
            row(HttpMethod.POST, "/api/movements/deposits/X/content-clearance", "enologo", "produccion"),
            row(HttpMethod.POST, "/api/contents/X/state-reviews", "enologo"),
            row(HttpMethod.POST, "/api/laboratory/samples", "enologo", "laboratorio", "operario"),
            row(HttpMethod.PUT, "/api/laboratory/samples/X/results", "laboratorio"),
            row(HttpMethod.POST, "/api/laboratory/samples/X/validate", "laboratorio"),
            row(HttpMethod.POST, "/api/laboratory/samples/X/invalidate", "enologo", "laboratorio"),
            row(HttpMethod.GET, "/api/admin/users", "admin"),
            row(HttpMethod.POST, "/api/admin/centers", "admin"),
            row(HttpMethod.GET, "/api/admin/audit", "admin", "enologo"),
            row(HttpMethod.POST, "/api/catalogs/varieties", "admin"),
            row(HttpMethod.GET, "/api/deposits", ALL_USERS.toArray(String[]::new))
        );
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrix")
    void enforcesTheMatrix(Row testCase) throws Exception {
        for (String username : ALL_USERS) {
            String token = login(username);
            int status = mvc.perform(request(testCase.method(), testCase.path())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andReturn().getResponse().getStatus();
            if (testCase.allowed().contains(username)) {
                assertNotEquals(403, status, username + " should be allowed on " + testCase.method() + " " + testCase.path());
            } else {
                assertEquals(403, status, username + " should be FORBIDDEN on " + testCase.method() + " " + testCase.path());
            }
        }
    }

    /**
     * CA-USR-01: a read-only ("consulta") user calling a write endpoint directly is denied
     * before the request body is even inspected — no analysis is created, corrected or touched.
     */
    @Test
    void viewerCannotMutateLaboratoryResults() throws Exception {
        Integer resultRowsBefore = jdbc.queryForObject("select count(*) from result", Integer.class);
        String sampleCode = "CA-USR-01-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String consultaToken = login("consulta");
        int status = mvc.perform(put("/api/laboratory/samples/" + sampleCode + "/results")
                .header("Authorization", "Bearer " + consultaToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn().getResponse().getStatus();
        assertEquals(403, status, "consulta must never be allowed to write laboratory results.");
        assertEquals(resultRowsBefore, jdbc.queryForObject("select count(*) from result", Integer.class),
            "The denied request must not have written anything.");
    }

    private MockHttpServletRequestBuilder request(HttpMethod method, String path) {
        return switch (method.name()) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
            .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("accessToken").asText();
    }
}
