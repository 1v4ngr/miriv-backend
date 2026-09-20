package coop.miriv.enology;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * A call to an endpoint this build does not have must stay a 404. It used to come back as 401 —
 * Spring forwards the 404 to /error and the catch-all denied it — which logged the user out of the SPA.
 */
@AutoConfigureMockMvc
class MissingEndpointTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired AppUserDetailsService userDetails;

    private String token() {
        List<String> authorities = userDetails.loadUserByUsername("enologo").getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).toList();
        return jwt.issueAccessToken("enologo", authorities);
    }

    @Test
    void unknownApiPathAnswers404AndKeepsTheSession() throws Exception {
        mvc.perform(post("/api/laboratory/imports/does-not-exist")
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());

        mvc.perform(get("/api/this/does/not/exist").header("Authorization", "Bearer " + token()))
            .andExpect(status().isNotFound());
    }

    @Test
    void withoutATokenItIsStill401() throws Exception {
        mvc.perform(get("/api/deposits")).andExpect(status().isUnauthorized());
    }
}
