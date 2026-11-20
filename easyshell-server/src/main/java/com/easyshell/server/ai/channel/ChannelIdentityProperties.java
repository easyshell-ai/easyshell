package com.easyshell.server.ai.channel;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** Deployment-owned bindings, never supplied by incoming messages or model output. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix="easyshell.ai.channel-identities")
public class ChannelIdentityProperties {
    private boolean enabled=false;
    private List<Binding> bindings=new ArrayList<>();

    @Getter @Setter
    public static class Binding {
        private String channel;
        private String externalId;
        private Long userId;
    }
}
