package com.easyshell.server.ai.channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelMessageRouterTest {
    @Test
    void explicitlyBoundInboundDelegatesWithoutReturningInternalErrors() {
        var bound=mock(BoundChannelChatService.class);
        var router=new ChannelMessageRouter(List.of(),mock(SystemConfigRepository.class),mock(CryptoUtils.class));
        org.springframework.test.util.ReflectionTestUtils.setField(router,"boundChat",bound);
        when(bound.reply("telegram","12345","hello")).thenReturn("reply");
        assertEquals("reply",router.routeMessage("telegram","hello","12345"));
        when(bound.reply("telegram","12345","hello")).thenThrow(new IllegalStateException("secret-provider-error"));
        assertFalse(router.routeMessage("telegram","hello","12345").contains("secret-provider-error"));
    }

    @Test
    void inboundAlwaysFailsClosedWithoutReadingConfigurationOrLoggingUntrustedData() {
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        ChannelMessageRouter router = new ChannelMessageRouter(List.of(), configs, mock(CryptoUtils.class));
        Logger logger = (Logger) LoggerFactory.getLogger(ChannelMessageRouter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String message : new String[]{"secret-message", "/new", null}) {
                String result = router.routeMessage("secret-channel", message, "secret-identity");
                assertTrue(result.contains("未授权"));
                assertTrue(result.contains("not authorized"));
            }
            verifyNoInteractions(configs);
            assertEquals(3, appender.list.size());
            for (ILoggingEvent event : appender.list) {
                assertEquals(Level.WARN, event.getLevel());
                assertFalse(event.getFormattedMessage().contains("secret"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void outboundPushRemainsAvailable() {
        BotChannelService channel = mock(BotChannelService.class);
        when(channel.getChannelKey()).thenReturn("wechat-work");
        when(channel.isRunning()).thenReturn(true);
        when(channel.pushMessage("webhook", "notification")).thenReturn(true);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        SystemConfig enabled = mock(SystemConfig.class);
        when(enabled.getConfigValue()).thenReturn("true");
        when(configs.findByConfigKey("ai.channel.wechat-work.enabled")).thenReturn(Optional.of(enabled));
        ChannelMessageRouter router = new ChannelMessageRouter(List.of(channel), configs, mock(CryptoUtils.class));
        router.init();
        assertEquals(PushOutcome.DELIVERED, router.pushMessage("wechat-work", "notification"));
        verify(channel).pushMessage("webhook", "notification");
    }
}
