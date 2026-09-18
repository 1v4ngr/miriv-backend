package coop.miriv.enology.identity.dto;
import jakarta.validation.constraints.*;
import java.util.List;
/** {@code roleCodes} defaults to VIEWER when empty so a new account is never created without any role. */
public record CreateAdminUserRequest(@NotBlank String username,@Email @NotBlank String email,@NotBlank String firstName,String lastName,String jobTitle,@NotBlank String password,@NotEmpty List<String> centerCodes,List<String> roleCodes) {}
