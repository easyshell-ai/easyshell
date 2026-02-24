package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.Agent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterDetailVO {

    private Long id;
    private String name;
    private String description;
    private Long createdBy;
    private List<Agent> agents;
}
