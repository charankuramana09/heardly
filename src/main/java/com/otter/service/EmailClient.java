package com.otter.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends emails through the configured SMTP server (Gmail when using a Gmail
 * App Password). The integration stays disabled unless spring.mail.host is set,
 * so the rest of the app keeps working with no mail server configured.
 */
@Service
public class EmailClient {

    private static final Logger log = LoggerFactory.getLogger(EmailClient.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String from;
    private final boolean configured;

    public EmailClient(
        ObjectProvider<JavaMailSender> mailSenderProvider,
        @Value("${spring.mail.host:}") String mailHost,
        @Value("${otterfree.mail.from:}") String from
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.from = from;
        this.configured = mailHost != null && !mailHost.isBlank();
    }

    /** True when an SMTP host is configured and a mail sender bean is available. */
    public boolean isEnabled() {
        return configured && mailSenderProvider.getIfAvailable() != null;
    }

    /** Sends an HTML email. Throws if email is not configured or sending fails. */
    public void send(String to, String subject, String htmlBody) throws Exception {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (!configured || sender == null) {
            throw new IllegalStateException(
                "Email is not configured. Set MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD to enable it.");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("Recipient email address is required");
        }
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        if (from != null && !from.isBlank()) {
            helper.setFrom(from);
        }
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        sender.send(message);
        log.info("Sent summary email to {}", to);
    }
}
