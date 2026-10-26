package com.mengde.clouddrive.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final AdminAccountService accounts;
    private final String username;
    private final String password;
    private final String email;

    public BootstrapAdmin(AdminAccountService accounts,
                           @Value("${clouddrive.bootstrap-admin.username:}") String username,
                           @Value("${clouddrive.bootstrap-admin.password:}") String password,
                           @Value("${clouddrive.bootstrap-admin.email:}") String email) {
        this.accounts = accounts;
        this.username = username;
        this.password = password;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank() && email.isBlank()) return;
        if (username.isBlank() || password.isBlank() || email.isBlank()) {
            throw new IllegalStateException("Set all CLOUD_DRIVE_ADMIN_USERNAME/PASSWORD/EMAIL values to bootstrap an admin");
        }
        accounts.bootstrap(username, password, email);
    }
}
