package com.easyshell.server.ai.channel;

import com.easyshell.server.ai.model.dto.AiChatRequest;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.common.exception.BusinessException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;

/** Invoked only by authenticated channel adapters, with immutable provider subject IDs. */
@Service
public class BoundChannelChatService {
    private final ChannelIdentityProperties properties;
    private final UserRepository users;
    private final AiChatService chat;
    private final SensitiveDataFilter filter;

    public BoundChannelChatService(ChannelIdentityProperties properties, UserRepository users,
                                   @Lazy AiChatService chat, SensitiveDataFilter filter) {
        this.properties=properties;this.users=users;this.chat=chat;this.filter=filter;
    }

    public String reply(String channel,String externalId,String message) {
        if (!properties.isEnabled() || channel==null || !Set.of("telegram","discord").contains(channel)
                || externalId==null || externalId.isBlank() || externalId.length()>256
                || "unknown".equals(externalId) || message==null || message.isBlank() || message.length()>16000)
            throw new BusinessException(403,"Channel identity not authorized");
        if (!("telegram".equals(channel) ? externalId.matches("[1-9][0-9]{0,31}")
                : externalId.matches("[1-9][0-9]{0,31}:[1-9][0-9]{0,31}")))
            throw new BusinessException(403,"Stable provider identity required");
        if (properties.getBindings()==null || properties.getBindings().size()>256)
            throw new BusinessException(403,"Channel identity configuration invalid");
        var matches=properties.getBindings().stream().filter(b->b!=null && Objects.equals(channel,b.getChannel())
                && Objects.equals(externalId,b.getExternalId())).toList();
        if (matches.size()!=1 || matches.get(0).getUserId()==null || matches.get(0).getUserId()<=0)
            throw new BusinessException(403,"Channel identity not authorized");
        Long actor=matches.get(0).getUserId();
        var user=users.findById(actor).orElseThrow(()->new BusinessException(403,"Channel user unavailable"));
        if (!Integer.valueOf(1).equals(user.getStatus()) || user.getRole()==null
                || !Set.of("viewer","operator","admin","super_admin").contains(user.getRole()))
            throw new BusinessException(403,"Channel user unavailable");
        // No supplied session ID and no tools: a bound channel cannot select another user's history
        // or acquire host execution merely by mapping a provider subject to an account.
        AiChatRequest request=new AiChatRequest();request.setMessage(filter.filter(message));request.setEnableTools(false);
        var response=chat.chat(request,actor,null);
        return filter.filter(response.getContent()==null?"":response.getContent());
    }
}
