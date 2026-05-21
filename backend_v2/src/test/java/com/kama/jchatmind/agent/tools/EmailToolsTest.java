package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.service.EmailService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailToolsTest {

    private final RecordingEmailService emailService = new RecordingEmailService();
    private final EmailTools tools = new EmailTools(emailService);

    @Test
    void shouldReturnExpectedName() {
        assertEquals("emailTool", tools.getName());
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        assertNotNull(tools.getDescription());
    }

    @Test
    void shouldBeOptionalToolType() {
        assertEquals(ToolType.OPTIONAL, tools.getType());
    }

    @Test
    void shouldSendEmailSuccessfully() {
        String result = tools.sendEmail("test@qq.com", "测试主题", "测试内容");

        assertTrue(result.contains("邮件已提交发送"));
        assertTrue(result.contains("test@qq.com"));
        assertTrue(result.contains("测试主题"));
        assertEquals("test@qq.com", emailService.lastTo);
        assertEquals("测试主题", emailService.lastSubject);
        assertEquals("测试内容", emailService.lastContent);
    }

    @Test
    void shouldRejectNullRecipient() {
        String result = tools.sendEmail(null, "主题", "内容");
        assertTrue(result.contains("收件人邮箱地址不能为空"));
        assertEquals(0, emailService.sendCount);
    }

    @Test
    void shouldRejectEmptyRecipient() {
        String result = tools.sendEmail("   ", "主题", "内容");
        assertTrue(result.contains("收件人邮箱地址不能为空"));
        assertEquals(0, emailService.sendCount);
    }

    @Test
    void shouldRejectNullSubject() {
        String result = tools.sendEmail("test@qq.com", null, "内容");
        assertTrue(result.contains("邮件主题不能为空"));
        assertEquals(0, emailService.sendCount);
    }

    @Test
    void shouldRejectEmptySubject() {
        String result = tools.sendEmail("test@qq.com", "   ", "内容");
        assertTrue(result.contains("邮件主题不能为空"));
        assertEquals(0, emailService.sendCount);
    }

    @Test
    void shouldRejectNullContent() {
        String result = tools.sendEmail("test@qq.com", "主题", null);
        assertTrue(result.contains("邮件内容不能为空"));
        assertEquals(0, emailService.sendCount);
    }

    @Test
    void shouldRejectEmptyContent() {
        String result = tools.sendEmail("test@qq.com", "主题", "   ");
        assertTrue(result.contains("邮件内容不能为空"));
        assertEquals(0, emailService.sendCount);
    }

    @Test
    void shouldRejectInvalidEmailFormat() {
        String result = tools.sendEmail("invalid-email", "主题", "内容");
        assertTrue(result.contains("收件人邮箱地址格式不正确"));
        assertEquals(0, emailService.sendCount);
    }

    private static class RecordingEmailService implements EmailService {
        private String lastTo;
        private String lastSubject;
        private String lastContent;
        private int sendCount;

        @Override
        public void sendEmailAsync(String to, String subject, String content) {
            this.lastTo = to;
            this.lastSubject = subject;
            this.lastContent = content;
            this.sendCount++;
        }
    }
}
