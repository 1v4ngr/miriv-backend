package coop.miriv.enology.assistant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.security.JwtService;
import coop.miriv.enology.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class McpEndpointTest extends IntegrationTest {

    private static final String ACCEPT = "application/json, text/event-stream";

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired AppUserDetailsService userDetails;

    @Test
    void mcpRequiresAuthentication() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).header("Accept", ACCEPT)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void toolsListExposesDepositTools() throws Exception {
        List<String> authorities = userDetails.loadUserByUsername("enologo").getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).toList();
        String token = jwt.issueAccessToken("enologo", authorities);
        String body = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).header("Accept", ACCEPT)
                .header("Authorization", "Bearer " + token)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("list_deposits"), body);
        assertTrue(body.contains("get_deposit_latest_analysis"), body);
    }

    @Test
    void toolCallReturnsDepositsOfUsersCenter() throws Exception {
        List<String> authorities = userDetails.loadUserByUsername("enologo").getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).toList();
        String token = jwt.issueAccessToken("enologo", authorities);
        String body = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).header("Accept", ACCEPT)
                .header("Authorization", "Bearer " + token)
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_deposits\",\"arguments\":{}}}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"result\""), body);
        assertTrue(!body.contains("\"isError\":true"), body);
    }
}
