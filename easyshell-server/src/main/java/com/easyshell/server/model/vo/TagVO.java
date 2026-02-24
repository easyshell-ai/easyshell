package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagVO {

    private Long id;
    private String name;
    private String color;
    private int agentCount;
}
