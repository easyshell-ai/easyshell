package com.easyshell.server.ai.channel;

import com.easyshell.server.ai.model.dto.AiChatRequest;
import com.easyshell.server.ai.model.vo.AiChatResponseVO;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundChannelChatServiceTest {
    @Test void explicitStableBindingUsesCurrentUserWithNoToolsOrSuppliedSession() {
        var properties=new ChannelIdentityProperties();properties.setEnabled(true);
        var binding=new ChannelIdentityProperties.Binding();binding.setChannel("telegram");binding.setExternalId("12345");binding.setUserId(7L);
        properties.setBindings(List.of(binding));
        var users=mock(UserRepository.class);var chats=mock(AiChatService.class);var filter=new SensitiveDataFilter();
        var user=new User();user.setStatus(1);user.setRole("operator");when(users.findById(7L)).thenReturn(Optional.of(user));
        when(chats.chat(any(),eq(7L),isNull())).thenReturn(AiChatResponseVO.builder().content("password=synthetic-value").build());
        var service=new BoundChannelChatService(properties,users,chats,filter);
        assertFalse(service.reply("telegram","12345","password=synthetic-input").contains("synthetic-value"));
        var captured=ArgumentCaptor.forClass(AiChatRequest.class);verify(chats).chat(captured.capture(),eq(7L),isNull());
        assertEquals(false,captured.getValue().getEnableTools());assertNull(captured.getValue().getSessionId());
        assertNull(captured.getValue().getTargetAgentIds());
        assertFalse(captured.getValue().getMessage().contains("synthetic-input"));
        user.setStatus(0);assertThrows(BusinessException.class,()->service.reply("telegram","12345","hello"));
        properties.setBindings(List.of());assertThrows(BusinessException.class,()->service.reply("telegram","12345","hello"));
        verifyNoMoreInteractions(chats);
    }


    @Test void defaultDisabledDuplicateBindingsAndDisplayNamesNeverInvokeAi() {
        var properties=new ChannelIdentityProperties();var users=mock(UserRepository.class);var chats=mock(AiChatService.class);
        var service=new BoundChannelChatService(properties,users,chats,new SensitiveDataFilter());
        assertThrows(BusinessException.class,()->service.reply("telegram","12345","hello"));
        properties.setEnabled(true);
        var binding=new ChannelIdentityProperties.Binding();binding.setChannel("telegram");binding.setExternalId("12345");binding.setUserId(7L);
        properties.setBindings(List.of(binding,binding));
        assertThrows(BusinessException.class,()->service.reply("telegram","12345","hello"));
        assertThrows(BusinessException.class,()->service.reply("telegram","admin-name","hello"));
        assertThrows(BusinessException.class,()->service.reply("feishu","12345","hello"));
        assertThrows(BusinessException.class,()->service.reply("slack","U123456","hello"));
        verifyNoInteractions(users,chats);
    }

    @Test void discordBindingIncludesReplyChannelSoAnotherChannelCannotReuseIt() {
        var properties=new ChannelIdentityProperties();properties.setEnabled(true);
        var binding=new ChannelIdentityProperties.Binding();binding.setChannel("discord");binding.setExternalId("111:222");binding.setUserId(7L);
        properties.setBindings(List.of(binding));
        var users=mock(UserRepository.class);var chats=mock(AiChatService.class);
        var user=new User();user.setStatus(1);user.setRole("viewer");when(users.findById(7L)).thenReturn(Optional.of(user));
        when(chats.chat(any(),eq(7L),isNull())).thenReturn(AiChatResponseVO.builder().content("reply").build());
        var service=new BoundChannelChatService(properties,users,chats,new SensitiveDataFilter());
        assertThrows(BusinessException.class,()->service.reply("discord","333:222","hello"));
        assertThrows(BusinessException.class,()->service.reply("discord","222","hello"));
        verifyNoInteractions(users,chats);
        assertEquals("reply",service.reply("discord","111:222","hello"));
    }
}
