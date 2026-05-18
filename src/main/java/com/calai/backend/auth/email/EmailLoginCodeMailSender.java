package com.calai.backend.auth.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Component
public class EmailLoginCodeMailSender {

    private static final String APP_NAME = "BiteCal AI";
    private static final String EMAIL_LOGO_CID = "bitecalLogo";
    private static final String EMAIL_LOGO_PATH = "email/ic_focus_spoon_email.png";

    private final JavaMailSender mail;

    @Value("${app.email.sender:no-reply@bitecal.app}")
    private String sender;

    public EmailLoginCodeMailSender(JavaMailSender mail) {
        this.mail = mail;
    }

    public void sendLoginCode(String to, String code, int ttlMinutes) {
        try {
            MimeMessage message = mail.createMimeMessage();

            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );

            ClassPathResource logo = new ClassPathResource(EMAIL_LOGO_PATH);
            boolean logoExists = logo.exists();

            helper.setFrom(sender, APP_NAME);
            helper.setTo(to);
            helper.setSubject(buildLoginCodeSubject(code));
            helper.setText(
                    buildPlainLoginCodeEmail(code, ttlMinutes),
                    buildHtmlLoginCodeEmail(code, ttlMinutes, logoExists)
            );

            if (logoExists) {
                helper.addInline(EMAIL_LOGO_CID, logo, "image/png");
            }

            mail.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new IllegalStateException("Failed to send login code email", ex);
        }
    }

    private String buildLoginCodeSubject(String code) {
        return "Welcome to BiteCal AI — Your login code is: " + code;
    }

    private String buildPlainLoginCodeEmail(String code, int ttlMinutes) {
        return """
                Welcome to BiteCal AI! 👋
                
                Your login code is:
                
                %s
                
                Open BiteCal AI and enter this code to continue signing in.
                
                This code expires in %d minutes ⏱️
                Please do not share it with anyone.
                
                With ❤️,
                The BiteCal AI Team
                """.formatted(code, ttlMinutes);
    }

    private String buildHtmlLoginCodeEmail(String code, int ttlMinutes, boolean logoExists) {
        String logoHtml = logoExists
                ? """
                <table role="presentation" width="96" height="96" cellspacing="0" cellpadding="0" border="0" align="center" style="width:96px;height:96px;margin:0 auto 22px auto;background:#F4F5F7;border-radius:28px;">
                  <tr>
                    <td align="center" valign="middle" style="width:96px;height:96px;text-align:center;vertical-align:middle;line-height:0;font-size:0;">
                      <img src="cid:%s" width="92" height="92" alt="BiteCal AI" style="display:inline-block;width:92px;height:92px;border:0;outline:none;text-decoration:none;vertical-align:middle;">
                    </td>
                  </tr>
                </table>
                """.formatted(EMAIL_LOGO_CID)
                : """
                <table role="presentation" width="96" height="96" cellspacing="0" cellpadding="0" border="0" align="center" style="width:96px;height:96px;margin:0 auto 22px auto;background:#111113;border-radius:28px;">
                  <tr>
                    <td align="center" valign="middle" style="width:96px;height:96px;text-align:center;vertical-align:middle;color:#FFFFFF;font-size:36px;line-height:96px;font-weight:800;">
                      B
                    </td>
                  </tr>
                </table>
                """;

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <meta http-equiv="X-UA-Compatible" content="IE=edge">
                  <title>BiteCal AI sign-in code</title>
                </head>
                <body style="margin:0;padding:0;background:#F6F7F9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#18181B;">
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">
                    Expires in ${TTL} minutes ⏱️. Please do not share this code.
                  </div>
                
                  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background:#F6F7F9;margin:0;padding:0;">
                    <tr>
                      <td align="center" style="padding:40px 16px;">
                        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width:560px;background:#FFFFFF;border-radius:28px;box-shadow:0 18px 50px rgba(15,23,42,0.08);overflow:hidden;">
                          <tr>
                            <td style="padding:46px 32px 34px 32px;text-align:center;">
                
                              ${LOGO_BLOCK}
                
                              <div style="font-size:15px;line-height:22px;color:#71717A;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;margin-bottom:10px;">
                                🔐 Secure sign-in
                              </div>
                
                              <h1 style="margin:0 0 12px 0;font-size:30px;line-height:38px;font-weight:800;color:#18181B;">
                                Your login code
                              </h1>
                
                              <p style="margin:0 auto 28px auto;max-width:400px;font-size:16px;line-height:25px;color:#52525B;">
                                Enter this code in BiteCal AI to continue. This code expires in
                                <strong style="color:#18181B;">${TTL} minutes</strong> ⏱️
                              </p>
                
                              <div style="background:#F4F5F7;border-radius:24px;padding:26px 18px;margin:0 auto 24px auto;max-width:360px;">
                                <div style="font-size:54px;line-height:64px;font-weight:850;letter-spacing:10px;color:#18181B;text-align:center;">
                                  ${CODE}
                                </div>
                              </div>
                
                              <p style="margin:0 auto 24px auto;max-width:410px;font-size:14px;line-height:22px;color:#71717A;">
                                Please do not share this code with anyone. BiteCal AI will never ask for your code outside the app.
                              </p>
                
                              <div style="height:1px;background:#E4E4E7;margin:30px 0 22px 0;"></div>
                
                              <p style="margin:0;font-size:12px;line-height:19px;color:#A1A1AA;">
                                If you did not request this code, you can safely ignore this email.
                              </p>
                
                            </td>
                          </tr>
                        </table>
                
                        <p style="margin:22px 0 0 0;font-size:12px;line-height:18px;color:#A1A1AA;text-align:center;">
                          See you in the app,<br>The BiteCal AI Team ❤️
                        </p>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """
                .replace("${LOGO_BLOCK}", logoHtml)
                .replace("${CODE}", code)
                .replace("${TTL}", String.valueOf(ttlMinutes));
    }
}
