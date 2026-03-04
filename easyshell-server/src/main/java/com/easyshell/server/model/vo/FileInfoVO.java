package com.easyshell.server.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileInfoVO {
    private String name;
    
    @JsonProperty("isDir")
    private boolean dir;
    
    private long size;
    private String mode;
    private long modTime;
}
