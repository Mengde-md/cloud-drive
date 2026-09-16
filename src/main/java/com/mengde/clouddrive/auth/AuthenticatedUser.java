package com.mengde.clouddrive.auth;
import java.util.List;
public record AuthenticatedUser(long id, String username, List<String> roles) { }
