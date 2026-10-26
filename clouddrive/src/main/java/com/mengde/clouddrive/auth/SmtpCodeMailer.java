package com.mengde.clouddrive.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/** SMTP credentials come exclusively from external configuration. Codes are never logged. */
@Service
public class SmtpCodeMailer {
    private static final Logger log = LoggerFactory.getLogger(SmtpCodeMailer.class);
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final boolean configured;

    public SmtpCodeMailer(@Value("${clouddrive.mail.host:}") String host,
                          @Value("${clouddrive.mail.port:587}") int port,
                          @Value("${clouddrive.mail.username:}") String username,
                          @Value("${clouddrive.mail.password:}") String password,
                          @Value("${clouddrive.mail.from:}") String from,
                          @Value("${clouddrive.mail.auth:true}") boolean auth,
                          @Value("${clouddrive.mail.starttls:true}") boolean starttls,
                          @Value("${clouddrive.mail.ssl:false}") boolean ssl) {
        if (ssl && starttls) {
            throw new IllegalArgumentException("SMTP SSL and STARTTLS cannot both be enabled; choose one transport mode");
        }
        this.from = from;
        this.configured = !host.isBlank() && !from.isBlank()
                && (!auth || (!username.isBlank() && !password.isBlank()));
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(auth));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(ssl));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
    }

    public void requireConfigured() {
        if (!configured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "邮箱服务未配置，请联系管理员配置 SMTP 后重试");
        }
    }

    public void sendVerification(String email, EmailVerificationService.Purpose purpose, String code) {
        requireConfigured();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(purpose == EmailVerificationService.Purpose.REGISTER
                ? "云盘注册验证码" : "云盘重置密码验证码");
        message.setText("您的验证码是：" + code + "\n验证码 10 分钟内有效，仅可使用一次。"
                + "\n如非本人操作，请忽略此邮件。请勿将验证码提供给他人。");
        try {
            sender.send(message);
        } catch (MailException exception) {
            // Avoid logging SMTP responses, credentials, addresses or the message body.
            log.warn("Verification email delivery failed ({})", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "验证码邮件发送失败，请稍后重试或联系管理员检查 SMTP 配置");
        }
    }
}
