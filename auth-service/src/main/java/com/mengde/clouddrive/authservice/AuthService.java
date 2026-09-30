package com.mengde.clouddrive.authservice;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AuthService {

    private final UserAccountMapper mapper;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;

    public AuthService(UserAccountMapper mapper, PasswordEncoder encoder, JwtTokenService tokens) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    public UserAccountRow register(String username, String password) {
        UserAccountRow row = new UserAccountRow();
        row.setUsername(normalize(username));
        row.setPasswordHash(encoder.encode(password));
        row.setRole("USER");
        try {
            mapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            // 靠数据库唯一索引 uk_user_account_username 判重，而不是先 SELECT 再 INSERT。
            // 后者在并发注册同名用户时会两个都通过检查、其中一个插入失败抛 500。
            throw new UsernameAlreadyExistsException();
        }
    }

    public JwtTokenService.IssuedToken login(String username, String password) {
        UserAccountRow row = mapper.findByUsername(normalize(username))
                .orElseThrow(InvalidCredentialsException::new);
        if (!encoder.matches(password, row.getPasswordHash())) {
            // 用户不存在与密码错误抛同一个异常、返回同一个响应体：
            // 区分开会变成一个用户名枚举接口。
            throw new InvalidCredentialsException();
        }
        return tokens.issue(row);
    }

    public UserAccountRow identity(long id) {
        return mapper.findById(id).orElseThrow(InvalidCredentialsException::new);
    }

    private String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
