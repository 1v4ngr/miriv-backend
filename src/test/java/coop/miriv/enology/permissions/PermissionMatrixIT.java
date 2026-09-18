package coop.miriv.enology.permissions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.repository.AppUserRepository;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.support.IntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PermissionMatrixIT extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;

    private AppUserPrincipal principalFor(String username) {
        AppUser user = users.findByUsernameAndActiveTrue(username).orElseThrow();
        return new AppUserPrincipal(user, Set.of(), new coop.miriv.enology.identity.service.PermissionScope(true, Set.of()));
    }

    @Test
    void labUserCannotCreateDeposit() throws Exception {
        mvc.perform(get("/api/deposits/anything").with(user(principalFor("laboratorio"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void consultCannotMutateLaboratory() throws Exception {
        mvc.perform(get("/api/laboratory/samples").with(user(principalFor("consulta"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotManageCatalog() throws Exception {
        mvc.perform(get("/api/catalogs/parameters/SOME").with(user(principalFor("operario")))
                .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
}