package com.mengde.clouddrive.authservice;

import java.util.List;

/** 验签后的身份。与单体 com.mengde.clouddrive.auth.AuthenticatedUser 结构一致，保证 token 可互换。 */
public record AuthenticatedUser(long id, String username, List<String> roles) { }
