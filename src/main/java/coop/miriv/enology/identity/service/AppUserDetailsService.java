package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public AppUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return appUserRepository.findByUsernameAndActiveTrue(username)
            .or(() -> appUserRepository.findByEmailIgnoreCaseAndActiveTrue(username))
            .map(AppUserPrincipal::new)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown or inactive user: " + username));
    }
}
